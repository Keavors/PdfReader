package com.example.pdfreader

import android.content.Context
import androidx.core.net.toUri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** Хранилище списка недавних поверх настоящих SharedPreferences. */
@RunWith(RobolectricTestRunner::class)
class RecentFilesStoreTest {

    private lateinit var context: Context
    private lateinit var store: RecentFilesStore

    private fun uri(name: String) = "content://test/$name".toUri()

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        store = RecentFilesStore(context)
    }

    @Test
    fun `новое хранилище пусто`() {
        assertTrue(store.load().isEmpty())
    }

    @Test
    fun `файл сохраняется и читается обратно`() {
        store.add(uri("a"), "Книга.pdf", page = 7)
        assertEquals(listOf(RecentFile("content://test/a", "Книга.pdf", 7)), store.load())
    }

    @Test
    fun `повторное открытие не плодит записи`() {
        store.add(uri("a"), "Книга.pdf", page = 0)
        store.add(uri("b"), "Отчёт.pdf", page = 0)
        store.add(uri("a"), "Книга.pdf", page = 3)

        val loaded = store.load()
        assertEquals(2, loaded.size)
        assertEquals("content://test/a", loaded.first().uri)
        assertEquals(3, loaded.first().page)
    }

    @Test
    fun `страница чтения запоминается и достаётся по ссылке`() {
        store.add(uri("a"), "Книга.pdf", page = 0)
        store.updatePage(uri("a"), 42)
        assertEquals(42, store.pageOf(uri("a")))
    }

    @Test
    fun `незнакомому файлу страницу не заводим`() {
        store.updatePage(uri("нет такого"), 42)
        assertTrue(store.load().isEmpty())
        assertEquals(0, store.pageOf(uri("нет такого")))
    }

    @Test
    fun `удаление убирает только свой файл`() {
        store.add(uri("a"), "A", page = 0)
        store.add(uri("b"), "B", page = 0)
        store.remove(uri("a"))
        assertEquals(listOf("content://test/b"), store.load().map { it.uri })
    }

    @Test
    fun `очистка опустошает список`() {
        store.add(uri("a"), "A", page = 0)
        store.add(uri("b"), "B", page = 0)
        store.clear()
        assertTrue(store.load().isEmpty())
    }

    @Test
    fun `список не растёт дальше лимита`() {
        repeat(RECENT_FILES_LIMIT + 5) { store.add(uri("file$it"), "F$it", page = 0) }
        assertEquals(RECENT_FILES_LIMIT, store.load().size)
    }

    @Test
    fun `другое хранилище видит те же записи`() {
        store.add(uri("a"), "Книга.pdf", page = 5)
        assertEquals(store.load(), RecentFilesStore(context).load())
    }
}
