package dev.diogo.conversorlocal.conversion

import com.artifex.mupdf.fitz.Document
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.StringReader
import java.util.zip.ZipFile

enum class Conversion(val source: String, val target: String, val mime: String) {
    DOCX_PDF("docx", "pdf", "application/pdf"),
    PPTX_PDF("pptx", "pdf", "application/pdf"),
    PDF_DOCX("pdf", "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
    PDF_PPTX("pdf", "pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation");
    companion object {
        fun forPair(source: String, target: String) = entries.singleOrNull { it.source == source && it.target == target }
        fun targets(sources: List<String>): List<String> = listOf("pdf", "docx", "pptx").filter { target ->
            sources.isNotEmpty() && sources.all { forPair(it, target) != null }
        }
    }
}

object Validation {
    const val MAX_INPUT = 100L * 1024 * 1024
    private const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private const val P = "http://schemas.openxmlformats.org/presentationml/2006/main"

    fun xml(text: String, visitor: (XmlPullParser) -> Unit) {
        require(!Regex("<!\\s*(DOCTYPE|ENTITY)", RegexOption.IGNORE_CASE).containsMatchIn(text.replace("\u0000", ""))) {
            "XML com declaraÃ§Ãµes externas nÃ£o Ã© permitido."
        }
        val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
        parser.setInput(StringReader(text))
        while (parser.next() != XmlPullParser.END_DOCUMENT) visitor(parser)
    }

    private fun packageFile(file: File, inspect: (ZipFile) -> Unit) {
        ZipFile(file).use { zip ->
            var total = 0L; var count = 0
            val names = HashSet<String>()
            for (entry in zip.entries()) {
                require(++count <= 10000 && entry.size >= 0 && entry.size <= 128L * 1024 * 1024) { "Pacote Office grande demais." }
                total += entry.size
                require(total <= 512L * 1024 * 1024 && names.add(entry.name)) { "Pacote Office invÃ¡lido ou grande demais." }
                require(!entry.name.startsWith('/') && !entry.name.split('/').contains("..")) { "Caminho invÃ¡lido no pacote Office." }
            }
            require(zip.getEntry("[Content_Types].xml") != null) { "O arquivo nÃ£o Ã© um documento Office vÃ¡lido." }
            xml(zip.text("[Content_Types].xml")) { }
            inspect(zip)
        }
    }

    private fun ZipFile.text(name: String): String {
        val entry = getEntry(name) ?: error("Documento incompleto: $name.")
        require(entry.size <= 8L * 1024 * 1024) { "XML de documento grande demais." }
        val bytes = getInputStream(entry).use { source ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = source.read(buffer)
                if (count < 0) break
                require(out.size() + count <= 8 * 1024 * 1024) { "XML de documento grande demais." }
                out.write(buffer, 0, count)
            }
            out.toByteArray()
        }
        return when {
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> bytes.toString(Charsets.UTF_16LE).removePrefix("\uFEFF")
            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> bytes.toString(Charsets.UTF_16BE).removePrefix("\uFEFF")
            else -> bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
        }
    }

    fun input(file: File, conversion: Conversion) {
        require(file.length() in 1..MAX_INPUT) { "O limite Ã© de 100 MB por arquivo." }
        if (conversion.source == "pdf") {
            val pdf = Document.openDocument(file.absolutePath)
            try {
                require(!pdf.needsPassword()) { "PDF protegido por senha nÃ£o Ã© aceito." }
                require(pdf.hasPermission(Document.PERMISSION_COPY)) { "Este PDF nÃ£o permite copiar conteÃºdo." }
                require(pdf.countPages() in 1..300) { "O limite Ã© de 300 pÃ¡ginas por arquivo." }
            } finally { pdf.destroy() }
            return
        }
        packageFile(file) { zip ->
            for (entry in zip.entries()) {
                val name = entry.name.lowercase()
                require(!name.contains("vbaproject") && !name.contains("activex") && !name.contains("/embeddings/")) {
                    "Documentos com macros ou objetos incorporados ativos nÃ£o sÃ£o aceitos."
                }
                if (name.endsWith(".rels")) xml(zip.text(entry.name)) { node ->
                    if (node.eventType == XmlPullParser.START_TAG && node.name == "Relationship") {
                        val external = node.getAttributeValue(null, "TargetMode").equals("External", true)
                        val type = node.getAttributeValue(null, "Type").orEmpty()
                        val target = node.getAttributeValue(null, "Target").orEmpty()
                        val absolute = Regex("^[a-z][a-z0-9+.-]*:", RegexOption.IGNORE_CASE).containsMatchIn(target) || target.startsWith("//")
                        require(!(external || absolute) || type.endsWith("/hyperlink")) {
                            "O documento depende de recursos externos. Incorpore esses recursos antes de converter offline."
                        }
                    }
                }
                if (name.endsWith(".xml")) {
                    val instructions = StringBuilder()
                    xml(zip.text(entry.name)) { node ->
                        if (node.eventType == XmlPullParser.START_TAG && node.namespace == W) {
                            if (node.name == "instrText") instructions.append(node.nextText())
                            if (node.name == "fldSimple") instructions.append(node.getAttributeValue(W, "instr"))
                        }
                    }
                    require(!Regex("\\b(INCLUDETEXT|INCLUDEPICTURE|DDE|DDEAUTO|DATABASE|LINK)\\b", RegexOption.IGNORE_CASE).containsMatchIn(instructions)) {
                        "O documento contÃ©m campos com recursos externos. Incorpore esses recursos antes de converter offline."
                    }
                }
            }
            val main = if (conversion.source == "docx") "word/document.xml" else "ppt/presentation.xml"
            var valid = false; var slides = 0
            xml(zip.text(main)) {
                if (it.eventType == XmlPullParser.START_TAG) {
                    if (it.name == "document" && it.namespace == W || it.name == "presentation" && it.namespace == P) valid = true
                    if (it.name == "sldId" && it.namespace == P) slides++
                }
            }
            require(valid && (conversion.source != "pptx" || slides in 1..300)) { "Documento invÃ¡lido ou com mais de 300 slides." }
        }
    }

