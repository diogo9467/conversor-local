#!/usr/bin/env python3
"""Prepare the upstream Android toolchain in a dedicated Linux build directory."""
from pathlib import Path
import hashlib
import os
import shutil
import subprocess
import sys
import tarfile
import urllib.request
import zipfile

ROOT = Path(os.environ.get("OFFICE_BUILD_ROOT", "/opt/office-local")).resolve()
SDK = ROOT / "android-sdk"
CACHE = ROOT / "downloads"


def download(url, path, expected=None, algorithm="sha256"):
    path.parent.mkdir(parents=True, exist_ok=True)
    if not path.exists():
        part = path.with_suffix(path.suffix + ".part")
        with urllib.request.urlopen(url, timeout=120) as source, part.open("wb") as target:
            shutil.copyfileobj(source, target)
        part.replace(path)
    digest = hashlib.new(algorithm, path.read_bytes()).hexdigest()
    if expected and digest != expected:
        raise RuntimeError(f"Checksum mismatch: {path.name}")
    print(path.name, algorithm, digest, flush=True)


def main():
    if sys.platform != "linux":
        raise SystemExit("Use Linux or an isolated WSL distribution for the upstream native build.")
    archive = CACHE / "commandlinetools-linux-16111833_latest.zip"
    download("https://dl.google.com/android/repository/" + archive.name, archive,
             "0877a1d048fe4a24efe2eff536ca4223f7adeb58648bb81909d33c446918cfa8")
    manager = SDK / "cmdline-tools/23.0/bin/android"
    if not manager.exists():
        with zipfile.ZipFile(archive) as source:
            source.extractall(SDK / "cmdline-tools")
        (SDK / "cmdline-tools/cmdline-tools").rename(SDK / "cmdline-tools/23.0")
        for binary in manager.parent.iterdir():
            binary.chmod(0o755)
    env = dict(os.environ, ANDROID_HOME=str(SDK), ANDROID_SDK_ROOT=str(SDK))
    for package in ("platform-tools", "platforms/android-37.0", "build-tools/37.0.0",
                    "ndk/29.0.14206865", "ndk/27.2.12479018", "cmake/3.22.1"):
        subprocess.run([str(manager), "--no-metrics", "--sdk=" + str(SDK),
                        "sdk", "install", package], env=env, check=True)
    # The CLI can return success when a requested package does not exist.
    for relative in ("platforms/android-37.0/android.jar", "build-tools/37.0.0/aapt2",
                     "ndk/29.0.14206865/source.properties", "ndk/27.2.12479018/source.properties", "cmake/3.22.1/bin/cmake"):
        if not (SDK / relative).is_file():
            raise RuntimeError(f"Incomplete toolchain: {relative}")
    node_name = "node-v22.20.0-linux-x64"
    node_archive = CACHE / (node_name + ".tar.xz")
    sums_url = "https://nodejs.org/dist/v22.20.0/SHASUMS256.txt"
    sums = urllib.request.urlopen(sums_url, timeout=60).read().decode()
    expected = next(line.split()[0] for line in sums.splitlines() if line.endswith(node_archive.name))
    download("https://nodejs.org/dist/v22.20.0/" + node_archive.name, node_archive, expected)
    if not (ROOT / node_name).exists():
        with tarfile.open(node_archive) as source:
            source.extractall(ROOT, filter="data")
    print("TOOLCHAIN_READY", flush=True)


if __name__ == "__main__":
    main()
