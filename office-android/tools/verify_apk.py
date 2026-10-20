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
            if entry.compress_type == zipfile.ZIP_STORED:
                with path.open("rb") as stream:
                    stream.seek(entry.header_offset + 26)
                    name_length, extra_length = struct.unpack("<HH", stream.read(4))
                data_offset = entry.header_offset + 30 + name_length + extra_length
                assert data_offset % 16384 == 0, "APK native library offset is not 16KB aligned: " + entry.filename
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
                              "min_load_alignment": min(p[-1] for p in loads),
                              "zip_16kb_aligned": entry.compress_type == zipfile.ZIP_STORED})
        required = {"liblo-native-code.so", "libandroidapp.so"}
        if not base_only:
            assert apk.getinfo("assets/dist/bundle.css").file_size > 100000, "Editor CSS missing or empty"
            assert apk.getinfo("assets/dist/bundle.js").file_size > 100000, "Editor JS missing or empty"
            required.add("libmupdf_java.so")
        assert required <= {p["name"].rsplit("/", 1)[-1] for p in libraries}, "Missing native engine"
        for resource in ("assets/program/services.rdb", "assets/unpack/etc/fonts/fonts.conf"):
            assert apk.getinfo(resource).file_size > 0, "Missing runtime resource: " + resource
        if not base_only:
            for resource in ("assets/NOTICE.txt", "assets/license.html", "assets/notice.txt", "assets/mupdf-build.json"):
                assert apk.getinfo(resource).file_size > 0, "Missing license/build provenance: " + resource
            for extension in ("docx", "odt", "xlsx", "ods", "pptx", "odp"):
                assert apk.getinfo("assets/templates/untitled." + extension).file_size > 0
            assert not any(p["name"].endswith("liblo-native.so") for p in libraries), "Legacy engine bundled"
            native = json.loads(apk.read("assets/mupdf-build.json"))
            assert hashlib.sha256(apk.read("lib/" + abi + "/libmupdf_java.so")).hexdigest() == native["libraries"][abi]
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
