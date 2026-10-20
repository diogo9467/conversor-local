package dev.diogo.officelocal.proof

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.*
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import dev.diogo.conversorlocal.TestGrantActivity
import dev.diogo.officelocal.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import java.util.zip.ZipFile

/** UI crosses main -> :editor -> main -> :editor with a real external SAF URI. */
@RunWith(AndroidJUnit4::class)
class SuiteFlowTest {
    @Test fun pdfConsultationConvertsAndResultOpensInsideSuite() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val context = inst.targetContext
        val device = UiDevice.getInstance(inst)
        Configurator.getInstance().waitForIdleTimeout = 1000
        val proof = File(context.cacheDir, "upstream-proof").apply { mkdirs() }
        fun click(selector: BySelector) {
            repeat(5) {
                try {
                    val item = device.wait(Until.findObject(selector), 10000)
                    if (item != null) { item.click(); return }
                } catch (_: StaleObjectException) { /* SAF redraws while its drawer animates. */ }
                SystemClock.sleep(400)
            }
            device.takeScreenshot(File(proof, "flow-failure.png")); fail("Missing stable action: $selector")
        }
        fun tap(label: String) {
            repeat(7) {
                val item = device.wait(Until.findObject(By.text(label)), 1500)
                if (item != null) { item.click(); return }
                device.swipe(device.displayWidth / 2, device.displayHeight * 4 / 5,
                    device.displayWidth / 2, device.displayHeight / 3, 15)
                SystemClock.sleep(350)
            }
            device.takeScreenshot(File(proof, "flow-failure.png")); fail("Missing action: $label")
        }
        val name = "Consulta ${System.nanoTime()}.pdf"
        val uri = DocumentsContract.buildDocumentUri("dev.diogo.officelocal.tests.documents", "item:$name")
        val ready = CountDownLatch(1); var error: String? = null
        val reply = object : ResultReceiver(Handler(Looper.getMainLooper())) {
            override fun onReceiveResult(code: Int, data: Bundle?) { if (code != 1) error = data?.getString("error"); ready.countDown() }
        }
        context.startActivity(Intent().setComponent(ComponentName(inst.context.packageName, TestGrantActivity::class.java.name))
            .putExtra("reply", reply).putExtra("uri", uri.toString()).putExtra("asset", "pesquisa.pdf").putExtra("name", name)
            .putExtra("flags", Intent.FLAG_GRANT_READ_URI_PERMISSION).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        assertTrue(ready.await(10, TimeUnit.SECONDS)); assertNull(error)
        context.startActivity(Intent(context, HomeActivity::class.java).setAction(Intent.ACTION_SEND)
            .setType("application/pdf").putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
        assertTrue(device.wait(Until.hasObject(By.desc("Ações do Office Local")), 120000))
        SystemClock.sleep(12000)
        device.takeScreenshot(File(proof, "pdf-consultation.png"))
        device.findObject(By.desc("Ações do Office Local")).click(); tap("Converter")
        assertTrue(device.wait(Until.hasObject(By.text("Conversor · Office Local")), 30000))
        tap("DOCX"); tap("Escolher pasta")
        click(By.desc("Show roots"))
        SystemClock.sleep(400)
        click(By.text("Testes Conversor").hasAncestor(By.res("com.android.documentsui", "roots_list")))
        click(By.text(Pattern.compile("USE THIS FOLDER", 2)))
        device.wait(Until.findObject(By.res("android", "button1")), 5000)?.click()
        tap("Converter")
        val deadline = SystemClock.elapsedRealtime() + 90000
        while (!device.hasObject(By.text("Abrir resultado")) && SystemClock.elapsedRealtime() < deadline) {
            device.swipe(device.displayWidth / 2, device.displayHeight * 4 / 5, device.displayWidth / 2, device.displayHeight / 3, 15)
            SystemClock.sleep(500)
        }
        assertTrue("Conversion must publish a DOCX", device.hasObject(By.text("Abrir resultado")))
        device.takeScreenshot(File(proof, "pdf-docx-published.png")); tap("Abrir resultado")
        assertTrue(device.wait(Until.hasObject(By.text("Abrir outro documento?")), 30000))
        val previousPid = device.executeShellCommand("pidof ${context.packageName}:editor").trim()
        device.findObject(By.res("android", "button1")).click()
        val reopenDeadline = SystemClock.elapsedRealtime() + 90000
        var nextPid = previousPid
        while ((nextPid.isBlank() || nextPid == previousPid) && SystemClock.elapsedRealtime() < reopenDeadline) {
            SystemClock.sleep(300)
            nextPid = device.executeShellCommand("pidof ${context.packageName}:editor").trim()
        }
        assertTrue("Converted DOCX starts in a fresh editor process", nextPid.isNotBlank() && nextPid != previousPid)
        assertTrue(device.wait(Until.hasObject(By.desc("Ações do Office Local")), 30000))
        assertTrue(device.wait(Until.hasObject(By.text(Pattern.compile("Consulta .*\\.docx"))), 120000))
        SystemClock.sleep(12000)
        device.takeScreenshot(File(proof, "pdf-docx-internal-editor.png"))
        val result = Sessions(context).recent().first { it.extension == "docx" && it.name.startsWith("Consulta ") }
        ZipFile(Sessions(context).working(result)).use { zip ->
            val text = zip.getInputStream(zip.getEntry("word/document.xml")).bufferedReader().use { it.readText() }
            assertTrue("Converted text remains present", text.contains("MARCADOR_PDF_1"))
        }
        assertTrue(device.hasObject(By.desc("Ações do Office Local")))
    }
}
