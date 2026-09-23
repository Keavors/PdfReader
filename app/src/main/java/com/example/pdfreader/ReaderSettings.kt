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
 * @param nightMode инверсия цветов — белый текст на чёрном
 * @param keepScreenOn не гасить экран, пока документ открыт
 * @param lockOrientation не переворачивать экран вслед за телефоном
 */
data class ReaderSettings(
    val horizontal: Boolean = false,
    val singlePage: Boolean = false,
    val nightMode: Boolean = false,
    val keepScreenOn: Boolean = false,
    val lockOrientation: Boolean = false,
) {
    companion object {
        private const val KEY_HORIZONTAL = "swipe_horizontal"
        private const val KEY_SINGLE = "single_page"
        private const val KEY_NIGHT = "night_mode"
        private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        private const val KEY_LOCK_ORIENTATION = "lock_orientation"

        fun load(context: Context): ReaderSettings {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return ReaderSettings(
                horizontal = prefs.getBoolean(KEY_HORIZONTAL, false),
                singlePage = prefs.getBoolean(KEY_SINGLE, false),
                nightMode = prefs.getBoolean(KEY_NIGHT, false),
                keepScreenOn = prefs.getBoolean(KEY_KEEP_SCREEN_ON, false),
                lockOrientation = prefs.getBoolean(KEY_LOCK_ORIENTATION, false),
            )
        }
    }

    fun save(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_HORIZONTAL, horizontal)
            putBoolean(KEY_SINGLE, singlePage)
            putBoolean(KEY_NIGHT, nightMode)
            putBoolean(KEY_KEEP_SCREEN_ON, keepScreenOn)
            putBoolean(KEY_LOCK_ORIENTATION, lockOrientation)
        }
    }
}
