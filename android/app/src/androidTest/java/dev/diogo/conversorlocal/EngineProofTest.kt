package dev.diogo.conversorlocal

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.diogo.conversorlocal.conversion.PptxWriter
import com.artifex.mupdf.fitz.Document
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipFile
import dev.diogo.conversorlocal.conversion.Conversion
import dev.diogo.conversorlocal.conversion.WorkerClient
import dev.diogo.conversorlocal.conversion.Validation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class EngineProofTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context = inst.targetContext
    private fun fixture(name: String): File = File(context.cacheDir, name).also { dest ->
        inst.context.assets.open(name).use { input -> dest.outputStream().use(input::copyTo) }
    }
    @Test fun fourRealConversions(): Unit = runBlocking {
        for ((name, marker) in listOf("artigo.docx" to "MARCADOR_DOCX_", "seminario.pptx" to "MARCADOR_PPTX_")) {
            val pdf = File(context.cacheDir, "$name.pdf")
            reopen(fixture(name), pdf, Conversion.forPair(name.substringAfterLast('.'), "pdf")!!)
            val doc = Document.openDocument(pdf.absolutePath)
            try {
                assertEquals(name, 3, doc.countPages())
                for (i in 0..2) {
                    val page = doc.loadPage(i); val text = page.toStructuredText()
                    try { assertTrue(text.asText(), text.asText().contains(marker + (i + 1))) }
                    finally { text.destroy(); page.destroy() }
                }
            } finally { doc.destroy() }
        }
        val input = fixture("pesquisa.pdf")
        val docx = File(context.cacheDir, "reconstruido.docx")
        reopen(input, docx, Conversion.PDF_DOCX)
        ZipFile(docx).use { zip ->
            val xml = zip.getInputStream(zip.getEntry("word/document.xml")).reader().readText()
            for (i in 1..3) assertTrue(xml, xml.contains("MARCADOR_PDF_$i"))
            assertTrue("Editable text", xml.contains(":t>"))
            assertTrue("Images", zip.entries().asSequence().any { it.name.startsWith("word/media/") })
            assertEquals("Images from every page", 3, zip.entries().asSequence().count { it.name.startsWith("word/media/") })
            for (image in zip.entries().asSequence().filter { it.name.startsWith("word/media/") }) {
                val bitmap = zip.getInputStream(image).use { android.graphics.BitmapFactory.decodeStream(it) }
                assertNotNull("Image must decode, including repeated PDF images", bitmap)
                assertEquals(700, bitmap.width); assertEquals(350, bitmap.height); bitmap.recycle()
            }
            assertTrue("Tables", xml.contains(":tbl>"))
        }
        val pptx = File(context.cacheDir, "paginas.pptx")
        PptxWriter.convert(context, input, pptx)
        ZipFile(pptx).use { zip ->
            assertEquals(3, zip.entries().asSequence().count { it.name.matches(Regex("ppt/slides/slide[0-9]+.xml")) })
            assertEquals(3, zip.entries().asSequence().count { it.name.startsWith("ppt/media/page") })
        }
    }

    @Test fun fourRoutesInDisposableWorker() = runBlocking {
        val dir = File(context.cacheDir, "jobs/native-proof").apply { mkdirs() }
        try {
            for ((route, asset) in listOf(Conversion.DOCX_PDF to "artigo.docx", Conversion.PPTX_PDF to "seminario.pptx", Conversion.PDF_DOCX to "pesquisa.pdf", Conversion.PDF_PPTX to "pesquisa.pdf")) {
                val source = File(dir, "input.${route.source}")
                inst.context.assets.open(asset).use { input -> source.outputStream().use(input::copyTo) }
                val output = File(dir, "${route.name}.${route.target}")
                withTimeout(30_000) { withContext(Dispatchers.Main) { WorkerClient(context).convert(source, output, route) { _, _ -> } } }
                Validation.output(output, route)
            }
        } finally { dir.deleteRecursively() }
    }

    @Test fun mixedPageSizesFitAndGeneratedOfficeFilesReopen(): Unit = runBlocking {
        val input = fixture("misto.pdf")
        val pptx = File(context.cacheDir, "proporcoes.pptx")
        PptxWriter.convert(context, input, pptx)
        ZipFile(pptx).use { zip ->
            var sw = 0L; var sh = 0L
            Validation.xml(zip.getInputStream(zip.getEntry("ppt/presentation.xml")).reader().readText()) {
                if (it.eventType == org.xmlpull.v1.XmlPullParser.START_TAG && it.name == "sldSz") { sw = it.getAttributeValue(null, "cx").toLong(); sh = it.getAttributeValue(null, "cy").toLong() }
            }
            for (i in 1..3) {
                var x = 0L; var y = 0L; var width = 0L; var height = 0L
                Validation.xml(zip.getInputStream(zip.getEntry("ppt/slides/slide$i.xml")).reader().readText()) {
                    if (it.eventType == org.xmlpull.v1.XmlPullParser.START_TAG) {
                        if (it.name == "off") { x = it.getAttributeValue(null, "x").toLong(); y = it.getAttributeValue(null, "y").toLong() }
                        if (it.name == "ext") { width = it.getAttributeValue(null, "cx").toLong(); height = it.getAttributeValue(null, "cy").toLong() }
                    }
                }
                assertTrue(x >= 0 && y >= 0 && width + x <= sw && height + y <= sh)
                assertEquals(if (i == 2) 2.0 else 2.0 / 3, width.toDouble() / height, 0.0001)
            }
        }
        val reopened = File(context.cacheDir, "pptx-reaberto.pdf")
        reopen(pptx, reopened, Conversion.PPTX_PDF)
        val pdf = Document.openDocument(reopened.absolutePath)
        try { assertEquals(3, pdf.countPages()) } finally { pdf.destroy() }
        val docx = File(context.cacheDir, "docx-editavel.docx")
        reopen(fixture("pesquisa.pdf"), docx, Conversion.PDF_DOCX)
        val docxPdf = File(context.cacheDir, "docx-reaberto.pdf")
        reopen(docx, docxPdf, Conversion.DOCX_PDF)
        val word = Document.openDocument(docxPdf.absolutePath)
        try {
            val all = StringBuilder()
            for (i in 0 until word.countPages()) {
                val page = word.loadPage(i); val text = page.toStructuredText()
                try { all.append(text.asText()) } finally { text.destroy(); page.destroy() }
            }
            assertTrue(all.indexOf("MARCADOR_PDF_1") >= 0 && all.indexOf("MARCADOR_PDF_1") < all.indexOf("MARCADOR_PDF_2") && all.indexOf("MARCADOR_PDF_2") < all.indexOf("MARCADOR_PDF_3"))
        } finally { word.destroy() }
    }

    private suspend fun reopen(source: File, destination: File, conversion: Conversion) {
        // Match production: a fresh private job and disposable process for each native load.
        val dir = File(context.cacheDir, "jobs/reopen-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val input = File(dir, "input.${conversion.source}"); source.copyTo(input)
            val output = File(dir, "output.${conversion.target}")
            withTimeout(30_000) { withContext(Dispatchers.Main) { WorkerClient(context).convert(input, output, conversion) { _, _ -> } } }
            output.copyTo(destination, overwrite = true)
        } finally { dir.deleteRecursively() }
    }
}
