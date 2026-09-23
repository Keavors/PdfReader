package com.example.pdfreader

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.edit
import androidx.core.net.toUri
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

/** Сколько файлов помним в списке недавних. */
const val RECENT_FILES_LIMIT = 15

/**
 * Запись списка недавних файлов. Ссылка хранится строкой — так её проще сериализовать.
 *
 * @param page страница, на которой документ закрыли в прошлый раз
 * @param size размер файла в байтах, 0 — если провайдер его не сообщил
 * @param openedAt когда открывали последний раз, 0 — если неизвестно
 */
data class RecentFile(
    val uri: String,
    val name: String,
    val page: Int = 0,
    val size: Long = 0,
    val openedAt: Long = 0,
)

/**
 * Один и тот же документ.
 *
 * Проводники и мессенджеры выдают на один файл разные ссылки — иногда новую на
 * каждое открытие, и по одной только ссылке список набивается копиями. Поэтому
 * сравниваем ещё и то, что от ссылки не зависит: имя вместе с размером.
 *
 * Два действительно разных файла с одинаковым именем и ровно одинаковым
 * размером список схлопнет в один — это заметно меньшее зло, чем десяток
 * одинаковых строк.
 */
fun isSameDocument(a: RecentFile, b: RecentFile): Boolean =
    a.uri == b.uri || (a.size > 0 && a.size == b.size && a.name == b.name)

/**
 * Разбирает сохранённый список.
 * Повреждённые записи пропускаются поодиночке: одна кривая строка не должна
 * стирать весь список с экрана.
 */
fun parseRecentFiles(json: String?): List<RecentFile> {
    if (json.isNullOrBlank()) return emptyList()
    val array = try {
        JSONArray(json)
    } catch (e: Exception) {
        return emptyList()
    }
    val result = mutableListOf<RecentFile>()
    for (i in 0 until array.length()) {
        val item = array.optJSONObject(i) ?: continue
        val uri = item.stringOrNull("uri") ?: continue
        val name = item.stringOrNull("name") ?: continue
        result += RecentFile(
            uri = uri,
            name = name,
            page = item.optInt("page", 0).coerceAtLeast(0),
            size = item.optLong("size", 0).coerceAtLeast(0),
            openedAt = item.optLong("openedAt", 0).coerceAtLeast(0),
        )
    }
    return result
}

/**
 * Значение поля или null.
 *
 * На `optString` полагаться нельзя: на устройстве и в юнит-тестах разные
 * реализации org.json, и для поля со значением null одна отдаёт пустую строку,
 * а другая — строку "null", из которой получилась бы запись с именем «null».
 */
private fun JSONObject.stringOrNull(key: String): String? =
    if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

/** Собирает список обратно в строку для SharedPreferences. */
fun encodeRecentFiles(files: List<RecentFile>): String {
    val array = JSONArray()
    files.forEach {
        array.put(
            JSONObject()
                .put("uri", it.uri)
                .put("name", it.name)
                .put("page", it.page)
                .put("size", it.size)
                .put("openedAt", it.openedAt)
        )
    }
    return array.toString()
}

/** Ставит файл в начало списка, убирая его прежние вхождения и обрезая хвост. */
fun withFileOnTop(
    files: List<RecentFile>,
    file: RecentFile,
    limit: Int = RECENT_FILES_LIMIT,
): List<RecentFile> =
    (listOf(file) + files.filterNot { isSameDocument(it, file) }).take(limit)

/** Насколько давно открывали файл — от этого зависит вид подписи. */
enum class OpenedAtBucket { UNKNOWN, TODAY, YESTERDAY, EARLIER }

/**
 * Сегодня, вчера или раньше. Часовой пояс приходит снаружи: границы суток
 * зависят от него, а в тестах удобно подставить свой.
 */
