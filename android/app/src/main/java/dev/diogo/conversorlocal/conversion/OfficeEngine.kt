package dev.diogo.conversorlocal.conversion

import android.content.Context
import org.libreoffice.kit.LibreOfficeKit
import org.libreoffice.kit.Office
import org.libreoffice.kit.Document
import java.io.File

/** One native call thread, in the disposable conversion process. */
object OfficeEngine {
    fun convert(context: Context, input: File, output: File) {
        unpack(context, "unpack", File(context.applicationInfo.dataDir))
        val data = File(context.applicationInfo.dataDir)
        File(data, "fontconfig").mkdirs()
        File(data, "etc/fonts/fonts.conf").apply {
            writeText(readText().replace("/data/data/org.documentfoundation.libreoffice", data.absolutePath))
        }
        File(context.applicationInfo.dataDir, "user/registrymodifications.xcu").apply {
            parentFile!!.mkdirs()
            writeText("""<?xml version="1.0" encoding="UTF-8"?>
                <oor:items xmlns:oor="http://openoffice.org/2001/registry">
                <item oor:path="/org.openoffice.Office.Common/Security/Scripting"><prop oor:name="MacroSecurityLevel" oor:op="fuse"><value>3</value></prop><prop oor:name="DisableMacrosExecution" oor:op="fuse"><value>true</value></prop></item>
                <item oor:path="/org.openoffice.Office.Writer/Content/Update"><prop oor:name="Link" oor:op="fuse"><value>0</value></prop></item>
                </oor:items>""".trimIndent())
        }
        LibreOfficeKit.putenv("SAL_LOK_OPTIONS=compact_fonts")
        LibreOfficeKit.init(context)
        val handle = LibreOfficeKit.getLibreOfficeKitHandle()
            ?: error("Não foi possível iniciar o LibreOfficeKit local.")
        val office = Office(handle)
        office.setOptionalFeatures(Document.LOK_FEATURE_DOCUMENT_PASSWORD)
        office.setMessageCallback { signal, url ->
            if (signal == Document.CALLBACK_DOCUMENT_PASSWORD) office.setDocumentPassword(url, null)
        }
        val document = office.documentLoad(input.absolutePath)
            ?: error("O documento está protegido, corrompido ou não pôde ser aberto: ${office.error}")
        try {
            document.saveAs(output.absolutePath, "pdf", """{"ExportHiddenSlides":{"type":"boolean","value":"true"}}""")
            check(output.isFile && output.length() > 0) { "O motor não gerou o documento: ${office.error}" }
        } finally {
            document.destroy()
        }
    }

    private fun unpack(context: Context, asset: String, dest: File) {
        val children = context.assets.list(asset).orEmpty()
        if (children.isNotEmpty()) {
            check(dest.isDirectory || dest.mkdirs()) { "Sem espaço para preparar o motor local." }
            children.forEach { unpack(context, "$asset/$it", File(dest, it)) }
        } else {
            dest.parentFile!!.mkdirs()
            context.assets.open(asset).use { source -> dest.outputStream().use(source::copyTo) }
        }
    }
}
