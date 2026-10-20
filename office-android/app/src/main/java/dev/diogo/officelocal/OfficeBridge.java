// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.diogo.officelocal;
import android.content.res.AssetManager;
public final class OfficeBridge {
    static { System.loadLibrary("androidapp"); }
    private OfficeBridge() { }
    public static native void convert(String data, String cache, String apk, AssetManager assets,
        String profile, String input, String output);
    public static native void exportCurrent(String output, String format);
}