fun openedAtBucket(openedAt: Long, now: Long, zone: ZoneId): OpenedAtBucket {
    if (openedAt <= 0) return OpenedAtBucket.UNKNOWN
    val day = Instant.ofEpochMilli(openedAt).atZone(zone).toLocalDate()
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return when {
        // Часы могли сбиться вперёд - «в будущем» показываем как сегодня.
        !day.isBefore(today) -> OpenedAtBucket.TODAY
        day == today.minusDays(1) -> OpenedAtBucket.YESTERDAY
        else -> OpenedAtBucket.EARLIER
    }
}

/**
 * Папка файла для подписи под его именем: из ссылки вида
 * `content://.../document/primary%3ADownload%2Fкнига.pdf` получается `Download`.
 *
 * Часть провайдеров отдаёт вместо пути числовой идентификатор — тогда показывать
 * нечего и подпись остаётся пустой.
 */
fun readablePath(uri: String): String = folderFromDecodedUri(Uri.decode(uri) ?: uri)

/** Та же логика без обращения к Android — вынесена отдельно, чтобы её можно было проверить тестом. */
internal fun folderFromDecodedUri(decoded: String): String {
    val document = decoded.substringAfterLast("/document/", "")
    if (document.isEmpty()) return ""
    return document.substringAfter(':', "").substringBeforeLast('/', "")
}

/** Список недавних файлов в настройках приложения. */
class RecentFilesStore(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val imported = ImportedDocuments(appContext)

    fun load(): List<RecentFile> = parseRecentFiles(prefs.getString(KEY_RECENT, null))

    /** Выносит копии документов, которых в списке уже нет. Блокирующий вызов. */
    fun deleteOrphanCopies() = imported.deleteOrphans(load().map { it.uri })

    /**
     * Страница, на которой этот файл закрыли в прошлый раз.
     *
     * Ищем строго по ссылке: на момент открытия документа его размер ещё
     * неизвестен, так что сопоставить файл по содержимому пока нечем.
     */
    fun pageOf(uri: Uri): Int {
        val target = uri.toString()
        return load().firstOrNull { it.uri == target }?.page ?: 0
    }

    fun add(uri: Uri, name: String, page: Int, size: Long, openedAt: Long) {
        val previous = load()
        val file = RecentFile(uri.toString(), name, page, size, openedAt)
        replace(previous, withFileOnTop(previous, file))
    }

    /** Запоминает, до какой страницы дочитали. Незнакомый файл не добавляет. */
    fun updatePage(uri: Uri, page: Int) {
        val target = uri.toString()
        val previous = load()
        val known = previous.firstOrNull { it.uri == target } ?: return
        if (known.page == page) return
        replace(previous, previous.map { if (it.uri == target) it.copy(page = page) else it })
    }

    fun remove(uri: Uri) {
        val previous = load()
        val target = uri.toString()
        replace(previous, previous.filterNot { it.uri == target })
    }

    fun clear() = replace(load(), emptyList())

    /**
     * Пишет новый список и убирает за выпавшими из него файлами: отпускает
     * постоянный доступ и выносит нашу копию документа, если она была.
     *
     * Разрешения не истекают сами, а системный запас их на приложение
     * ограничен: без этого рано или поздно перестали бы выдаваться новые.
     * Копии же иначе просто лежали бы мёртвым грузом.
     */
    private fun replace(previous: List<RecentFile>, current: List<RecentFile>) {
        prefs.edit { putString(KEY_RECENT, encodeRecentFiles(current)) }
        val kept = current.mapTo(HashSet()) { it.uri }
        previous.forEach {
            if (it.uri !in kept) {
                releaseAccess(it.uri)
                imported.forget(it.uri)
            }
        }
    }

    private fun releaseAccess(uri: String) {
        if (!uri.startsWith("${ContentResolver.SCHEME_CONTENT}:")) return
        try {
            appContext.contentResolver.releasePersistableUriPermission(
                uri.toUri(),
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (_: SecurityException) {
            // Постоянного доступа к этому файлу и не было — отпускать нечего.
        }
    }

    private companion object {
        const val KEY_RECENT = "recent_files"
    }
}
