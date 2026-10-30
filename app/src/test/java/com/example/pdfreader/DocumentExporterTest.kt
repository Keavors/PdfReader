package com.example.pdfreader

import android.content.Context
import androidx.core.net.toUri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/** «Сохранить как…»: копия документа в выбранном месте. */
@RunWith(RobolectricTestRunner::class)
class DocumentExporterTest {

    private lateinit var context: Context
    private lateinit var exporter: DocumentExporter

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        exporter = DocumentExporter(context)
    }

    @Test
    fun `копия ложится в выбранный файл целиком`() {
        val content = "%PDF-1.7 договор".toByteArray()
        val source = "content://wa/1".toUri()
        val target = "content://docs/Документы%2FДоговор.pdf".toUri()
        val written = ByteArrayOutputStream()
        shadowOf(context.contentResolver).registerInputStream(source, content.inputStream())
        shadowOf(context.contentResolver).registerOutputStream(target, written)

        assertTrue(exporter.export(source, target))
        assertEquals(content.toList(), written.toByteArray().toList())
    }

    @Test
    fun `замена файла им же самим не обнуляет его`() {
        // Окно сохранения отдало тот же файл, что открыт: читаем и пишем одно и то же.
        val content = "%PDF-1.7 единственный экземпляр".toByteArray()
        val file = File(context.filesDir, "same.pdf").apply { writeBytes(content) }
        val uri = "content://docs/same.pdf".toUri()
        shadowOf(context.contentResolver).registerInputStreamSupplier(uri) { FileInputStream(file) }
        shadowOf(context.contentResolver).registerOutputStreamSupplier(uri) { FileOutputStream(file) }

        assertTrue(exporter.export(uri, uri))
        assertEquals(content.toList(), file.readBytes().toList())
    }

    @Test
    fun `нечитаемый источник - сохранить не удалось`() {
        val target = "content://docs/Пусто.pdf".toUri()
        shadowOf(context.contentResolver).registerOutputStream(target, ByteArrayOutputStream())

        assertFalse(exporter.export("content://wa/нет".toUri(), target))
    }

    @Test
    fun `временный файл за собой не оставляет`() {
        val content = "данные".toByteArray()
        val source = "content://wa/1".toUri()
        val target = "content://docs/Файл.pdf".toUri()
        shadowOf(context.contentResolver).registerInputStream(source, content.inputStream())
        shadowOf(context.contentResolver).registerOutputStream(target, ByteArrayOutputStream())

        exporter.export(source, target)
        exporter.export("content://wa/нет".toUri(), target)

        assertTrue(context.cacheDir.listFiles().orEmpty().none { it.name.startsWith("export") })
    }
}
