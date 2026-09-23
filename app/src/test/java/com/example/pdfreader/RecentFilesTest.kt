package com.example.pdfreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

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

    @Test
    fun `запись с JSON-null не превращается в файл с именем null`() {
        assertTrue(parseRecentFiles("""[{"uri":null,"name":null}]""").isEmpty())
        assertTrue(parseRecentFiles("""[{"uri":"a"}]""").isEmpty())
        assertTrue(parseRecentFiles("""[{"uri":"","name":"A"}]""").isEmpty())
    }

    @Test
    fun `страница, размер и время открытия переживают запись и чтение`() {
        val files = listOf(
            RecentFile("content://x/1", "Книга.pdf", page = 42, size = 1024, openedAt = 1700000),
        )
        assertEquals(files, parseRecentFiles(encodeRecentFiles(files)))
    }

    @Test
    fun `у старой записи без новых полей разумные значения по умолчанию`() {
        val parsed = parseRecentFiles("""[{"uri":"a","name":"A"}]""").single()
        assertEquals(0, parsed.page)
        assertEquals(0L, parsed.size)
        assertEquals(0L, parsed.openedAt)
    }

    @Test
    fun `отрицательные значения не просачиваются в список`() {
        val json = """[{"uri":"a","name":"A","page":-7,"size":-1,"openedAt":-5}]"""
        val parsed = parseRecentFiles(json).single()
        assertEquals(0, parsed.page)
        assertEquals(0L, parsed.size)
        assertEquals(0L, parsed.openedAt)
    }

    @Test
    fun `тот же файл под новой ссылкой не создаёт вторую запись`() {
        val old = RecentFile("content://whatsapp/1", "Договор.pdf", page = 5, size = 90_000)
        val new = RecentFile("content://whatsapp/2", "Договор.pdf", page = 0, size = 90_000)

        val result = withFileOnTop(listOf(old), new)

        assertEquals(listOf(new), result)
    }

    @Test
    fun `одинаковые имена с разным размером остаются разными файлами`() {
        val first = RecentFile("content://x/1", "Отчёт.pdf", size = 100)
        val second = RecentFile("content://x/2", "Отчёт.pdf", size = 200)

        assertEquals(listOf(second, first), withFileOnTop(listOf(first), second))
    }

    @Test
    fun `без известного размера файлы различаются только по ссылке`() {
        val first = RecentFile("content://x/1", "Отчёт.pdf", size = 0)
        val second = RecentFile("content://x/2", "Отчёт.pdf", size = 0)

        assertEquals(listOf(second, first), withFileOnTop(listOf(first), second))
    }

    @Test
    fun `время открытия раскладывается на сегодня, вчера и раньше`() {
        val zone = ZoneId.of("Europe/Moscow")
        val now = moment(2026, 9, 23, 10, 0, zone)

        assertEquals(OpenedAtBucket.UNKNOWN, openedAtBucket(0, now, zone))
        assertEquals(
            OpenedAtBucket.TODAY,
            openedAtBucket(moment(2026, 9, 23, 0, 1, zone), now, zone),
        )
        assertEquals(
            OpenedAtBucket.YESTERDAY,
            openedAtBucket(moment(2026, 9, 22, 23, 59, zone), now, zone),
        )
        assertEquals(
            OpenedAtBucket.EARLIER,
            openedAtBucket(moment(2026, 9, 21, 23, 59, zone), now, zone),
        )
    }

    @Test
    fun `сбитые вперёд часы не дают открытия из будущего`() {
        val zone = ZoneOffset.UTC
        val now = moment(2026, 9, 23, 10, 0, zone)
        val tomorrow = moment(2026, 9, 24, 10, 0, zone)

        assertEquals(OpenedAtBucket.TODAY, openedAtBucket(tomorrow, now, zone))
    }

    private fun moment(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
        zone: ZoneId,
    ): Long = LocalDateTime.of(year, month, day, hour, minute)
        .atZone(zone)
        .toInstant()
        .toEpochMilli()
}
