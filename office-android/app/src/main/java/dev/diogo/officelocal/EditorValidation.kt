// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.diogo.officelocal

import dev.diogo.conversorlocal.conversion.Conversion
import dev.diogo.conversorlocal.conversion.Validation
import java.io.File
import java.util.zip.ZipFile
import org.xmlpull.v1.XmlPullParser

object EditorValidation {
    fun check(file: File, extension: String) {
        require(file.length() in 1..Validation.MAX_INPUT) { "Documento vazio ou maior que 100 MB." }
        when (extension) {
            "docx" -> { Validation.input(file, Conversion.DOCX_PDF); return }
            "pptx" -> { Validation.input(file, Conversion.PPTX_PDF); return }
            "pdf" -> { Validation.input(file, Conversion.PDF_DOCX); return }
        }
        require(extension in setOf("xlsx", "odt", "ods", "odp")) { "Formato não suportado." }
        ZipFile(file).use { zip ->
            val entries = zip.entries().asSequence().toList()
            require(entries.size <= 10000 && entries.sumOf { it.size.coerceAtLeast(0) } <= 512L * 1024 * 1024) { "Pacote grande demais." }
            val names = HashSet<String>()
            for (entry in entries) {
                val name = entry.name.lowercase()
                require(names.add(entry.name) && entry.size in 0..(128L * 1024 * 1024) && !name.startsWith('/') && ".." !in name.split('/')) { "Pacote inválido." }
                require(listOf("vbaproject", "activex", "embeddings/", "scripts/", "basic/", "externallinks/", "connections.xml", "querytables/", "objectreplacements/").none { name.contains(it) }) { "Macros, objetos ativos ou conexões externas não são aceitos." }
                if (name.endsWith(".xml") || name.endsWith(".rels")) {
                    require(entry.size <= 8 * 1024 * 1024) { "XML grande demais." }
                    val bytes = zip.getInputStream(entry).use { bounded(it, 8 * 1024 * 1024) }
                    require(bytes.size <= 8 * 1024 * 1024)
                    val xml = when {
                        bytes.size > 1 && bytes[0] == (-1).toByte() && bytes[1] == (-2).toByte() -> bytes.toString(Charsets.UTF_16LE)
                        bytes.size > 1 && bytes[0] == (-2).toByte() && bytes[1] == (-1).toByte() -> bytes.toString(Charsets.UTF_16BE)
                        else -> bytes.toString(Charsets.UTF_8)
                    }.removePrefix("\uFEFF")
                    Validation.xml(xml) { node ->
                        if (node.eventType == XmlPullParser.START_TAG) {
                            require(node.name !in setOf("script", "event-listener", "dde-link", "ddeLink", "externalLink", "object", "object-ole", "encrypted-key", "encryption-data")) { "Documento com conteúdo ativo, externo ou criptografado." }
                            if (node.name == "Relationship") {
                                val target = node.getAttributeValue(null, "Target").orEmpty()
                                val type = node.getAttributeValue(null, "Type").orEmpty()
                                val external = node.getAttributeValue(null, "TargetMode").equals("External", true) || target.matches(Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:.*")) || target.startsWith("//")
                                require(!external || type.endsWith("/hyperlink")) { "Recursos externos precisam estar incorporados." }
                            }
                            val href = node.getAttributeValue("http://www.w3.org/1999/xlink", "href")
                            if (href != null && node.name != "a") require(!href.contains(':') && !href.startsWith('/') && ".." !in href.split('/')) { "Recursos externos precisam estar incorporados." }
                            val formula = node.getAttributeValue("urn:oasis:names:tc:opendocument:xmlns:table:1.0", "formula")
                            if (formula != null) checkFormula(formula)
                            if (node.name == "f") checkFormula(node.nextText())
                        }
                    }
                }
            }
            if (extension == "xlsx") require(zip.getEntry("xl/workbook.xml") != null && zip.getEntry("[Content_Types].xml") != null) { "Planilha incompleta." }
            else {
                require(zip.getEntry("content.xml") != null && zip.getEntry("mimetype") != null) { "Documento ODF incompleto." }
                val mime = zip.getInputStream(zip.getEntry("mimetype")).use { bounded(it, 200).toString(Charsets.UTF_8) }
                require(mime == Sessions.MIME[extension]) { "O conteúdo não corresponde à extensão do documento." }
            }
        }
    }
    private fun checkFormula(formula: String) {
        require(!Regex("(?i)(WEBSERVICE|DDE|RTD|IMPORTXML|IMPORTDATA|\\[[0-9]+\\]|file:|https?:|ftp:)").containsMatchIn(formula)) { "Fórmulas com recursos externos não são aceitas offline." }
    }
    private fun bounded(input: java.io.InputStream, limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer); if (count < 0) break
            require(output.size() + count <= limit) { "Conteúdo descompactado grande demais." }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
}
