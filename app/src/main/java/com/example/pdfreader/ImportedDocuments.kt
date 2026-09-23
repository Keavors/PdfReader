package com.example.pdfreader

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/** Больше этого в своё хранилище не тянем: файл откроется, но в списке не останется. */
private const val MAX_IMPORT_BYTES = 256L * 1024 * 1024

/** Папка внутри приватного хранилища приложения. */
private const val DIRECTORY = "imported"

private const val SCHEME_FILE = ContentResolver.SCHEME_FILE

private const val MAX_NAME_LENGTH = 80
private const val FORBIDDEN_IN_NAME = "/\\:*?\"<>|"

/**
 * Копии документов, до которых иначе было бы не дотянуться второй раз.
 *
 * Мессенджеры и почта дают доступ к файлу ровно на один сеанс: ссылка живёт,
 * пока приложение её не закрыло, а после перезапуска по ней уже ничего не
 * прочитать. Чтобы такой документ остался в недавних и открывался, его
 * содержимое переносится в приватное хранилище приложения, и список ссылается
 * уже на копию.
 *
 * Копия лежит в папке с техническим именем, а внутри неё — файл под своим
 * настоящим именем: так и заголовок, и подпись в списке остаются человеческими.
 * Копия живёт ровно столько, сколько документ держится в списке недавних.
 */
class ImportedDocuments(context: Context) {

    private val appContext = context.applicationContext
    private val root = File(appContext.filesDir, DIRECTORY)

    /** Ссылка ведёт на нашу собственную копию, а не на чужой файл. */
    fun isImported(uri: String): Boolean = fileFor(uri) != null

    /**
     * Переносит документ к себе и отдаёт ссылку на копию.
     *
     * Блокирующий вызов — читает весь файл. Уже перенесённый документ узнаётся
     * по имени и размеру и второй раз не копируется.
     *
     * @return null, если файл слишком большой или скопировать его не удалось.
     */
    fun copy(source: Uri, name: String, size: Long): Uri? {
        if (size > MAX_IMPORT_BYTES) return null

        val folder = File(root, folderNameFor(name, size))
        val target = File(folder, safeFileName(name))
        if (target.isFile && size > 0 && target.length() == size) return Uri.fromFile(target)

        return try {
            folder.mkdirs()
            val input = appContext.contentResolver.openInputStream(source)
                ?: throw IOException("Провайдер не отдал содержимое файла")
            input.use { from -> target.outputStream().use { to -> from.copyCapped(to) } }
            Uri.fromFile(target)
        } catch (_: Exception) {
            // Недокачанная копия хуже её отсутствия - убираем следы.
            folder.deleteRecursively()
            null
        }
    }

    /** Убирает копию, если эта ссылка на неё и указывала. */
    fun forget(uri: String) {
        folderOf(uri)?.deleteRecursively()
    }

    /**
     * Выносит копии, на которые уже никто не ссылается.
     *
     * Такое остаётся, если приложение убили посреди переноса или настройки
     * почистили мимо нас. Блокирующий вызов.
     */
    fun deleteOrphans(keep: Collection<String>) {
        val used = keep.mapNotNull { folderOf(it)?.absolutePath }.toSet()
        root.listFiles()?.forEach { folder ->
            if (folder.absolutePath !in used) folder.deleteRecursively()
        }
    }

    /**
     * Файл нашей копии по ссылке, или null, если ссылка не наша.
     *
     * Сравниваем пути, а не строки ссылок: в строке разделитель каталогов
     * может оказаться закодированным, и сопоставление по началу строки тогда
     * молча перестаёт работать.
     */
    private fun fileFor(uri: String): File? {
        val parsed = uri.toUri()
        if (parsed.scheme != SCHEME_FILE) return null
        val file = parsed.path?.let(::File) ?: return null
        val prefix = root.absolutePath + File.separator
        return file.takeIf { it.absolutePath.startsWith(prefix) }
    }

    /** Папка копии — её и удаляем целиком, вместе с лежащим внутри файлом. */
    private fun folderOf(uri: String): File? =
        fileFor(uri)?.parentFile?.takeIf { it.parentFile?.absolutePath == root.absolutePath }

    /**
     * Техническое имя папки: один и тот же документ, присланный второй раз,
     * должен попасть в неё же и не копироваться заново.
     */
    private fun folderNameFor(name: String, size: Long): String =
        MessageDigest.getInstance("SHA-256")
            .digest("$name:$size".toByteArray())
            .take(8)
            .joinToString("") { "%02x".format(it) }

    private fun safeFileName(name: String): String {
        val cleaned = name
            .map { if (it in FORBIDDEN_IN_NAME || it.isISOControl()) '_' else it }
            .joinToString("")
            .trim()
            .take(MAX_NAME_LENGTH)
        return cleaned.ifBlank { "document.pdf" }
    }

    /** Копирует поток, обрываясь, если файл оказался больше обещанного. */
    private fun InputStream.copyCapped(out: OutputStream) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            total += read
            if (total > MAX_IMPORT_BYTES) throw IOException("Файл больше допустимого")
            out.write(buffer, 0, read)
        }
    }
}
