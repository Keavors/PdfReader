package com.example.pdfreader

import android.content.Context
import androidx.core.content.edit

/** Общее имя файла настроек: и режим просмотра, и список недавних лежат в нём. */
const val PREFS_NAME = "reader_prefs"

/**
 * Как показывать документ.
 *
 * @param horizontal листание по горизонтали вместо вертикали
 * @param singlePage строго по одной странице на экран вместо слитной ленты
 */
data class ReaderSettings(
    val horizontal: Boolean = false,
    val singlePage: Boolean = false,
) {
    companion object {
        private const val KEY_HORIZONTAL = "swipe_horizontal"
        private const val KEY_SINGLE = "single_page"

        fun load(context: Context): ReaderSettings {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return ReaderSettings(
                horizontal = prefs.getBoolean(KEY_HORIZONTAL, false),
                singlePage = prefs.getBoolean(KEY_SINGLE, false),
            )
        }
    }

    fun save(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_HORIZONTAL, horizontal)
            putBoolean(KEY_SINGLE, singlePage)
        }
    }
}
