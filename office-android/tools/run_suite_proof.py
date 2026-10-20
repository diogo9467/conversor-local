#!/usr/bin/env python3
"""Run independent emulator tests and preserve evidence even on failure.

Build main tests normally; build editor tests with -PeditorTests=true. Both use
AndroidJUnitRunner; targetProcesses in the merged manifest selects the process.
"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import tarfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--adb", type=Path, required=True)
parser.add_argument("--serial", required=True)
parser.add_argument("--test-apk", type=Path, required=True)
parser.add_argument("--output", type=Path, required=True)
parser.add_argument("--mode", choices=["converter", "editor", "flow", "home"], required=True)
parser.add_argument("--formats", default="docx,xlsx,pptx")
parser.add_argument("--phases", default="edit,reopen,new,odf")
args = parser.parse_args()
assert args.serial.startswith("emulator-"), "Use a dedicated emulator; no physical device settings are changed."
adb = [str(args.adb.resolve()), "-s", args.serial]
output = args.output.resolve(); output.mkdir(parents=True, exist_ok=True)

def run(*command, timeout=180):
    return subprocess.run([*adb, *command], capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=timeout)

run("shell", "cmd", "connectivity", "airplane-mode", "enable")
run("shell", "svc", "wifi", "disable")
run("shell", "svc", "data", "disable")
assert run("shell", "settings", "get", "global", "airplane_mode_on").stdout.strip() == "1"
assert run("install", "-r", str(args.test_apk.resolve())).returncode == 0
apk_path = run("shell", "pm", "path", "dev.diogo.officelocal").stdout.strip().removeprefix("package:")
assert apk_path.startswith("/data/app/") and apk_path.endswith("/base.apk")
apk_hash = run("shell", "sha256sum", apk_path).stdout.split()[0]
assert len(apk_hash) == 64 and all(c in "0123456789abcdef" for c in apk_hash)
with args.test_apk.open("rb") as stream:
    test_hash = hashlib.file_digest(stream, "sha256").hexdigest()
if args.mode == "home":
    tests = [("adaptive-home", ["-e", "class", "dev.diogo.officelocal.proof.SuiteHomeTest"])]
elif args.mode == "flow":
    tests = [("pdf-to-editor", ["-e", "class", "dev.diogo.officelocal.proof.SuiteFlowTest"])]
elif args.mode == "converter":
    tests = [("converter", ["-e", "class", "dev.diogo.conversorlocal.EngineProofTest,dev.diogo.conversorlocal.WorkflowTest,dev.diogo.officelocal.proof.SuiteFilesTest"])]
else:
    tests = [(f"{fmt}-{phase}", ["-e", "class", "dev.diogo.officelocal.proof.SuiteEditorTest", "-e", "format", fmt, "-e", "phase", phase])
             for fmt in args.formats.split(",") for phase in args.phases.split(",")]
results = []
try:
    for name, options in tests:
        run("shell", "am", "force-stop", "dev.diogo.officelocal")
        result = run("shell", "am", "instrument", "-w", "-r", *options,
                     "dev.diogo.officelocal.test/androidx.test.runner.AndroidJUnitRunner", timeout=420)
        (output / f"{name}.txt").write_text(result.stdout + result.stderr, encoding="utf-8")
        passed = result.returncode == 0 and "OK (" in result.stdout and "FAILURES!!!" not in result.stdout
        results.append({"name": name, "passed": passed})
        print(name + (": PASS" if passed else ": FAIL"), flush=True)
        if not passed:
            print(result.stdout[-3500:], flush=True)
            break
finally:
    (output / "result.json").write_text(json.dumps({"results": results, "device": args.serial,
        "airplane_mode": True, "physical_device": False, "apk_sha256": apk_hash,
        "test_apk_sha256": test_hash}, indent=2), encoding="utf-8")
    with (output / "evidence.tar").open("wb") as out:
        pulled = subprocess.run([*adb, "exec-out", "run-as", "dev.diogo.officelocal", "tar", "-C", "cache", "-cf", "-", "upstream-proof"], stdout=out, stderr=subprocess.PIPE, timeout=60)
    if pulled.returncode == 0:
        with tarfile.open(output / "evidence.tar") as tar: tar.extractall(output, filter="data")
    (output / "logcat.txt").write_text(run("logcat", "-d", "-t", "5000").stdout, encoding="utf-8")
if len(results) != len(tests) or not all(r["passed"] for r in results):
    raise SystemExit("Tests incomplete or failed; evidence preserved in " + str(output))
