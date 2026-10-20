package dev.diogo.officelocal.proof

import android.content.Intent
import android.content.ComponentName
import android.content.ClipData
import android.content.ClipboardManager
import android.view.KeyEvent
import android.net.Uri
import android.app.UiModeManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.os.SystemClock
import android.provider.DocumentsContract
import android.webkit.WebView
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import dev.diogo.officelocal.*
import dev.diogo.conversorlocal.conversion.*
import dev.diogo.conversorlocal.TestGrantActivity
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

@RunWith(AndroidJUnit4::class)
class SuiteEditorTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val target = inst.targetContext
    private val device = UiDevice.getInstance(inst)
    private val store = Sessions(target)
    private val folder = File(target.cacheDir, "upstream-proof").apply { mkdirs() }
    private fun js(editor: EditorActivity, script: String): String {
        val done = CountDownLatch(1); var result = ""
        inst.runOnMainSync { editor.findViewById<WebView>(org.libreoffice.androidlib.R.id.browser).evaluateJavascript(script) { result = it; done.countDown() } }
        if (!done.await(10, TimeUnit.SECONDS)) {
            device.takeScreenshot(File(folder, "javascript-timeout.png"))
            fail("Editor JavaScript did not respond within 10 seconds")
        }
        return result
    }
    private fun waitFor(message: String, seconds: Int = 60, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + seconds * 1000
        while (SystemClock.elapsedRealtime() < end) { if (condition()) return; SystemClock.sleep(250) }
        device.takeScreenshot(File(folder, "failure.png")); fail(message)
    }
    private fun capture(editor: EditorActivity, name: String) {
        val painted = CountDownLatch(1)
        inst.runOnMainSync {
            editor.findViewById<WebView>(org.libreoffice.androidlib.R.id.browser).postVisualStateCallback(1,
                object : WebView.VisualStateCallback() { override fun onComplete(id: Long) { painted.countDown() } })
        }
        assertTrue("WebView paints the current controls", painted.await(15, TimeUnit.SECONDS))
        SystemClock.sleep(500)
        assertTrue("Screenshot saved", device.takeScreenshot(File(folder, name)))
    }
    private fun open(session: EditingSession): EditorActivity {
        val editor = inst.startActivitySync(Intent(target, EditorActivity::class.java)
            .putExtra("session", session.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as EditorActivity
        waitFor("Real editor did not load ready for use", 120) { js(editor, "!!(window.app && app.map && app.map._docLoaded && (${session.extension == "pdf"} || app.map.isEditMode()) && window.RenderManager && Array.from(RenderManager.getTiles().values()).some(t=>t.isReadyToDraw()))") == "true" }
        assertEquals("Bundled editor stylesheet applied", "true", js(editor, "getComputedStyle(document.body).overflow==='hidden' && getComputedStyle(document.getElementById('map')).position==='absolute'"))
        return editor
    }
    private fun paste(editor: EditorActivity, value: String) {
        inst.runOnMainSync { editor.paste("text/plain;charset=utf-8", value.toByteArray()) }
        SystemClock.sleep(500)
    }
    private fun xml(file: File, prefix: String): String = ZipFile(file).use { zip ->
        zip.entries().asSequence().filter { it.name.startsWith(prefix) && it.name.endsWith(".xml") }.joinToString("\n") { zip.getInputStream(it).bufferedReader().use { r -> r.readText() } }
    }
    private fun menu(label: String) {
        device.wait(Until.findObject(By.desc("Ações do Office Local")), 10000).click()
        device.wait(Until.findObject(By.text(label)), 10000).click()
    }
    private fun chooseFolder() {
        waitFor("No export destination or confirmed output", 30) {
            device.hasObject(By.text("Arquivo criado")) || device.hasObject(By.pkg("com.android.documentsui"))
        }
        // A previously authorized source folder is deliberately preferred.
        if (device.hasObject(By.text("Arquivo criado"))) return
        SystemClock.sleep(1500)
        device.wait(Until.findObject(By.desc("Show roots")), 10000).click()
        SystemClock.sleep(400)
        device.wait(Until.findObject(By.text("Testes Conversor").hasAncestor(By.res("com.android.documentsui", "roots_list"))), 10000).click()
        device.wait(Until.findObject(By.text(java.util.regex.Pattern.compile("USE THIS FOLDER", 2))), 10000).click()
        device.wait(Until.findObject(By.res("android", "button1")), 5000)?.click()
    }
    private fun pdfText(file: File): String {
        val pdf = com.artifex.mupdf.fitz.Document.openDocument(file.absolutePath)
        try { return (0 until pdf.countPages()).joinToString("\n") { i ->
            val page = pdf.loadPage(i); val text = page.toStructuredText()
            try { text.asText() } finally { text.destroy(); page.destroy() }
        } } finally { pdf.destroy() }
    }
    private fun grantedFixture(asset: String, extension: String, flags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION): Uri {
        val name = "Editor ${System.nanoTime()}.$extension"
        val uri = DocumentsContract.buildDocumentUri("dev.diogo.officelocal.tests.documents", "item:$name")
        val done = CountDownLatch(1); var failure: String? = null
        val reply = object : ResultReceiver(Handler(Looper.getMainLooper())) {
            override fun onReceiveResult(code: Int, data: Bundle?) { if (code != 1) failure = data?.getString("error"); done.countDown() }
        }
        target.startActivity(Intent().setComponent(ComponentName(inst.context.packageName, TestGrantActivity::class.java.name))
            .putExtra("reply", reply).putExtra("uri", uri.toString()).putExtra("asset", asset).putExtra("name", name)
            .putExtra("flags", flags)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        assertTrue(done.await(10, TimeUnit.SECONDS)); assertNull(failure)
        return uri
    }
    @Test fun realEditorRoundTripAndLatestExport() = runBlocking {
        Configurator.getInstance().waitForIdleTimeout = 1000
        val arguments = InstrumentationRegistry.getArguments()
        val extension = arguments.getString("format", "docx")!!
        val phase = arguments.getString("phase", "edit")!!
        val marker = "OFFICE_LOCAL_${extension.uppercase()} ação"
        val idFile = File(folder, "suite-$extension.id")
        val fixture = when (extension) { "xlsx" -> "calculos.xlsx"; "pptx" -> "seminario.pptx"; "pdf" -> "pesquisa.pdf"; else -> "artigo.docx" }
        val source = File(folder, "suite-source.$extension")
        val session = if (phase == "new" || phase == "keyboard") store.create(extension) else if (phase == "odf") store.read(File(folder, "odf-$extension.id").readText()) else if (phase in setOf("edit", "readonly", "pdf")) {
            inst.context.assets.open(fixture).use { input -> source.outputStream().use(input::copyTo) }
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or if (phase == "readonly") 0 else Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            val uri = grantedFixture(fixture, extension, flags)
            store.import(uri, flags, phase == "readonly").also { if (phase == "edit") idFile.writeText(it.id) }
        } else store.read(idFile.readText())
        if (phase == "reopen") inst.runOnMainSync { target.getSystemService(UiModeManager::class.java).setApplicationNightMode(UiModeManager.MODE_NIGHT_YES) }
        val editor = open(session)
        fun originalXml(): String {
            target.contentResolver.openInputStream(Uri.parse(session.source))!!.use { source.outputStream().use(it::copyTo) }
            return xml(source, "")
        }
        assertEquals("Documents edit immediately; PDF keeps its real capabilities", if (extension == "pdf") "false" else "true", js(editor, "app.map.isEditMode()"))
        assertEquals("No viewing/editing switch or pencil is offered", "true", js(editor,
            "['viewMode','mobile-edit-button','permissionmode-container'].every(id=>{const e=document.getElementById(id);return !e||getComputedStyle(e).display==='none';})"))
        assertEquals("Office Local blue chrome loaded", "true", js(editor,
            "getComputedStyle(document.documentElement).getPropertyValue('--office-chrome-accent').trim() === (document.documentElement.dataset.theme==='dark'?'#a8c8ff':'#185abd')"))
        if (phase == "pdf") {
            capture(editor, "pdf-consultation.png")
            menu("Converter")
            assertTrue("PDF still offers the integrated converter", device.wait(Until.hasObject(By.text("Converter arquivos")), 10000))
            SystemClock.sleep(1000)
            device.takeScreenshot(File(folder, "converter-light.png"))
            inst.runOnMainSync { target.getSystemService(UiModeManager::class.java).setApplicationNightMode(UiModeManager.MODE_NIGHT_YES) }
            SystemClock.sleep(1600)
            device.takeScreenshot(File(folder, "converter-dark.png"))
            inst.runOnMainSync { target.getSystemService(UiModeManager::class.java).setApplicationNightMode(UiModeManager.MODE_NIGHT_NO) }
            return@runBlocking
        }
        if (phase == "readonly") {
            assertFalse("Read-only incoming URI stays read-only", session.writable)
            paste(editor, "COPIA_EDITAVEL ação")
            val copy = File(folder, "read-only-edited.docx")
            OfficeBridge.exportCurrent(copy.toURI().toString(), "docx")
            assertTrue("The private copy is editable without a mode switch", xml(copy, "word/").contains("COPIA_EDITAVEL ação"))
            menu("Salvar")
            assertTrue("Save requests a destination instead of writing to the read-only origin", device.wait(Until.hasObject(By.pkg("com.android.documentsui")), 10000))
            device.pressBack()
            waitFor("Returned from destination picker") { !device.hasObject(By.pkg("com.android.documentsui")) }
            originalXml()
            assertEquals("Editing a read-only copy leaves its origin intact", session.sourceHash, Sessions.hash(source))
            inst.runOnMainSync { editor.onBackPressedDispatcher.onBackPressed() }
            assertTrue("Back offers recovery/exit directly, without switching to viewer", device.wait(Until.hasObject(By.text("Sair do documento?")), 10000))
            capture(editor, "exit-dialog.png")
            device.wait(Until.findObject(By.res("android", "button2")), 5000).click()
            assertEquals("Continue keeps editing enabled", "true", js(editor, "app.map.isEditMode()"))
            capture(editor, "read-only-copy-editable.png")
            return@runBlocking
        }
        if (phase == "resize") {
            paste(editor, " JANELA_REDIMENSIONADA ação")
            val pid = android.os.Process.myPid()
            try {
                device.executeShellCommand("wm size 1200x900")
                SystemClock.sleep(1500)
                assertEquals("true", js(editor, "!!app.map._docLoaded"))
                device.takeScreenshot(File(folder, "document-resized.png"))
            } finally { device.executeShellCommand("wm size 1600x1000") }
            SystemClock.sleep(1200)
            val copy = File(folder, "document-resized.docx")
            OfficeBridge.exportCurrent(copy.toURI().toString(), "docx")
            assertTrue("Pending edit survives a smaller window", xml(copy, "word/").contains("JANELA_REDIMENSIONADA ação"))
            assertEquals(pid, android.os.Process.myPid())
            return@runBlocking
        }
        if (phase == "keyboard") {
            val clipboard = target.getSystemService(ClipboardManager::class.java)
            val typed = "TECLADO ação coração"
            inst.runOnMainSync {
                editor.findViewById<WebView>(org.libreoffice.androidlib.R.id.browser).requestFocus()
                clipboard.setPrimaryClip(ClipData.newPlainText("teste", typed))
            }
            js(editor, "app.map.focus(); true")
            device.pressKeyCode(KeyEvent.KEYCODE_V, KeyEvent.META_CTRL_ON)
            SystemClock.sleep(1000)
            val check = File(folder, "keyboard.docx")
            OfficeBridge.exportCurrent(check.toURI().toString(), "docx")
            assertTrue("Ctrl+V pastes Portuguese text", xml(check, "word/").contains(typed))
            device.pressKeyCode(KeyEvent.KEYCODE_Z, KeyEvent.META_CTRL_ON)
            SystemClock.sleep(700)
            OfficeBridge.exportCurrent(check.toURI().toString(), "docx")
            assertFalse("Ctrl+Z undoes paste", xml(check, "word/").contains(typed))
            device.pressKeyCode(KeyEvent.KEYCODE_V, KeyEvent.META_CTRL_ON)
            SystemClock.sleep(700)
            inst.runOnMainSync { clipboard.setPrimaryClip(ClipData.newPlainText("sentinela", "ANTES_DE_COPIAR")) }
            js(editor, "app.map.sendUnoCommand('.uno:SelectAll'); app.map.focus(); true")
            device.pressKeyCode(KeyEvent.KEYCODE_C, KeyEvent.META_CTRL_ON)
            SystemClock.sleep(700)
            var copied = ""
            inst.runOnMainSync { copied = clipboard.primaryClip?.getItemAt(0)?.coerceToText(target).toString() }
            assertTrue("Ctrl+C copies selected text", copied.contains(typed))
            device.pressKeyCode(KeyEvent.KEYCODE_S, KeyEvent.META_CTRL_ON)
            chooseFolder()
            assertTrue("Ctrl+S publishes a new document", device.wait(Until.hasObject(By.text("Arquivo criado")), 30000))
            device.takeScreenshot(File(folder, "keyboard-saved.png"))
            return@runBlocking
        }
        if (phase == "new" || phase == "odf") {
            val newMarker = "NOVO_${extension.uppercase()} ação"
            if (phase == "new") {
                paste(editor, newMarker)
                menu("Salvar")
                chooseFolder()
                assertTrue(device.wait(Until.hasObject(By.text("Arquivo criado")), 30000))
                device.wait(Until.findObject(By.res("android", "button2")), 5000).click()
                val odf = mapOf("docx" to "odt", "xlsx" to "ods", "pptx" to "odp").getValue(extension)
                val copy = File(folder, "new.$odf")
                OfficeBridge.exportCurrent(copy.toURI().toString(), odf)
                EditorValidation.check(copy, odf)
                val uri = FileProvider.getUriForFile(target, target.packageName + ".fileprovider", copy)
                val next = store.import(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION, true)
                File(folder, "odf-$extension.id").writeText(next.id)
            } else {
                val copy = File(folder, "odf-roundtrip.$extension")
                OfficeBridge.exportCurrent(copy.toURI().toString(), extension)
                assertTrue("New ODF file remains editable after a cold reopen", xml(copy, "").contains(newMarker))
                paste(editor, " ODF_EDITADO")
                OfficeBridge.exportCurrent(copy.toURI().toString(), extension)
                assertTrue(xml(copy, "").contains("ODF_EDITADO"))
            }
            SystemClock.sleep(1000)
            device.takeScreenshot(File(folder, "$extension-$phase.png"))
            return@runBlocking
        }
        if (phase == "edit") {
            when (extension) {
                "docx" -> { js(editor, "app.map.sendUnoCommand('.uno:GoToEndOfDoc'); true"); SystemClock.sleep(300); paste(editor, marker) }
                "xlsx" -> {
                    js(editor, "app.map.sendUnoCommand('.uno:GoToCell',{ToPoint:{type:'string',value:'A1'}}); true")
                    SystemClock.sleep(300); paste(editor, "21")
                    js(editor, "app.map.sendUnoCommand('.uno:GoToCell',{ToPoint:{type:'string',value:'B3'}}); true")
                    SystemClock.sleep(300); paste(editor, marker)
                }
                "pptx" -> paste(editor, marker)
            }
            // Rotation must keep the same activity and live edit, before saving.
            device.setOrientationLeft(); SystemClock.sleep(900)
            device.setOrientationNatural(); device.unfreezeRotation(); SystemClock.sleep(900)
            assertEquals("Activity process stayed alive", android.os.Process.myPid(), java.lang.Integer.parseInt(device.executeShellCommand("pidof ${target.packageName}:editor").trim()))
            menu("Salvar")
            waitFor("Explicit Save did not persist the edit") { runCatching { originalXml().contains(marker) }.getOrDefault(false) }
            assertEquals(Sessions.hash(source), Sessions.hash(store.working(session)))
            // Add a new change and export through the actual menu without saving the original.
            val latest = "LATEST_${extension.uppercase()}"
            if (extension == "xlsx") { js(editor, "app.map.sendUnoCommand('.uno:GoToCell',{ToPoint:{type:'string',value:'B4'}}); true"); SystemClock.sleep(300) }
            paste(editor, latest)
            val before = File(target.cacheDir, "shared").walkTopDown().filter { it.isFile }.map { it.path }.toSet()
            menu("Exportar PDF")
            chooseFolder()
            assertTrue("New file publication confirmed", device.wait(Until.hasObject(By.text("Arquivo criado")), 30000))
            device.takeScreenshot(File(folder, "$extension-export-confirmation.png"))
            device.wait(Until.findObject(By.res("android", "button2")), 5000).click()
            val exported = File(target.cacheDir, "shared").walkTopDown().single { it.extension == "pdf" && it.path !in before }
            assertTrue("PDF must include the newest edit", pdfText(exported).contains(latest))
            assertFalse("Export must not overwrite the original", originalXml().contains(latest))
            exported.copyTo(File(folder, "$extension-latest.pdf"), overwrite = true)
            // Cancellation of another conversion must not kill the editor process.
            val jobs = File(target.cacheDir, "jobs/editor-cancel").apply { mkdirs() }
            val input = File(jobs, "input.pdf")
            inst.context.assets.open("pesquisa.pdf").use { input.outputStream().use(it::copyTo) }
            withContext(Dispatchers.Main) {
                val job = launch { WorkerClient(target).convert(input, File(jobs, "output.pptx"), Conversion.PDF_PPTX) { _, _ -> } }
                delay(120); job.cancelAndJoin()
            }
            assertEquals("true", js(editor, "!!app.map._docLoaded"))
        } else {
            val result = File(folder, "$extension-reopened.$extension")
            OfficeBridge.exportCurrent(result.toURI().toString(), extension)
            assertTrue(xml(result, "").contains(marker))
            assertTrue("Recovered copy includes the latest exported edit", xml(result, "").contains("LATEST_${extension.uppercase()}"))
            if (extension == "xlsx") {
                assertTrue(xml(result, "xl/").contains("SUM(A1:A2)"))
                assertTrue(xml(result, "xl/").contains("Conferência"))
                assertTrue("Recalculated formula survives", xml(result, "xl/worksheets/").contains("<v>29</v>"))
            }
            if (extension == "pptx") ZipFile(result).use { zip -> assertEquals(3, zip.entries().asSequence().count { it.name.matches(Regex("ppt/slides/slide[0-9]+.xml")) }) }
            if (extension == "docx") {
                val text = xml(result, "word/document.xml")
                assertEquals(3, Regex("<w:tbl>").findAll(text).count()); assertEquals(3, Regex("<w:drawing>").findAll(text).count())
            }
        }
        if (phase == "reopen") {
            OfficeBridge.exportCurrent(File(folder, "$extension-cold-dark.pdf").toURI().toString(), "pdf")
            inst.runOnMainSync { target.getSystemService(UiModeManager::class.java).setApplicationNightMode(UiModeManager.MODE_NIGHT_NO) }
            SystemClock.sleep(1800)
            OfficeBridge.exportCurrent(File(folder, "$extension-reopen-light.pdf").toURI().toString(), "pdf")
        }
        if (extension == "docx") js(editor, "app.map.sendUnoCommand('.uno:GoToStartOfDoc'); true")
        SystemClock.sleep(1000); capture(editor, "$extension-$phase-light.png")
        inst.runOnMainSync { target.getSystemService(UiModeManager::class.java).setApplicationNightMode(UiModeManager.MODE_NIGHT_YES) }
        waitFor("Editor controls use the dark palette") { js(editor, "document.documentElement.getAttribute('data-theme')==='dark' && getComputedStyle(document.documentElement).getPropertyValue('--color-main-background').trim()==='#1b2431'") == "true" }
        assertEquals("The page and transparent Calc cells keep their real background", "true", js(editor, "document.documentElement.getAttribute('data-bg-theme')==='light' && getComputedStyle(document.documentElement).getPropertyValue('--color-background-document').trim()==='#fff'"))
        capture(editor, "$extension-$phase-dark.png")
        val darkPdf = File(folder, "$extension-$phase-dark.pdf")
        OfficeBridge.exportCurrent(darkPdf.toURI().toString(), "pdf")
        assertTrue("Theme export retains text", pdfText(darkPdf).replace(Regex("\\s+"), " ").contains(marker))
        inst.runOnMainSync { target.getSystemService(UiModeManager::class.java).setApplicationNightMode(UiModeManager.MODE_NIGHT_NO) }
        // No finish(): closing mobile Collabora deliberately exits :editor. The
        // host test script performs a cold restart between edit and reopen phases.
    }
}
