package dev.diogo.officelocal.proof

import android.app.UiModeManager
import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import dev.diogo.officelocal.HomeActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SuiteHomeTest {
    @Test fun homeAdaptsToTabletPortraitAndDarkTheme() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val context = inst.targetContext
        val device = UiDevice.getInstance(inst)
        val mode = context.getSystemService(UiModeManager::class.java)
        val proof = File(context.cacheDir, "upstream-proof").apply { mkdirs() }
        fun home() {
            context.startActivity(Intent(context, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            assertTrue(device.wait(Until.hasObject(By.text("Criar novo")), 10000))
            SystemClock.sleep(1800)
        }
        try {
            device.executeShellCommand("wm size 1600x1000")
            mode.setApplicationNightMode(UiModeManager.MODE_NIGHT_NO)
            home()
            val document = device.findObject(By.text("Documento")).visibleCenter
            val spreadsheet = device.findObject(By.text("Planilha")).visibleCenter
            assertTrue("Wide tablet places creation cards in a row", kotlin.math.abs(document.y - spreadsheet.y) < 8 && document.x < spreadsheet.x)
            device.takeScreenshot(File(proof, "home-wide-light.png"))
            device.executeShellCommand("wm size 1000x1600")
            SystemClock.sleep(1800)
            assertTrue("Portrait places creation cards in a column", device.findObject(By.text("Documento")).visibleCenter.y < device.findObject(By.text("Planilha")).visibleCenter.y)
            assertTrue(device.hasObject(By.text("Abrir arquivo")))
            assertTrue(device.hasObject(By.text("Converter arquivos")))
            device.takeScreenshot(File(proof, "home-portrait-light.png"))
            mode.setApplicationNightMode(UiModeManager.MODE_NIGHT_YES)
            SystemClock.sleep(1800)
            device.takeScreenshot(File(proof, "home-portrait-dark.png"))
            assertTrue(device.hasObject(By.text("Apresentação")))
        } finally {
            device.executeShellCommand("wm size 1600x1000")
            mode.setApplicationNightMode(UiModeManager.MODE_NIGHT_NO)
        }
    }
}
