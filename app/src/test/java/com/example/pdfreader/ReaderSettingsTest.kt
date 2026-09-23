package com.example.pdfreader

import android.content.Context
import androidx.core.net.toUri
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ReaderSettingsTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun `по умолчанию вертикальная слитная лента без изысков`() {
        assertEquals(ReaderSettings(), ReaderSettings.load(context))
    }

    @Test
    fun `настройки переживают запись и чтение`() {
        val settings = ReaderSettings(
            horizontal = true,
            singlePage = true,
            nightMode = true,
            keepScreenOn = true,
            lockOrientation = true,
        )
        settings.save(context)
        assertEquals(settings, ReaderSettings.load(context))
    }

    @Test
    fun `настройки живут в том же файле, что и список недавних`() {
        ReaderSettings(nightMode = true).save(context)
        RecentFilesStore(context).add(
            "content://test/a".toUri(),
            name = "A",
            page = 1,
            size = 0,
            openedAt = 1,
        )

        assertEquals(true, ReaderSettings.load(context).nightMode)
        assertEquals(1, RecentFilesStore(context).load().single().page)
    }
}
