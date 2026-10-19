// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.diogo.officelocal.proof;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.SystemClock;
import android.webkit.WebView;
import androidx.core.content.FileProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.UiDevice;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.libreoffice.androidlib.LOActivity;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipFile;
import static org.junit.Assert.*;

/** Real upstream editor session: load, edit, save, close, reopen, save again. */
@RunWith(AndroidJUnit4.class)
public class EditorProofTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final Context target = instrumentation.getTargetContext();

    private String js(LOActivity editor, String script) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> value = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            WebView web = editor.findViewById(org.libreoffice.androidlib.R.id.browser);
            if (web == null) { value.set("false"); done.countDown(); return; }
            web.evaluateJavascript(script, result -> { value.set(result); done.countDown(); });
        });
        assertTrue("JavaScript response timed out", done.await(10, TimeUnit.SECONDS));
        return value.get();
    }

    private LOActivity open(File file) throws Exception {
        Uri uri = FileProvider.getUriForFile(target, target.getPackageName() + ".fileprovider", file);
        Intent intent = new Intent(Intent.ACTION_VIEW, uri, target, LOActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION |
                      Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        LOActivity editor = (LOActivity) instrumentation.startActivitySync(intent);
        long deadline = SystemClock.elapsedRealtime() + 120000;
        while (SystemClock.elapsedRealtime() < deadline) {
            try {
                if ("true".equals(js(editor, "!!(window.app && app.map && app.map._docLoaded)")))
                    return editor;
            } catch (RuntimeException ignored) { }
            SystemClock.sleep(500);
        }
        UiDevice.getInstance(instrumentation).takeScreenshot(new File(target.getCacheDir(), "proof-failure.png"));
        fail("Upstream editor did not finish opening the document");
        return editor;
    }

    private String documentXml(File file) throws Exception {
        try (ZipFile zip = new ZipFile(file)) {
            try (InputStream in = zip.getInputStream(zip.getEntry("word/document.xml"))) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
    }

    @Test public void editsSavesAndReopensLocalDocxOffline() throws Exception {
        File folder = new File(target.getCacheDir(), "upstream-proof");
        assertTrue(folder.isDirectory() || folder.mkdirs());
        File source = new File(folder, "artigo.docx");
        try (InputStream in = instrumentation.getContext().getAssets().open("artigo.docx");
             FileOutputStream out = new FileOutputStream(source)) { in.transferTo(out); }
        LOActivity editor = open(source);
        js(editor, "app.map.setPermission('edit'); app.map.sendUnoCommand('.uno:GoToEndOfDoc'); true");
        SystemClock.sleep(800);
        String marker = "OFFICE_LOCAL_PROOF_20261019 ação e edição";
        instrumentation.runOnMainSync(() -> editor.paste("text/plain;charset=utf-8", marker.getBytes(StandardCharsets.UTF_8)));
        SystemClock.sleep(1500);
        File first = new File(folder, "editado.docx");
        instrumentation.runOnMainSync(() -> editor.saveAs(first.toURI().toString(), "docx", null));
        assertTrue("Saved output missing", first.length() > 0);
        String xml = documentXml(first);
        assertTrue("Latest edit is missing", xml.contains("OFFICE_LOCAL_PROOF_20261019"));
        assertTrue("The fixture's table was lost", xml.contains("w:tbl"));
        try (ZipFile zip = new ZipFile(first)) {
            long images = zip.stream().filter(e -> e.getName().startsWith("word/media/") && e.getSize() > 0).count();
            assertTrue("The fixture's images were lost", images >= 3);
        }
        UiDevice.getInstance(instrumentation).takeScreenshot(new File(folder, "editado.png"));
        instrumentation.runOnMainSync(editor::finish);
        instrumentation.waitForIdleSync();
        SystemClock.sleep(1800);
        LOActivity reopened = open(first);
        File second = new File(folder, "reaberto.docx");
        instrumentation.runOnMainSync(() -> reopened.saveAs(second.toURI().toString(), "docx", null));
        assertTrue("Reopened document lost the edit", documentXml(second).contains("OFFICE_LOCAL_PROOF_20261019"));
        UiDevice.getInstance(instrumentation).takeScreenshot(new File(folder, "reaberto.png"));
        instrumentation.runOnMainSync(reopened::finish);
    }
}
