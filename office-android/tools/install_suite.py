#!/usr/bin/env python3
"""Apply the small Office Local overlay to a pinned Collabora checkout.

The converter sources are copied from this repository unchanged except for the
explicit adaptations below. Never imports the legacy Office JNI or libraries.
"""
from pathlib import Path
import hashlib
import json
import shutil
import subprocess
import sys

upstream = Path(sys.argv[1]).resolve()
suite = Path(__file__).resolve().parents[1]
repo = suite.parent
lock = json.loads((suite / "upstream.lock.json").read_text())
assert subprocess.check_output(["git", "-C", str(upstream), "rev-parse", "HEAD"], text=True).strip() == lock["commit"]
wrapper = upstream / "android/gradle/wrapper/gradle-wrapper.properties"
properties = wrapper.read_text()
checksum = "distributionSha256Sum=" + lock["gradle_distribution_sha256"]
assert "distributionSha256Sum=" not in properties or checksum in properties
if checksum not in properties: wrapper.write_text(properties.rstrip() + "\n" + checksum + "\n")

def replace(path, old, new):
    text = path.read_text()
    if new in text:
        return
    assert text.count(old) == 1, f"Unexpected source in {path}: {old[:80]}"
    path.write_text(text.replace(old, new))

def copy_tree(source, target):
    shutil.copytree(source, target, dirs_exist_ok=True)

app = upstream / "android/app"
lib = upstream / "android/lib"
copy_tree(repo / "android/app/src/main/java", app / "src/main/java")
copy_tree(repo / "android/app/src/main/assets", app / "src/main/assets")
copy_tree(repo / "android/app/src/androidTest", app / "src/androidTest")
copy_tree(repo / "android/app/src/test", app / "src/test")
copy_tree(suite / "app/src/main", app / "src/main")
copy_tree(suite / "proof/assets", app / "src/androidTest/assets")
copy_tree(suite / "proof/suite", app / "src/androidTest/java/dev/diogo/officelocal/proof")
(app / "src/androidTest/res/drawable").mkdir(parents=True, exist_ok=True)
shutil.copy2(app / "src/main/res/drawable/drawer_header.png", app / "src/androidTest/res/drawable/drawer_header.png")
test_manifest = app / "src/androidTest/AndroidManifest.xml"
replace(test_manifest, "</manifest>", '<instrumentation android:name="androidx.test.runner.AndroidJUnitRunner" android:targetPackage="dev.diogo.officelocal" android:targetProcesses="${officeTestProcess}"/>\n</manifest>')
shutil.copy2(suite / "native/OfficeBridge.cpp", lib / "src/main/cpp/OfficeBridge.cpp")

# Expose only the hooks needed by our activity. The actual editor remains upstream.
activity = lib / "src/main/java/org/libreoffice/androidlib/LOActivity.java"
for member in ("File mTempFile", "boolean documentLoaded", "boolean isDocEditable",
               "boolean copyFileToTemp()", "void copyTempBackToIntent()", "void finishWithProgress()",
               "boolean beforeMessageFromWebView(String[] messageAndParam)"):
    replace(activity, "private " + member, "protected " + member)
replace(activity,
    'if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU && ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {',
    'if (false) { // Office Local uses only SAF and private working files, including Android 8-12.')
replace(activity, 'Log.d(TAG, "postMobileMessage: " + message);', '// Office Local: do not log document content or keystrokes.')
source = activity.read_text()
source = "\n".join('// Office Local: document and clipboard payloads are private.' if any(token in line for token in (
    'Log.d(TAG, "postMobileDebug:', 'Log.d(TAG, "postMobileError:',
    'Log.i(TAG, "set text to clipoard', 'Log.i(TAG, "clipboard comes from us - same instance:',
    'Log.i(TAG, "foreign html')) else line for line in source.split("\n"))
activity.write_text(source)
replace(activity, '    protected void onNewIntent(Intent intent) {',
    '    protected void onNewIntent(Intent intent) {\n        if (handleOfficeIntent(intent)) { super.onNewIntent(intent); return; }')
replace(activity, '    private void updateDarkMode() {',
    '    protected boolean handleOfficeIntent(Intent intent) { return false; }\n\n    private void updateDarkMode() {')
replace(activity, 'mlp.topMargin = insets.top;', 'mlp.topMargin = 0; // Office Local header handles the status bar inset.')
old_save = 'postMobileMessageNative("save dontTerminateEdit=1 dontSaveIfUnmodified=1");'
source = activity.read_text()
if 'protected void saveWorkingCopy()' not in source:
    assert source.count(old_save) == 2
    source = source.replace(old_save, 'saveWorkingCopy();')
    source = source.replace('    @Override\n    protected void onPause()', '    protected void saveWorkingCopy() { ' + old_save + ' }\n\n    @Override\n    protected void onPause()')
    activity.write_text(source)
native_activity = lib / 'src/main/cpp/androidapp.cpp'
ui_manager = upstream / 'browser/src/control/Control.UIManager.ts'
replace(ui_manager, 'initDarkBackgroundUI(activate: boolean): void {',
    'initDarkBackgroundUI(activate: boolean): void {\n\t\tif (window.ThisIsTheAndroidApp) activate = false; // Office Local keeps the document palette light.')
