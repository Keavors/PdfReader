package com.example.pdfreader

import android.content.Context
import androidx.core.net.toUri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.io.File

/** Перенос документов, доступ к которым выдан на один сеанс. */
@RunWith(RobolectricTestRunner::class)
class ImportedDocumentsTest {

    private lateinit var context: Context
    private lateinit var imported: ImportedDocuments

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        File(context.filesDir, "imported").deleteRecursively()
        imported = ImportedDocuments(context)
    }

    /** Подкладывает содержимое так, будто его отдал чужой провайдер. */
    private fun provide(uri: String, content: ByteArray): android.net.Uri {
        val source = uri.toUri()
        shadowOf(context.contentResolver).registerInputStream(source, content.inputStream())
        return source
    }

    @Test
    fun `копия появляется под настоящим именем файла`() {
        val content = "%PDF-1.7 книга".toByteArray()
        val copy = imported.copy(provide("content://wa/1", content), "Договор.pdf", content.size.toLong())

        assertNotNull(copy)
        val file = File(copy!!.path!!)
        assertEquals("Договор.pdf", file.name)
        assertArrayEquals(content, file.readBytes())
    }

    @Test
    fun `копия узнаётся как своя, а чужая ссылка - нет`() {
        val content = "данные".toByteArray()
        val copy = imported.copy(provide("content://wa/1", content), "Файл.pdf", content.size.toLong())

        assertTrue(imported.isImported(copy.toString()))
        assertFalse(imported.isImported("content://wa/1"))
    }

    @Test
    fun `тот же документ второй раз не копируется заново`() {
        val content = "данные".toByteArray()
        val size = content.size.toLong()
        val first = imported.copy(provide("content://wa/1", content), "Файл.pdf", size)
        // Второй раз содержимое не подкладываем: если копия нужна, будет null.
        val second = imported.copy("content://wa/2".toUri(), "Файл.pdf", size)

        assertEquals(first, second)
    }

    @Test
    fun `слишком большой файл не переносим`() {
        val huge = 512L * 1024 * 1024
        assertNull(imported.copy("content://wa/1".toUri(), "Огромный.pdf", huge))
    }

    @Test
    fun `нечитаемый источник не оставляет обрубка`() {
        assertNull(imported.copy("content://wa/нет".toUri(), "Файл.pdf", 10))
        assertTrue(File(context.filesDir, "imported").listFiles().isNullOrEmpty())
    }

    @Test
    fun `забытая копия исчезает с диска`() {
        val content = "данные".toByteArray()
        val copy = imported.copy(provide("content://wa/1", content), "Файл.pdf", content.size.toLong())!!

        imported.forget(copy.toString())

        assertFalse(File(copy.path!!).exists())
    }

    @Test
    fun `чужую ссылку forget не трогает`() {
        val content = "данные".toByteArray()
        val copy = imported.copy(provide("content://wa/1", content), "Файл.pdf", content.size.toLong())!!

        imported.forget("content://wa/1")

        assertTrue(File(copy.path!!).exists())
    }

    @Test
    fun `сироты выносятся, нужные копии остаются`() {
        val keepContent = "нужное".toByteArray()
        val dropContent = "лишнее".toByteArray()
        val keep = imported.copy(
            provide("content://wa/1", keepContent),
            "Нужный.pdf",
            keepContent.size.toLong(),
        )!!
        val drop = imported.copy(
            provide("content://wa/2", dropContent),
            "Лишний.pdf",
            dropContent.size.toLong(),
        )!!

        imported.deleteOrphans(listOf(keep.toString()))

        assertTrue(File(keep.path!!).exists())
        assertFalse(File(drop.path!!).exists())
    }

    @Test
    fun `имя с разделителями пути не уводит копию из своей папки`() {
        val content = "данные".toByteArray()
        val copy = imported.copy(
            provide("content://wa/1", content),
            "../../побег.pdf",
            content.size.toLong(),
        )!!

        val root = File(context.filesDir, "imported").absolutePath
        assertTrue(File(copy.path!!).absolutePath.startsWith(root))
    }

    private fun assertArrayEquals(expected: ByteArray, actual: ByteArray) =
        assertEquals(expected.toList(), actual.toList())
}
