// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.diogo.officelocal.proof;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipFile;
import static org.junit.Assert.*;

/** Two cold-start phases: the upstream mobile engine intentionally exits on close. */
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

    private void awaitPaint(LOActivity editor, File folder, String phase) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 90000;
        String state = "";
        while (SystemClock.elapsedRealtime() < deadline) {
            state = js(editor, "JSON.stringify({tiles: window.RenderManager ? RenderManager.getTiles().size : 0, ready: window.RenderManager ? Array.from(RenderManager.getTiles().values()).filter(t => t.isReadyToDraw()).length : 0})");
            if ("true".equals(js(editor, "!!(window.RenderManager && RenderManager.getTiles().size > 0 && Array.from(RenderManager.getTiles().values()).some(t => t.isReadyToDraw()))"))) {
                SystemClock.sleep(1500);
                return;
            }
            SystemClock.sleep(500);
        }
        try (FileOutputStream out = new FileOutputStream(new File(folder, phase + "-render-state.txt"))) {
            out.write(state.getBytes(StandardCharsets.UTF_8));
        }
        UiDevice.getInstance(instrumentation).takeScreenshot(new File(folder, phase + "-render-failure.png"));
        fail("No document tiles were rendered: " + state);
    }

    private int occurrences(String xml, String tag) {
        return xml.split(java.util.regex.Pattern.quote(tag), -1).length - 1;
    }

    private Set<String> imagePixels(File file) throws Exception {
        Set<String> hashes = new HashSet<>();
        try (ZipFile zip = new ZipFile(file)) {
            java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                java.util.zip.ZipEntry entry = entries.nextElement();
                if (!entry.getName().startsWith("word/media/") || entry.isDirectory()) continue;
                try (InputStream in = zip.getInputStream(entry)) {
                    Bitmap bitmap = BitmapFactory.decodeStream(in);
                    assertNotNull("Unreadable embedded image", bitmap);
                    int[] pixels = new int[bitmap.getWidth() * bitmap.getHeight()];
                    bitmap.getPixels(pixels, 0, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight());
                    ByteBuffer bytes = ByteBuffer.allocate((pixels.length + 2) * 4);
                    bytes.putInt(bitmap.getWidth()).putInt(bitmap.getHeight());
                    for (int pixel : pixels) bytes.putInt(pixel);
                    hashes.add(Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes.array())));
                    bitmap.recycle();
                }
            }
        }
        return hashes;
    }

    private void assertContentPreserved(File source, File result) throws Exception {
        String original = documentXml(source), actual = documentXml(result);
        assertTrue("Latest edit is missing", actual.contains("OFFICE_LOCAL_PROOF_20261019"));
        assertTrue("Portuguese accents were lost", actual.contains("ação e edição"));
        assertEquals("Image placements changed", occurrences(original, "<w:drawing>"), occurrences(actual, "<w:drawing>"));
        assertEquals("Tables changed", occurrences(original, "<w:tbl>"), occurrences(actual, "<w:tbl>"));
        Set<String> expectedImages = imagePixels(source);
        assertFalse("Fixture must include an image", expectedImages.isEmpty());
        assertEquals("Embedded image pixels changed", expectedImages, imagePixels(result));
    }

    @Test public void editsSavesAndReopensLocalDocxOffline() throws Exception {
        File folder = new File(target.getCacheDir(), "upstream-proof");
        assertTrue(folder.isDirectory() || folder.mkdirs());
        File source = new File(folder, "artigo.docx");
        String phase = InstrumentationRegistry.getArguments().getString("phase", "edit");
        File first = new File(folder, "editado.docx");
        if (phase.equals("reopen")) {
            assertTrue("First phase output is required", first.isFile());
            LOActivity reopened = open(first);
            awaitPaint(reopened, folder, "reaberto");
            File second = new File(folder, "reaberto.docx");
            instrumentation.runOnMainSync(() -> reopened.saveAs(second.toURI().toString(), "docx", null));
            assertContentPreserved(source, second);
            UiDevice.getInstance(instrumentation).takeScreenshot(new File(folder, "reaberto.png"));
            return;
        }
        try (InputStream in = instrumentation.getContext().getAssets().open("artigo.docx");
             FileOutputStream out = new FileOutputStream(source)) { in.transferTo(out); }
        LOActivity editor = open(source);
        awaitPaint(editor, folder, "aberto");
        js(editor, "app.map.setPermission('edit'); app.map.sendUnoCommand('.uno:GoToEndOfDoc'); true");
        SystemClock.sleep(800);
        String marker = "OFFICE_LOCAL_PROOF_20261019 ação e edição";
        instrumentation.runOnMainSync(() -> editor.paste("text/plain;charset=utf-8", marker.getBytes(StandardCharsets.UTF_8)));
        SystemClock.sleep(1500);
        instrumentation.runOnMainSync(() -> editor.saveAs(first.toURI().toString(), "docx", null));
        assertTrue("Saved output missing", first.length() > 0);
        assertContentPreserved(source, first);
        js(editor, "app.map.sendUnoCommand('.uno:GoToStartOfDoc'); true");
        awaitPaint(editor, folder, "editado");
        UiDevice.getInstance(instrumentation).takeScreenshot(new File(folder, "editado.png"));
    }
}
