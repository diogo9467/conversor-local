#!/usr/bin/env python3
"""Inspect the suite APK; does not claim execution on an ARM device.

ELF checks follow ../android/tools/verify_apk.py without changing that baseline.
"""
import argparse
import hashlib
import json
from pathlib import Path
import struct
import zipfile


def inspect(path, abi, base_only):
    machine = {"arm64-v8a": 183, "x86_64": 62}[abi]
    libraries = []
    with zipfile.ZipFile(path) as apk:
        assert apk.testzip() is None, "Invalid APK ZIP"
        assert len(apk.namelist()) == len(set(apk.namelist())), "Duplicate APK members"
        for entry in apk.infolist():
            if entry.filename.startswith(("assets/program/", "assets/share/", "assets/unpack/")):
                assert entry.compress_type == zipfile.ZIP_STORED, "Native asset compressed: " + entry.filename
            if not (entry.filename.startswith("lib/") and entry.filename.endswith(".so")):
                continue
            assert entry.filename.split("/")[1] == abi, "Unexpected ABI: " + entry.filename
            data = apk.read(entry)
            assert data[:6] == b"\x7fELF\x02\x01", "Expected 64-bit little-endian ELF"
            assert struct.unpack_from("<H", data, 18)[0] == machine, "Wrong native architecture"
            start = struct.unpack_from("<Q", data, 32)[0]
            size, count = struct.unpack_from("<HH", data, 54)
            loads = [struct.unpack_from("<IIQQQQQQ", data, start + i * size)
                     for i in range(count) if struct.unpack_from("<I", data, start + i * size)[0] == 1]
            assert loads and min(p[-1] for p in loads) >= 16384, "ELF alignment below 16KB: " + entry.filename
            libraries.append({"name": entry.filename, "bytes": len(data),
                              "sha256": hashlib.sha256(data).hexdigest(),
                              "min_load_alignment": min(p[-1] for p in loads)})
        required = {"liblo-native-code.so", "libandroidapp.so"}
        if not base_only:
            required.add("libmupdf_java.so")
        assert required <= {p["name"].rsplit("/", 1)[-1] for p in libraries}, "Missing native engine"
        for resource in ("assets/program/services.rdb", "assets/unpack/etc/fonts/fonts.conf"):
            assert apk.getinfo(resource).file_size > 0, "Missing runtime resource: " + resource
    with path.open("rb") as stream:
        digest = hashlib.file_digest(stream, "sha256").hexdigest()
    return {"apk": path.name, "bytes": path.stat().st_size, "sha256": digest,
            "abi": abi, "libraries": libraries, "base_only": base_only,
            "physical_device_validation": False}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--abi", choices=["arm64-v8a", "x86_64"], required=True)
    parser.add_argument("--base-only", action="store_true")
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()
    report = inspect(args.apk, args.abi, args.base_only)
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(f"PASS: {report['apk']}, {args.abi}, {len(report['libraries'])} libraries; packaging only")
