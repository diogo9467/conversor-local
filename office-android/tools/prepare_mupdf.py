#!/usr/bin/env python3
"""Reuse the converter's exact source patch and builder; do not download old LO."""
from pathlib import Path
import sys

repo = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(repo / "android/tools"))
from prepare_engines import download, CACHE, OUT, LOCK
from build_mupdf import build

abis = sys.argv[1].split(",") if len(sys.argv) > 1 else ["arm64-v8a", "x86_64"]
assert set(abis) <= {"arm64-v8a", "x86_64"}
spec = LOCK["mupdf"]
download(f"https://maven.ghostscript.com/com/artifex/mupdf/fitz/{spec['version']}/fitz-{spec['version']}.aar",
         spec["aar_sha256"], f"fitz-{spec['version']}.aar")
(OUT / "assets").mkdir(parents=True, exist_ok=True)
build(abis, download, CACHE, OUT, LOCK)
