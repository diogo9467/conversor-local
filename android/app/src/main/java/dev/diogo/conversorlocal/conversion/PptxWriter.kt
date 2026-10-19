package dev.diogo.conversorlocal.conversion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Minimal OOXML packaging. PdfRenderer renders one page at a time; no editor/layout engine. */
object PptxWriter {
    private const val P = "http://schemas.openxmlformats.org/presentationml/2006/main"
    private const val A = "http://schemas.openxmlformats.org/drawingml/2006/main"
    private const val R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val REL = "http://schemas.openxmlformats.org/package/2006/relationships"
    private const val GROUP = """<p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/><a:chOff x="0" y="0"/><a:chExt cx="0" cy="0"/></a:xfrm></p:grpSpPr>"""

    fun convert(context: Context, input: File, output: File, progress: (Int, Int) -> Unit = { _, _ -> }) {
        ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { pdf ->
                val count = pdf.pageCount
                require(count in 1..300) { "O limite é de 300 páginas por arquivo." }
                val first = pdf.openPage(0)
                val sw = 9144000L
                val sh = (sw.toDouble() * first.height / first.width).toLong()
                first.close()
                require(sh in 914400L..51206400L) { "A proporção da página não é suportada pelo PowerPoint." }
                ZipOutputStream(output.outputStream().buffered()).use { zip ->
                    var types = ""
                    ZipInputStream(context.assets.open("empty.pptx")).use { template ->
                        while (true) {
                            val entry = template.nextEntry ?: break
                            when (entry.name) {
                                "[Content_Types].xml" -> types = template.readBytes().toString(Charsets.UTF_8)
                                "ppt/presentation.xml", "ppt/_rels/presentation.xml.rels" -> Unit
                                else -> { zip.putNextEntry(ZipEntry(entry.name)); template.copyTo(zip); zip.closeEntry() }
                            }
                        }
                    }
                    val overrides = StringBuilder()
                    val ids = StringBuilder()
                    val rels = StringBuilder("<Relationships xmlns=\"$REL\"><Relationship Id=\"rId1\" Type=\"$R/slideMaster\" Target=\"slideMasters/slideMaster1.xml\"/>")
                    for (i in 0 until count) {
                        pdf.openPage(i).use { page ->
                            require(page.width > 0 && page.height > 0) { "Página PDF com tamanho inválido." }
                            val scale = min(200.0 / 72, min(4096.0 / maxOf(page.width, page.height), sqrt(8_000_000.0 / page.width / page.height)))
                            val bitmap = Bitmap.createBitmap(maxOf(1, (page.width * scale).roundToInt()), maxOf(1, (page.height * scale).roundToInt()), Bitmap.Config.ARGB_8888)
                            try {
                                bitmap.eraseColor(Color.WHITE)
                                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                zip.putNextEntry(ZipEntry("ppt/media/page${i + 1}.png"))
                                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, zip)) { "Não foi possível renderizar a página." }
                                zip.closeEntry()
                            } finally { bitmap.recycle() }
                            val fit = min(sw.toDouble() / page.width, sh.toDouble() / page.height)
                            val width = (page.width * fit).toLong(); val height = (page.height * fit).toLong()
                            val x = (sw - width) / 2; val y = (sh - height) / 2
                            zip.xml("ppt/slides/slide${i + 1}.xml", """<p:sld xmlns:p="$P" xmlns:a="$A" xmlns:r="$R"><p:cSld><p:spTree>$GROUP<p:pic><p:nvPicPr><p:cNvPr id="2" name="Página ${i + 1}"/><p:cNvPicPr><a:picLocks noChangeAspect="1"/></p:cNvPicPr><p:nvPr/></p:nvPicPr><p:blipFill><a:blip r:embed="rId2"/><a:stretch><a:fillRect/></a:stretch></p:blipFill><p:spPr><a:xfrm><a:off x="$x" y="$y"/><a:ext cx="$width" cy="$height"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></p:spPr></p:pic></p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>""")
                            zip.xml("ppt/slides/_rels/slide${i + 1}.xml.rels", """<Relationships xmlns="$REL"><Relationship Id="rId1" Type="$R/slideLayout" Target="../slideLayouts/slideLayout7.xml"/><Relationship Id="rId2" Type="$R/image" Target="../media/page${i + 1}.png"/></Relationships>""")
                        }
                        ids.append("<p:sldId id=\"${256 + i}\" r:id=\"rId${i + 2}\"/>")
                        rels.append("<Relationship Id=\"rId${i + 2}\" Type=\"$R/slide\" Target=\"slides/slide${i + 1}.xml\"/>")
                        overrides.append("<Override PartName=\"/ppt/slides/slide${i + 1}.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slide+xml\"/>")
                        progress(i + 1, count)
                    }
                    zip.xml("ppt/presentation.xml", """<p:presentation xmlns:p="$P" xmlns:a="$A" xmlns:r="$R"><p:sldMasterIdLst><p:sldMasterId id="2147483648" r:id="rId1"/></p:sldMasterIdLst><p:sldIdLst>$ids</p:sldIdLst><p:sldSz cx="$sw" cy="$sh" type="custom"/><p:notesSz cx="6858000" cy="9144000"/></p:presentation>""")
                    zip.xml("ppt/_rels/presentation.xml.rels", rels.append("</Relationships>").toString())
                    if (!types.contains("Extension=\"png\"")) overrides.append("<Default Extension=\"png\" ContentType=\"image/png\"/>")
                    zip.xml("[Content_Types].xml", types.replace("</Types>", "$overrides</Types>"))
                }
            }
        }
    }

    private fun ZipOutputStream.xml(name: String, body: String) {
        putNextEntry(ZipEntry(name))
        val content = if (body.trimStart().startsWith("<?xml")) body else "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>$body"
        write(content.toByteArray(Charsets.UTF_8))
        closeEntry()
    }
}