    fun output(file: File, conversion: Conversion) {
        require(file.isFile && file.length() > 0 && file.length() <= 512L * 1024 * 1024) { "A saÃ­da estÃ¡ vazia ou excede 512 MB." }
        if (conversion.target == "pdf") {
            val pdf = Document.openDocument(file.absolutePath)
            try {
                require(!pdf.needsPassword() && pdf.countPages() in 1..300) { "O PDF gerado nÃ£o passou na validaÃ§Ã£o." }
                for (i in 0 until pdf.countPages()) pdf.loadPage(i).destroy()
            } finally { pdf.destroy() }
        } else packageFile(file) { zip ->
            for (entry in zip.entries()) if (entry.name.endsWith(".xml") || entry.name.endsWith(".rels")) xml(zip.text(entry.name)) { }
            val wordIds = HashSet<String>()
            for (entry in zip.entries()) if (entry.name.endsWith(".rels")) {
                val ids = HashSet<String>()
                val base = entry.name.substringBefore("/_rels/", "")
                xml(zip.text(entry.name)) { node ->
                    if (node.eventType == XmlPullParser.START_TAG && node.name == "Relationship") {
                        val id = node.getAttributeValue(null, "Id")
                        require(ids.add(id)) { "A saÃ­da contÃ©m relaÃ§Ãµes duplicadas." }
                        if (entry.name == "word/_rels/document.xml.rels") wordIds.add(id)
                        require(!node.getAttributeValue(null, "TargetMode").equals("External", true)) { "A saÃ­da contÃ©m recursos externos." }
                        val path = java.nio.file.Paths.get(base).resolve(node.getAttributeValue(null, "Target")).normalize().toString().replace('\\', '/')
                        require(!path.startsWith("../") && zip.getEntry(path) != null) { "A saÃ­da contÃ©m uma referÃªncia incompleta: $path." }
                    }
                }
            }
            for (entry in zip.entries()) if (entry.name.startsWith("word/media/") || entry.name.startsWith("ppt/media/page")) {
                require(entry.size > 0) { "O motor gerou uma imagem vazia. Nenhum resultado foi publicado." }
                val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                zip.getInputStream(entry).use { android.graphics.BitmapFactory.decodeStream(it, null, options) }
                require(options.outWidth > 0 && options.outHeight > 0) { "O motor gerou uma imagem incompatÃ­vel com Office. Nenhum resultado foi publicado." }
            }
            var elements = 0
            val path = if (conversion.target == "docx") "word/document.xml" else "ppt/presentation.xml"
            xml(zip.text(path)) {
                if (conversion.target == "docx" && it.eventType == XmlPullParser.START_TAG && it.name == "blip") {
                    val embed = it.getAttributeValue("http://schemas.openxmlformats.org/officeDocument/2006/relationships", "embed")
                    require(embed == null || embed in wordIds) { "A saÃ­da contÃ©m uma imagem sem referÃªncia vÃ¡lida." }
                }
                if (conversion.target == "docx" && it.eventType == XmlPullParser.START_TAG && it.name == "t" && it.namespace == W) {
                    if (it.nextText().any(Char::isLetterOrDigit)) elements++
                } else if (conversion.target == "pptx" && it.eventType == XmlPullParser.START_TAG && it.name == "sldId" && it.namespace == P) elements++
            }
            require(elements > 0) { "A saÃ­da nÃ£o contÃ©m texto editÃ¡vel ou slides vÃ¡lidos." }
            if (conversion.target == "pptx") require(zip.entries().asSequence().count { it.name.matches(Regex("ppt/media/page[0-9]+\\.png")) } == elements) {
                "O nÃºmero de pÃ¡ginas e imagens do PPTX nÃ£o confere."
            }
        }
    }
}
