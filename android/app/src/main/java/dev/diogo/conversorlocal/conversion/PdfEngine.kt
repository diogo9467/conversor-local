package dev.diogo.conversorlocal.conversion

import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.DocumentWriter
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.StructuredTextWalker
import com.artifex.mupdf.fitz.Rect
import com.artifex.mupdf.fitz.Point
import com.artifex.mupdf.fitz.Quad
import com.artifex.mupdf.fitz.Font
import com.artifex.mupdf.fitz.Image
import java.io.File

object PdfEngine {
    fun toDocx(input: File, output: File, progress: (Int, Int) -> Unit = { _, _ -> }) {
        val pdf = Document.openDocument(input.absolutePath)
        try {
            require(!pdf.needsPassword()) { "PDF protegido por senha. Não é possível converter." }
            require(pdf.hasPermission(Document.PERMISSION_COPY)) { "Este PDF não permite copiar seu conteúdo." }
            val count = pdf.countPages()
            require(count in 1..300) { "O limite é de 300 páginas por arquivo." }
            var editableCharacters = 0
            for (i in 0 until count) {
                val page = pdf.loadPage(i)
                try {
                    val text = page.toStructuredText()
                    try {
                        val characters = text.asText().count { it.isLetterOrDigit() }
                        editableCharacters += characters
                        // A scanned page may have only a selectable page number or header.
                        require(characters >= 20 || !mostlyRaster(page, characters == 0)) {
                            "A página ${i + 1} é principalmente imagem, com texto selecionável ausente ou insuficiente. OCR não está incluído nesta versão; use um PDF com texto para gerar DOCX editável."
                        }
                    } finally { text.destroy() }
                } finally { page.destroy() }
            }
            require(editableCharacters > 0) { "O PDF não tem texto selecionável. OCR não está incluído nesta versão." }
            val writer = DocumentWriter(output.absolutePath, "docx", "")
            try {
                for (i in 0 until count) {
                    val page = pdf.loadPage(i)
                    try {
                        val device = writer.beginPage(page.bounds)
                        try {
                            page.run(device, Matrix(1f))
                            writer.endPage()
                        } finally { device.destroy() }
                    } finally { page.destroy() }
                    progress(i + 1, count)
                }
                writer.close()
            } finally { writer.destroy() }
        } finally { pdf.destroy() }
    }

    private fun mostlyRaster(page: com.artifex.mupdf.fitz.Page, noText: Boolean): Boolean {
        val bounds = page.bounds
        var area = 0f
        val structure = page.toStructuredText("preserve-images")
        try {
            structure.walk(object : StructuredTextWalker {
                override fun onImageBlock(box: Rect, transform: Matrix, image: Image) {
                    area += maxOf(0f, minOf(box.x1, bounds.x1) - maxOf(box.x0, bounds.x0)) * maxOf(0f, minOf(box.y1, bounds.y1) - maxOf(box.y0, bounds.y0))
                    image.destroy()
                }
                override fun beginTextBlock(box: Rect, flags: Int) = Unit
                override fun endTextBlock() = Unit
                override fun beginLine(box: Rect, wmode: Int, direction: Point) = Unit
                override fun endLine() = Unit
                override fun onChar(code: Int, origin: Point, font: Font, size: Float, quad: Quad, color: Int, bidi: Int, flags: Int) = Unit
                override fun beginStruct(standard: String?, raw: String?, index: Int) = Unit
                override fun endStruct() = Unit
                override fun onVector(box: Rect, info: StructuredTextWalker.VectorInfo, flags: Int) = Unit
            })
        } finally { structure.destroy() }
        return area > 0f && (noText || area > (bounds.x1 - bounds.x0) * (bounds.y1 - bounds.y0) * 0.5f)
    }
}
