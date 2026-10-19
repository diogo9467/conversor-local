#!/usr/bin/env python3
"""Run the real editor proof on an explicit, disposable Android emulator."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import tarfile
import zipfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", type=Path, required=True)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--test-apk", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--package", default="dev.diogo.officelocal.debug")
    args = parser.parse_args()
    if not args.serial.startswith("emulator-"):
        parser.error("This proof changes network settings: use a disposable emulator.")
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    adb = [str(args.adb.resolve()), "-s", args.serial]

    def run(*command, check=True, timeout=180):
        result = subprocess.run([*adb, *command], capture_output=True, text=True,
                                encoding="utf-8", errors="replace", timeout=timeout)
        if check and result.returncode:
            raise RuntimeError(result.stdout + result.stderr)
        return result

    run("wait-for-device")
    run("shell", "cmd", "connectivity", "airplane-mode", "enable")
    run("shell", "svc", "wifi", "disable")
    run("shell", "svc", "data", "disable")
    assert run("shell", "settings", "get", "global", "airplane_mode_on").stdout.strip() == "1"
    run("install", "-r", str(args.apk.resolve()), timeout=300)
    run("install", "-r", str(args.test_apk.resolve()), timeout=300)
    result = run("shell", "am", "instrument", "-w", "-r", "-e", "class",
                 "dev.diogo.officelocal.proof.EditorProofTest",
                 args.package + ".test/androidx.test.runner.AndroidJUnitRunner",
                 check=False, timeout=400)
    (output / "instrumentation.txt").write_text(result.stdout + result.stderr, encoding="utf-8")
    # Collect evidence even when a test fails. Never uninstall the APK here.
    with (output / "editor-files.tar").open("wb") as destination:
        pulled = subprocess.run([*adb, "exec-out", "run-as", args.package,
                                 "tar", "-C", "cache", "-cf", "-", "upstream-proof"],
                                stdout=destination, stderr=subprocess.PIPE, timeout=45)
    if pulled.returncode == 0:
        with tarfile.open(output / "editor-files.tar") as archive:
            archive.extractall(output, filter="data")
    logs = run("logcat", "-d", "-t", "5000", check=False)
    (output / "logcat.txt").write_text(logs.stdout, encoding="utf-8")
    if result.returncode or "OK (1 test)" not in result.stdout:
        print(result.stdout[-7000:])
        raise SystemExit("Editor proof did not pass; preserved logs and files in " + str(output))
    files = {}
    for name in ("editado.docx", "reaberto.docx"):
        path = output / "upstream-proof" / name
        with zipfile.ZipFile(path) as archive:
            document = archive.read("word/document.xml").decode("utf-8")
            assert "OFFICE_LOCAL_PROOF_20261019" in document
            assert "w:tbl" in document
            assert len([n for n in archive.namelist() if n.startswith("word/media/")]) >= 3
        files[name] = hashlib.sha256(path.read_bytes()).hexdigest()
    report = {
        "test": "base editor: open, edit, save, close, reopen, save again",
        "device": args.serial, "airplane_mode": True, "physical_device": False,
        "apk_sha256": hashlib.sha256(args.apk.read_bytes()).hexdigest(),
        "outputs": files,
        "scope": "Upstream editor proof only; converter integration is not covered.",
    }
    (output / "result.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print("PASS: saved and reopened DOCX contains the edit, table and images; " + str(output))


if __name__ == "__main__":
    main()
