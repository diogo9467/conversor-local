package dev.diogo.conversorlocal;

import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.os.ResultReceiver;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/** Test APK's separate UID; uses only Android/Java, not the target APK's Kotlin runtime. */
public class TestGrantActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ResultReceiver reply = getIntent().getParcelableExtra("reply");
        try {
            File root = new File(getFilesDir(), "documents"); root.mkdirs();
            String asset = getIntent().getStringExtra("asset");
            if (asset != null) {
                File file = new File(root, getIntent().getStringExtra("name"));
                if (!file.getCanonicalFile().getParentFile().equals(root.getCanonicalFile())) throw new SecurityException();
                try (InputStream input = getAssets().open(asset); FileOutputStream output = new FileOutputStream(file)) {
                    byte[] buffer = new byte[65536]; int count;
                    while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
                }
            }
            grantUriPermission("dev.diogo.conversorlocal", Uri.parse(getIntent().getStringExtra("uri")), getIntent().getIntExtra("flags", 0));
            reply.send(1, new Bundle());
        } catch (Exception error) {
            Bundle result = new Bundle(); result.putString("error", error.toString()); reply.send(0, result);
        }
        finish();
    }
}
