package com.example.pdfreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentFilesTest {

    private fun file(uri: String, name: String = uri) = RecentFile(uri, name)

    @Test
    fun `пустой и повреждённый список читаются без падения`() {
        assertTrue(parseRecentFiles(null).isEmpty())
        assertTrue(parseRecentFiles("").isEmpty())
        assertTrue(parseRecentFiles("не json").isEmpty())
    }

    @Test
    fun `одна битая запись не стирает весь список`() {
        val json = """[{"uri":"a","name":"A"},{"broken":true},{"uri":"b","name":"B"}]"""
        assertEquals(listOf(file("a", "A"), file("b", "B")), parseRecentFiles(json))
    }

    @Test
    fun `список переживает запись и чтение`() {
        val files = listOf(file("content://x/1", "Книга.pdf"), file("content://x/2", "Отчёт.pdf"))
        assertEquals(files, parseRecentFiles(encodeRecentFiles(files)))
    }

    @Test
    fun `открытый файл встаёт первым и не дублируется`() {
        val start = listOf(file("a"), file("b"), file("c"))
        assertEquals(
            listOf(file("b"), file("a"), file("c")),
            withFileOnTop(start, file("b")),
        )
        assertEquals(
            listOf(file("d"), file("a"), file("b"), file("c")),
            withFileOnTop(start, file("d")),
        )
    }

    @Test
    fun `список не растёт дальше лимита`() {
        val many = (1..20).map { file("uri$it") }
        val result = withFileOnTop(many, file("новый"), limit = 5)
        assertEquals(5, result.size)
        assertEquals(file("новый"), result.first())
    }

    @Test
    fun `папка файла достаётся из ссылки`() {
        assertEquals(
            "Download",
            folderFromDecodedUri("content://x/document/primary:Download/книга.pdf"),
        )
        assertEquals(
            "Books/2026",
            folderFromDecodedUri("content://x/document/primary:Books/2026/отчёт.pdf"),
        )
    }

    @Test
    fun `непонятная ссылка не даёт мусорной подписи`() {
        assertEquals("", folderFromDecodedUri("content://media/document/document:34"))
        assertEquals("", folderFromDecodedUri("content://x/document/primary:книга.pdf"))
        assertEquals("", folderFromDecodedUri("что-то совсем другое"))
    }

    @Test
    fun `имя файла обновляется, если файл переименовали`() {
        val start = listOf(file("a", "Старое имя"))
        assertEquals(
            listOf(file("a", "Новое имя")),
            withFileOnTop(start, file("a", "Новое имя")),
        )
    }
}
