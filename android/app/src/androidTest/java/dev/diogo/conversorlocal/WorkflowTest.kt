package dev.diogo.conversorlocal

import android.app.Application
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.content.ComponentName
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.provider.DocumentsContract as DC
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import androidx.test.uiautomator.UiScrollable
import androidx.test.uiautomator.UiSelector
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.StaleObjectException
import dev.diogo.conversorlocal.conversion.*
import dev.diogo.conversorlocal.files.Documents
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class WorkflowTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context = inst.targetContext
    private val authority = "dev.diogo.conversorlocal.tests.documents"
    private val tree = DC.buildTreeDocumentUri(authority, "root")
    private val documents = Documents(context)
    private fun companion(uri: Uri, flags: Int, asset: String? = null, name: String? = null) {
        val done = CountDownLatch(1)
        var failure: String? = null
        val reply = object : ResultReceiver(Handler(Looper.getMainLooper())) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) { if (resultCode != 1) failure = resultData?.getString("error"); done.countDown() }
        }
        context.startActivity(Intent().setComponent(ComponentName(inst.context.packageName, TestGrantActivity::class.java.name))
            .putExtra("reply", reply).putExtra("uri", uri.toString()).putExtra("flags", flags)
            .putExtra("asset", asset).putExtra("name", name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK))
        assertTrue("Test companion must complete", done.await(10, TimeUnit.SECONDS))
        assertNull(failure, failure)
    }
    private fun fixture(asset: String, name: String): Uri {
        val uri = DC.buildDocumentUri(authority, "item:$name")
        companion(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION, asset, name)
        return uri
    }
    private fun grant(uri: Uri, write: Boolean = true) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION or if (write) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0
        companion(uri, flags)
        documents.persist(uri, flags)
    }
    private suspend fun waitUntil(condition: () -> Boolean) = withTimeout(30_000) { while (!condition()) delay(25) }

    @Test fun filePermissionIsNotFolderPermissionAndShareNeverInfersParent() {
        context.contentResolver.persistedUriPermissions.forEach { context.contentResolver.releasePersistableUriPermission(it.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        val source = fixture("pesquisa.pdf", "Pesquisa com ação.pdf")
        grant(source, false)
        val input = documents.describe(source, false)
        assertNull(documents.originalParent(input))
        grant(tree)
        assertNotNull(documents.originalParent(input))
        assertNull(documents.originalParent(input.copy(shared = true)))
        val readonly = DC.buildTreeDocumentUri(authority, "readonly")
        grant(readonly, false)
        assertThrows(IllegalArgumentException::class.java) { documents.folder(readonly) }
    }

    @Test fun safePublicationAndCollisionPreserveOriginal(): Unit = runBlocking {
        grant(tree)
        val source = fixture("pesquisa.pdf", "Artigo com ação.pdf")
        val before = context.contentResolver.openInputStream(source)!!.use { it.readBytes() }
        val existing = fixture("existing.txt", "Artigo com ação.docx")
        val input = documents.describe(source, false)
        val private = File(context.cacheDir, "jobs/publication").apply { mkdirs() }
        val snapshot = File(private, "input.pdf"); val output = File(private, "output.docx")
        documents.snapshot(input, snapshot)
        withContext(Dispatchers.Main) { WorkerClient(context).convert(snapshot, output, Conversion.PDF_DOCX) { _, _ -> } }
        Validation.output(output, Conversion.PDF_DOCX)
        val saved = documents.publish(output, input, Conversion.PDF_DOCX, documents.folder(tree))
        assertTrue(saved.name.matches(Regex("Artigo com ação \\([0-9]+\\).docx")))
        assertArrayEquals(output.readBytes(), context.contentResolver.openInputStream(saved.uri)!!.use { it.readBytes() })
        assertArrayEquals(before, context.contentResolver.openInputStream(source)!!.use { it.readBytes() })
        assertEquals("RESULTADO EXISTENTE", context.contentResolver.openInputStream(existing)!!.reader().use { it.readText().trimEnd() })
        private.deleteRecursively()
    }

    @Test fun workerAndBatchContinueAfterCorruptedFile() = runBlocking {
        grant(tree)
        val good = fixture("pesquisa.pdf", "Lote válido.pdf")
        val bad = fixture("corrompido.pdf", "Lote inválido.pdf")
        lateinit var model: MainViewModel
        withContext(Dispatchers.Main) { model = MainViewModel(context.applicationContext as Application); model.select(listOf(bad, good), false, 0) }
        waitUntil { !model.state.value.busy && model.state.value.inputs.size == 2 }
        withContext(Dispatchers.Main) { model.folder(tree, 0) }
        waitUntil { model.state.value.folder != null }
        withContext(Dispatchers.Main) { model.target("docx"); model.convert() }
        waitUntil { !model.state.value.busy }
        assertEquals(model.state.value.toString(), 2, model.state.value.results.size)
        assertNotNull(model.state.value.results[0].error)
        assertNotNull(model.state.value.results[1].saved)
        assertTrue(File(context.cacheDir, "jobs").listFiles().orEmpty().isEmpty())
    }

    @Test fun scannedAndProtectedPdfGiveExplicitErrors() {
        for (name in listOf("digitalizado.pdf", "digitalizado-numero.pdf", "protegido.pdf")) {
            val source = File(context.cacheDir, name)
            inst.context.assets.open(name).use { input -> source.outputStream().use(input::copyTo) }
            val error = assertThrows(IllegalArgumentException::class.java) { PdfEngine.toDocx(source, File(context.cacheDir, "$name.docx")) }
            assertTrue(error.message.orEmpty(), error.message!!.contains(if (name.startsWith("digitalizado")) "OCR" else "senha"))
        }
    }

    @Test fun cancellationKillsWorkerAndLeavesNoPublishedResult() = runBlocking {
        val dir = File(context.cacheDir, "jobs/cancel-${System.nanoTime()}").apply { mkdirs() }
        val source = File(dir, "input.pdf")
        inst.context.assets.open("digitalizado.pdf").use { input -> source.outputStream().use(input::copyTo) }
        val task = launch(Dispatchers.Main) {
            WorkerClient(context).convert(source, File(dir, "output.pptx"), Conversion.PDF_PPTX) { _, _ -> }
        }
        delay(150)
        task.cancelAndJoin()
        assertTrue(task.isCancelled)
        dir.deleteRecursively()
        delay(150)
        assertFalse(dir.exists())
    }

    @Test fun shareSelectionSurvivesRotationAndNoInternetPermission() {
        val source = fixture("pesquisa.pdf", "Compartilhado com ação.pdf")
        grant(source, false)
        val intent = Intent(context, MainActivity::class.java).setAction(Intent.ACTION_SEND)
            .setType("application/pdf").putExtra(Intent.EXTRA_STREAM, source)
            .apply { clipData = ClipData.newRawUri("documento", source) }.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            Thread.sleep(500)
            scenario.recreate()
            Thread.sleep(500)
            scenario.onActivity { activity ->
                val provider = androidx.lifecycle.ViewModelProvider(activity)
                val model = provider[MainViewModel::class.java]
                assertEquals(1, model.state.value.inputs.size)
                assertTrue(model.state.value.inputs.single().shared)
            }
        }
        assertEquals(PackageManager.PERMISSION_DENIED, context.packageManager.checkPermission("android.permission.INTERNET", context.packageName))
        assertEquals(PackageManager.PERMISSION_DENIED, context.packageManager.checkPermission("android.permission.MANAGE_EXTERNAL_STORAGE", context.packageName))
    }

    @Test fun realSystemPickerFolderGrantAndConversion() {
        fixture("pesquisa.pdf", "Escolha ação.pdf")
        val device = UiDevice.getInstance(inst)
        device.wakeUp()
        fun click(selector: BySelector) {
            val end = System.currentTimeMillis() + 10000
            while (System.currentTimeMillis() < end) {
                try {
                    device.waitForIdle(1000)
                    val node = device.wait(Until.findObject(selector), 1000) ?: continue
                    node.click(); device.waitForIdle(); Thread.sleep(300); return
                } catch (_: StaleObjectException) { /* Query a fresh node after the provider refreshes. */ }
            }
            val diagnostics = File(context.cacheDir, "qa").apply { mkdirs() }
            device.dumpWindowHierarchy(File(diagnostics, "picker-failure.xml"))
            device.takeScreenshot(File(diagnostics, "picker-failure.png"))
            throw AssertionError("UI control unavailable: $selector")
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            click(By.text("Selecionar arquivos"))
            // Wait for DocumentsUI's asynchronous last-location restore before
            // selecting another root; otherwise it can restore Downloads over it.
            device.wait(Until.hasObject(By.res("com.android.documentsui", "dir_list")), 10000)
            Thread.sleep(1500)
            click(By.desc("Show roots"))
            Thread.sleep(500)
            // The background "recent apps" row has the same label. Only the
            // drawer's actual root grants DocumentsUI access to this provider.
            click(By.text("Testes Conversor").hasAncestor(By.res("com.android.documentsui", "roots_list")))
            device.waitForIdle()
            UiScrollable(UiSelector().resourceId("com.android.documentsui:id/dir_list")).scrollIntoView(UiSelector().text("Escolha ação.pdf"))
            click(By.text("Escolha ação.pdf"))
            device.wait(Until.hasObject(By.pkg(context.packageName).depth(0)), 10000)
            UiScrollable(UiSelector().scrollable(true)).scrollIntoView(UiSelector().text("Escolher pasta"))
            click(By.text("Escolher pasta"))
            // A fresh tree picker can start in a prior location; open the known test root.
            Thread.sleep(1500)
            click(By.desc("Show roots"))
            Thread.sleep(500)
            click(By.text("Testes Conversor").hasAncestor(By.res("com.android.documentsui", "roots_list")))
            click(By.text(java.util.regex.Pattern.compile("USE THIS FOLDER", java.util.regex.Pattern.CASE_INSENSITIVE)))
            if (device.wait(Until.hasObject(By.res("android", "button1")), 5000)) click(By.res("android", "button1"))
            UiScrollable(UiSelector().scrollable(true)).scrollIntoView(UiSelector().text("Converter"))
            click(By.text("Converter"))
            val limit = System.currentTimeMillis() + 20000
            var completed = false
            while (!completed && System.currentTimeMillis() < limit) {
                scenario.onActivity { activity -> completed = androidx.lifecycle.ViewModelProvider(activity)[MainViewModel::class.java].state.value.results.any { it.saved != null } }
                if (!completed) Thread.sleep(100)
            }
            assertTrue("Picker selection → chosen folder → real conversion → confirmed output", completed)
            val qa = File(context.cacheDir, "qa").apply { mkdirs() }
            UiScrollable(UiSelector().scrollable(true)).scrollToEnd(8)
            device.takeScreenshot(File(qa, "resultado.png"))
            try {
                device.setOrientationLeft()
                Thread.sleep(500)
                device.takeScreenshot(File(qa, "paisagem.png"))
                if (android.os.Build.VERSION.SDK_INT >= 31) {
                    context.getSystemService(android.app.UiModeManager::class.java).setApplicationNightMode(android.app.UiModeManager.MODE_NIGHT_YES)
                    Thread.sleep(500)
                    device.takeScreenshot(File(qa, "escuro.png"))
                    context.getSystemService(android.app.UiModeManager::class.java).setApplicationNightMode(android.app.UiModeManager.MODE_NIGHT_AUTO)
                }
            } finally { device.setOrientationNatural(); device.unfreezeRotation() }
            scenario.recreate()
            scenario.onActivity { activity -> assertEquals(1, androidx.lifecycle.ViewModelProvider(activity)[MainViewModel::class.java].state.value.results.size) }
        }
    }

    @Test fun shareMultipleBatchSurvivesRotationWhileConverting(): Unit = runBlocking {
        grant(tree)
        val a = fixture("pesquisa.pdf", "Compartilhar A.pdf")
        val b = fixture("pesquisa.pdf", "Compartilhar B.pdf")
        val intent = Intent(context, MainActivity::class.java).setAction(Intent.ACTION_SEND_MULTIPLE).setType("application/pdf")
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(a, b)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .apply { clipData = ClipData.newRawUri("A", a).also { it.addItem(ClipData.Item(b)) } }
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            lateinit var model: MainViewModel
            scenario.onActivity { model = androidx.lifecycle.ViewModelProvider(it)[MainViewModel::class.java] }
            waitUntil { !model.state.value.busy && model.state.value.inputs.size == 2 }
            assertTrue(model.state.value.inputs.all { it.shared })
            withContext(Dispatchers.Main) { model.folder(tree, 0) }
            waitUntil { model.state.value.folder != null }
            withContext(Dispatchers.Main) { model.target("pptx"); model.convert() }
            scenario.recreate()
            waitUntil { !model.state.value.busy }
            assertEquals(model.state.value.toString(), 2, model.state.value.results.count { it.saved != null })
            scenario.onActivity { assertSame(model, androidx.lifecycle.ViewModelProvider(it)[MainViewModel::class.java]) }
        }
    }
}
