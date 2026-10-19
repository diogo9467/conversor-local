#!/usr/bin/env python3
"""Add only the base-editor proof to the pinned upstream checkout."""
from pathlib import Path
import shutil
import json
import subprocess
import sys

upstream = Path(sys.argv[1]).resolve()
suite = Path(__file__).resolve().parents[1]
lock = json.loads((suite / "upstream.lock.json").read_text())
commit = subprocess.check_output(["git", "-C", str(upstream), "rev-parse", "HEAD"], text=True).strip()
if commit != lock["commit"]:
    raise SystemExit("Unexpected upstream checkout; no files were changed.")
wrapper = upstream / "android/gradle/wrapper/gradle-wrapper.properties"
properties = wrapper.read_text()
checksum_line = "distributionSha256Sum=" + lock["gradle_distribution_sha256"]
if "distributionSha256Sum=" in properties and checksum_line not in properties:
    raise SystemExit("Unexpected Gradle checksum; no files were changed.")
if "distributionSha256Sum=" not in properties:
    wrapper.write_text(properties.rstrip() + "\n" + checksum_line + "\n")
gradle = upstream / "android/app/build.gradle"
source = gradle.read_text()
marker = "// Office Local: upstream editor proof"
packaging_marker = "// Office Local: native APK asset reader requires ZIP_STORED"
if packaging_marker not in source:
    source += """
// Office Local: native APK asset reader requires ZIP_STORED
// engine/sal/android/cokit-bootstrap.c: lo_apkentry rejects compressed members.
android.androidResources.noCompress += ['']
"""
if marker not in source:
    source += """
// Office Local: upstream editor proof
android.defaultConfig.testInstrumentationRunner = 'androidx.test.runner.AndroidJUnitRunner'
dependencies {
    androidTestImplementation 'androidx.test.ext:junit:1.2.1'
    androidTestImplementation 'androidx.test:runner:1.6.2'
    androidTestImplementation 'androidx.test.uiautomator:uiautomator:2.3.0'
}
"""
gradle.write_text(source)
tests = upstream / "android/app/src/androidTest"
(tests / "java/dev/diogo/officelocal/proof").mkdir(parents=True, exist_ok=True)
shutil.copy2(suite / "proof/EditorProofTest.java", tests / "java/dev/diogo/officelocal/proof/EditorProofTest.java")
(tests / "assets").mkdir(exist_ok=True)
shutil.copy2(suite.parent / "android/app/src/androidTest/assets/artigo.docx", tests / "assets/artigo.docx")
# The app's generated resValue also appears in the test package, which does not
# inherit this drawable from the target APK. Use the exact upstream test resource.
(tests / "res/drawable").mkdir(parents=True, exist_ok=True)
shutil.copy2(upstream / "android/app/src/main/res/drawable/drawer_header.png",
             tests / "res/drawable/drawer_header.png")
print("Upstream proof installed; production editor sources unchanged.")
