package com.example.pdfreader

import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

/** Сколько файлов помним в списке недавних. */
const val RECENT_FILES_LIMIT = 15

/** Запись списка недавних файлов. Ссылка хранится строкой — так её проще сериализовать. */
data class RecentFile(val uri: String, val name: String)

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
        val uri = item.optString("uri")
        val name = item.optString("name")
        if (uri.isNotEmpty() && name.isNotEmpty()) result += RecentFile(uri, name)
    }
    return result
}

/** Собирает список обратно в строку для SharedPreferences. */
fun encodeRecentFiles(files: List<RecentFile>): String {
    val array = JSONArray()
    files.forEach { array.put(JSONObject().put("uri", it.uri).put("name", it.name)) }
    return array.toString()
}

/** Ставит файл в начало списка, убирая его прежнее вхождение и обрезая хвост. */
fun withFileOnTop(
    files: List<RecentFile>,
    file: RecentFile,
    limit: Int = RECENT_FILES_LIMIT,
): List<RecentFile> = (listOf(file) + files.filterNot { it.uri == file.uri }).take(limit)

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

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): List<RecentFile> = parseRecentFiles(prefs.getString(KEY_RECENT, null))

    fun add(uri: Uri, name: String) {
        save(withFileOnTop(load(), RecentFile(uri.toString(), name)))
    }

    fun remove(uri: Uri) {
        val target = uri.toString()
        save(load().filterNot { it.uri == target })
    }

    private fun save(files: List<RecentFile>) {
        prefs.edit { putString(KEY_RECENT, encodeRecentFiles(files)) }
    }

    private companion object {
        const val KEY_RECENT = "recent_files"
    }
}