replace(ui_manager, 'isBackgroundDark(): boolean {',
    'isBackgroundDark(): boolean {\n\t\tif (window.ThisIsTheAndroidApp) return false; // Match the native document palette, including Calc transparent cells.')
keyboard = upstream / 'browser/src/map/handler/Map.Keyboard.js'
for key, command in (("C", "Copy"), ("V", "Paste"), ("X", "Cut")):
    replace(keyboard,
        f"if (this.keyCodes.{key}.includes(e.keyCode)) {{\n\t\t\t\tapp.socket.sendMessage('uno .uno:{command}');",
        f"if (this.keyCodes.{key}.includes(e.keyCode)) {{\n\t\t\t\tif (window.ThisIsTheAndroidApp) e.preventDefault(); // Native clipboard owns this event.\n\t\t\t\tapp.socket.sendMessage('uno .uno:{command}');")
replace(native_activity, '    setupKitEnvironment(userInterfaceMode);', '    setupKitEnvironment(userInterfaceMode);\n    setenv("COOL_NO_AUTOSAVE", "1", 1); // Office Local serializes recovery saves with explicit saves.')
for cmake in (lib / "src/main/cpp/CMakeLists.txt.in", lib / "src/main/cpp/CMakeLists.txt"):
    if cmake.exists(): replace(cmake, "            androidapp.cpp\n", "            androidapp.cpp\n            OfficeBridge.cpp\n")

root_gradle = upstream / "android/build.gradle"
replace(root_gradle, "classpath 'com.android.tools.build:gradle:9.3.0'", "classpath 'com.android.tools.build:gradle:9.3.0'\n        classpath 'org.jetbrains.kotlin:compose-compiler-gradle-plugin:2.2.10'")
gradle = app / "build.gradle"
text = gradle.read_text()
marker = "// Office Local: integrated application"
if marker in text: text = text.split(marker)[0]
text += """
// Office Local: integrated application
apply plugin: 'org.jetbrains.kotlin.plugin.compose'
android.buildFeatures.compose = true
android.defaultConfig.versionCode = 100
android.defaultConfig.versionName = '0.2.0-test'
android.buildTypes.debug.applicationIdSuffix = ''
android.buildTypes.debug.resValue 'string', 'app_name', 'Office Local'
android.defaultConfig.testInstrumentationRunner = 'androidx.test.runner.AndroidJUnitRunner'
android.defaultConfig.manifestPlaceholders.officeTestProcess = project.findProperty('editorTests') == 'true' ? 'dev.diogo.officelocal:editor' : 'dev.diogo.officelocal'
android.androidResources.noCompress += ['']
android.sourceSets.main.jniLibs.srcDir 'office-native'
dependencies {
    implementation 'androidx.activity:activity-compose:1.10.1'
    implementation 'androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0'
    implementation 'androidx.lifecycle:lifecycle-runtime-compose:2.9.0'
    implementation platform('androidx.compose:compose-bom:2025.04.01')
    implementation 'androidx.compose.material3:material3'
    implementation 'androidx.compose.ui:ui-tooling-preview'
    implementation 'org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2'
    implementation files('libs/fitz.aar')
    testImplementation 'junit:junit:4.13.2'
    androidTestImplementation 'androidx.test.ext:junit:1.2.1'
    androidTestImplementation 'androidx.test:runner:1.6.2'
    androidTestImplementation 'androidx.test:rules:1.6.1'
    androidTestImplementation 'androidx.test.uiautomator:uiautomator:2.3.0'
}
"""
gradle.write_text(text)

# Asset extraction is versioned by our actual sources as well as upstream revision.
digest = hashlib.sha256()
for directory in (suite / "app", suite / "native", suite / "tools"):
    for path in sorted(directory.rglob("*")):
        if path.is_file() and "__pycache__" not in path.parts: digest.update(path.read_bytes())
lib_gradle = lib / "build.gradle"
replace(lib_gradle, "tasks.register('generateCoolDebugAssets') {\n    doLast {",
    "tasks.register('generateCoolDebugAssets') {\n    doLast {\n        // Configuration mode changes must invalidate previously empty bundles.\n        delete file('src/debug/assets/dist/bundle.css'), file('src/debug/assets/dist/bundle.js'), file('src/debug/assets/dist/cool.html')")
source = lib_gradle.read_text().replace("'BUNDLE=RELEASE' // Office Local: production resources also with a test signature.", "'ENABLE_BUNDLE=true' // Office Local: production resources also with a test signature.")
lib_gradle.write_text(source)
replace(lib_gradle, "'BUNDLE=DEBUG'", "'ENABLE_BUNDLE=true' // Office Local: production resources also with a test signature.")
text = lib_gradle.read_text().split("// Office Local: asset revision")[0]
text += '\n// Office Local: asset revision\nandroid.defaultConfig.buildConfigField "String", "GIT_COMMIT", \'"' + lock["commit"][:12] + '-' + digest.hexdigest()[:16] + '-pt-BR-secure"\'\n'
lib_gradle.write_text(text)

