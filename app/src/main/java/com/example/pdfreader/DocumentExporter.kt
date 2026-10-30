package com.example.pdfreader

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.File
import java.io.IOException
import java.io.OutputStream

/**
 * Копия документа в месте, которое выбрали в системном окне «Сохранить».
 *
 * Документ сначала целиком читается во временный файл и только потом пишется
 * на место. Если в окне выбрали тот же самый файл и согласились его заменить,
 * прямая запись обнулила бы его раньше, чем он прочитан, — и от документа
 * не осталось бы ничего.
 */
class DocumentExporter(context: Context) {

    private val appContext = context.applicationContext
    private val resolver: ContentResolver = appContext.contentResolver

    /**
     * Пишет содержимое [source] в [target]. Блокирующий вызов — читает весь файл.
     *
     * @return удалось ли сохранить
     */
    fun export(source: Uri, target: Uri): Boolean {
        // Пустой файл только что создало окно сохранения: если записать не
        // выйдет, его надо убрать, чтобы в папке не осталось пустышки.
        // Непустой выбрали на замену - он чужой, его не трогаем.
        val createdForUs = sizeOf(target) == 0L
        return try {
            copyThroughTemp(source, target)
            true
        } catch (_: Exception) {
            if (createdForUs) discard(target)
            false
        }
    }

    private fun copyThroughTemp(source: Uri, target: Uri) {
        val temp = File.createTempFile("export", ".pdf", appContext.cacheDir)
        try {
            val input = resolver.openInputStream(source)
                ?: throw IOException("Провайдер не отдал содержимое файла")
            input.use { from -> temp.outputStream().use { to -> from.copyTo(to) } }
            openForOverwrite(target).use { to -> temp.inputStream().use { from -> from.copyTo(to) } }
        } finally {
            temp.delete()
        }
    }

    /**
     * Поток на запись, который обрезает прежнее содержимое.
     *
     * Без обрезки замена длинного файла коротким оставила бы в конце хвост
     * старого, а PDF читается как раз с конца. Режим "wt" понимает не каждый
     * провайдер, поэтому есть запасной "w".
     */
    private fun openForOverwrite(target: Uri): OutputStream {
        val stream = try {
            resolver.openOutputStream(target, "wt")
        } catch (_: Exception) {
            null
        } ?: resolver.openOutputStream(target, "w")
        return stream ?: throw IOException("Провайдер не дал записать файл")
    }

    /** Размер файла по словам провайдера, или null, если он его не сообщает. */
    private fun sizeOf(uri: Uri): Long? = try {
        resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (index < 0 || !cursor.moveToFirst() || cursor.isNull(index)) null
            else cursor.getLong(index)
        }
    } catch (_: Exception) {
        null
    }

    private fun discard(uri: Uri) {
        try {
            DocumentsContract.deleteDocument(resolver, uri)
        } catch (_: Exception) {
            // Удалить не дали - останется пустой файл, хуже от этого не станет.
        }
    }
}
