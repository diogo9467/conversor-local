package dev.diogo.officelocal.proof

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.*
import android.provider.DocumentsContract as DC
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.diogo.conversorlocal.TestGrantActivity
import dev.diogo.conversorlocal.files.Documents
import dev.diogo.officelocal.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class SuiteFilesTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context = inst.targetContext
    private val store = Sessions(context)
    private val authority = "dev.diogo.officelocal.tests.documents"
    private fun grant(uri: Uri, flags: Int, asset: String? = null, name: String? = null) {
        val done = CountDownLatch(1); var failure: String? = null
        val receiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
            override fun onReceiveResult(code: Int, data: Bundle?) { if (code != 1) failure = data?.getString("error"); done.countDown() }
        }
        context.startActivity(Intent().setComponent(ComponentName(inst.context.packageName, TestGrantActivity::class.java.name))
            .putExtra("reply", receiver).putExtra("uri", uri.toString()).putExtra("flags", flags)
            .putExtra("asset", asset).putExtra("name", name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        assertTrue(done.await(10, TimeUnit.SECONDS)); assertNull(failure)
    }
    private fun source(name: String, writable: Boolean): Uri {
        val uri = DC.buildDocumentUri(authority, "item:$name")
        grant(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or if (writable) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0, "artigo.docx", name)
        return uri
    }
    private fun hash(uri: Uri) = context.contentResolver.openInputStream(uri)!!.use(Sessions::hash)
    @Test fun readOnlySharedFileEditsPrivateCopyAndPublishesNumberedCopies() = runBlocking {
        val uri = source("Read only ${System.nanoTime()}.docx", false)
        val original = hash(uri)
        val session = store.import(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION, true)
        assertFalse(session.writable)
        store.working(session).appendText("private-change")
        assertEquals(original, hash(uri))
        assertTrue(runCatching { store.publishOriginal(session) }.isFailure)
        val tree = DC.buildTreeDocumentUri(authority, "root")
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        grant(tree, flags); Documents(context).persist(tree, flags)
        assertNull("Shared source must not infer parent", Documents(context).originalParent(store.input(session)))
        val first = Documents(context).publishFile(store.working(session), store.input(session), "docx", Sessions.MIME.getValue("docx"), Documents(context).folder(tree))
        val second = Documents(context).publishFile(store.working(session), store.input(session), "docx", Sessions.MIME.getValue("docx"), Documents(context).folder(tree))
        assertNotEquals(first.uri, second.uri); assertTrue(second.name.contains(" (2)."))
        assertEquals(original, hash(uri))
        assertTrue(store.read(session.id).recovery)
        // Simulate converter cleanup; editing recovery must remain untouched.
        File(context.cacheDir, "jobs").deleteRecursively()
        assertEquals(Sessions.hash(store.working(session)), Sessions.hash(store.working(store.read(session.id))))
    }
    @Test fun explicitSaveHasReadbackAndRejectsChangedOriginal() {
        val uri = source("Editable ${System.nanoTime()}.docx", true)
        val session = store.import(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION, false)
        assertTrue(session.writable)
        val working = store.working(session)
        working.appendText("saved-edit")
        store.publishOriginal(session)
        assertEquals(Sessions.hash(working), hash(uri))
        context.contentResolver.openOutputStream(uri, "wa")!!.use { it.write("external-edit".toByteArray()) }
        val externallyChanged = hash(uri)
        working.appendText("another-local-edit")
        assertTrue(runCatching { store.publishOriginal(session) }.exceptionOrNull()?.message?.contains("original mudou") == true)
        assertEquals(externallyChanged, hash(uri))
        assertTrue(File(store.directory(session.id), "original.docx").length() > 0)
    }
    @Test fun spreadsheetAndOdfValidationRejectActiveAndExternalContent() {
        val root = File(context.cacheDir, "validation-proof").apply { mkdirs() }
        val source = File(root, "valid.xlsx")
        inst.context.assets.open("calculos.xlsx").use { source.outputStream().use(it::copyTo) }
        EditorValidation.check(source, "xlsx")
        for ((name, body) in listOf("xl/vbaProject.bin" to "macro", "xl/externalLinks/externalLink1.xml" to "<x/>", "xl/test.xml" to "<!DOCTYPE x SYSTEM 'file:///private'><x/>")) {
            val bad = File(root, "invalid.xlsx")
            ZipOutputStream(bad.outputStream()).use { out ->
                ZipFile(source).use { zip -> for (entry in zip.entries()) {
                    out.putNextEntry(ZipEntry(entry.name)); zip.getInputStream(entry).use { it.copyTo(out) }; out.closeEntry()
                } }
                out.putNextEntry(ZipEntry(name)); out.write(body.toByteArray()); out.closeEntry()
            }
            assertTrue("Reject $name", runCatching { EditorValidation.check(bad, "xlsx") }.isFailure)
        }
        for (extension in listOf("odt", "ods", "odp")) {
            val template = File(root, "valid.$extension")
            context.assets.open("templates/untitled.$extension").use { template.outputStream().use(it::copyTo) }
            EditorValidation.check(template, extension)
        }
    }
}