documents = app / "src/main/java/dev/diogo/conversorlocal/files/Documents.kt"
replace(documents,
    "suspend fun publish(output: File, input: InputDocument, conversion: Conversion, folder: Uri): SavedDocument {",
    "suspend fun publish(output: File, input: InputDocument, conversion: Conversion, folder: Uri): SavedDocument =\n        publishFile(output, input, conversion.target, conversion.mime, folder)\n\n    suspend fun publishFile(output: File, input: InputDocument, target: String, mime: String, folder: Uri): SavedDocument {")
text = documents.read_text().replace("availableName(input.name, conversion.target,", "availableName(input.name, target,").replace("folder, conversion.mime, desired", "folder, mime, desired").replace("$folder\", conversion.mime)", "$folder\", mime)")
text = text.replace('"${name(folder)} · $folder"', 'name(folder)')
documents.write_text(text)
activity = app / "src/main/java/dev/diogo/conversorlocal/MainActivity.kt"
text = activity.read_text().replace("Conversor Local", "Conversor · Office Local")
text = text.replace('                                Text(saved.uri.toString(), style = MaterialTheme.typography.bodySmall)\n', '')
text = text.replace("Intent(Intent.ACTION_VIEW).setDataAndType(saved.uri, saved.mime)", "Intent(context, dev.diogo.officelocal.HomeActivity::class.java).setAction(Intent.ACTION_VIEW).setDataAndType(saved.uri, saved.mime)")
text = text.replace('setDataAndType(saved.uri, saved.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)', 'setDataAndType(saved.uri, saved.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)')
activity.write_text(text)
# Repair pre-existing encoding artifacts only in the generated copy, not legacy Android.
validation = app / "src/main/java/dev/diogo/conversorlocal/conversion/Conversion.kt"
text = validation.read_text()
text = "\n".join(line.encode("latin1").decode("utf-8") if "Ã" in line else line for line in text.split("\n"))
validation.write_text(text)

generated = repo / "android/app/build/generated/office"
(app / "libs").mkdir(exist_ok=True)
shutil.copy2(generated / "fitz.aar", app / "libs/fitz.aar")
native_manifest = json.loads((generated / "assets/native-build.json").read_text())
assert native_manifest["ndk"] == "27.2.12479018"
assert native_manifest["source_sha256"] == "98a5c10cda20c3992cdf76ff6b2a1149c32bd79cc796d3f703230b1185b7e934"
assert native_manifest["patch_sha256"] == "c0c84ef8fe95ef5e6a373c3411d74802731915c71b298e0a02caf974451aa9a1"
assert hashlib.sha256((repo / "android/tools/patch_mupdf.py").read_bytes()).hexdigest() == native_manifest["patch_sha256"]
shutil.copy2(generated / "assets/native-build.json", app / "src/main/assets/mupdf-build.json")
if (generated / "assets/licenses").exists():
    copy_tree(generated / "assets/licenses", app / "src/main/assets/licenses")
for abi, sha in native_manifest["libraries"].items():
    assert abi in ("arm64-v8a", "x86_64")
    source = generated / "jniLibs" / abi / "libmupdf_java.so"
    assert hashlib.sha256(source.read_bytes()).hexdigest() == sha, "MuPDF differs from verified baseline"
    target = app / "office-native" / abi
    target.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source, target / source.name)

# The synthetic provider is isolated from the legacy converter's test companion.
for path in (app / "src/androidTest").rglob("*"):
    if path.suffix not in (".java", ".kt", ".xml"): continue
    text = path.read_text().replace("dev.diogo.conversorlocal.tests.documents", "dev.diogo.officelocal.tests.documents")
    text = text.replace('grantUriPermission("dev.diogo.conversorlocal",', 'grantUriPermission("dev.diogo.officelocal",')
    path.write_text(text)

notice = """Office Local 0.2.0 — versão de teste, assinatura de desenvolvimento.
Aplicativo independente; não é o produto oficial da Collabora.
Código e receitas: https://github.com/diogo9467/conversor-local/tree/codex/office-local-android

Collabora Office 26.04.4.2 (cp-26.04.4-2)
Copyright Collabora, The Document Foundation e contribuidores.
MPL-2.0 e licenças próprias dos componentes, fontes e bibliotecas incluídos.
Fontes: https://github.com/CollaboraOnline/online.mirror/tree/201368fc8ded1a0f28d1891c77e0410cb9416531
Avisos completos: assets/license.html, assets/notice.txt e assets/licenses/.
Alterações identificadas em office-android/: integração SAF, recuperação local,
ponte COKit, identidade visual e desativação de macros no Android.

"""
old_notice = (repo / "android/app/src/main/assets/NOTICE.txt").read_text()
notice += old_notice[old_notice.index("MuPDF / Extract"):]
notice += "\nCamada Office Local e conversor: AGPL-3.0-or-later. As modificações de fontes MPL mantêm MPL-2.0.\n"
(app / "src/main/assets/NOTICE.txt").write_text(notice)
print("Suite overlay applied; old Android and Windows files unchanged.")
