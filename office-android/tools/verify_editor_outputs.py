#!/usr/bin/env python3
"""Audit pulled synthetic editor outputs. Host dependencies: PyMuPDF and Pillow."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import xml.etree.ElementTree as ET
import zipfile

import fitz
from PIL import Image

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("evidence", type=Path, help="The pulled upstream-proof directory")
parser.add_argument("--report", type=Path, required=True)
args = parser.parse_args()
repo = Path(__file__).resolve().parents[2]
report = {"physical_device": False, "checks": []}

def pixels(data):
    with Image.open(io.BytesIO(data)) as image:
        rgba = image.convert("RGBA")
        return (rgba.size, hashlib.sha256(rgba.tobytes()).hexdigest())

def office_parts(path, prefix):
    with zipfile.ZipFile(path) as archive:
        media = {pixels(archive.read(name)) for name in archive.namelist()
                 if name.startswith(prefix + "/media/") and name.endswith(".png")}
        xml = [ET.fromstring(archive.read(name)) for name in archive.namelist()
               if (name == "word/document.xml" or
                   (name.startswith("ppt/slides/slide") and name.endswith(".xml")))]
        counts = {tag: sum(1 for root in xml for node in root.iter()
                          if node.tag.rsplit("}", 1)[-1] == tag)
                  for tag in ("tbl", "drawing", "pic")}
        return media, counts

def text_colors(document):
    return [(char, span["color"]) for page in document
            for block in page.get_text("dict")["blocks"] if "lines" in block
            for line in block["lines"] for span in line["spans"]
            for char in span["text"] if not char.isspace()]


for format in ("docx", "xlsx", "pptx"):
    # Compare themes within the same loaded model. Saving/reimporting OOXML can
    # change text metrics independently of the theme; check its colors below.
    for light_mode, dark_mode in (("latest", "edit-dark"),
                                  ("reopen-light", "cold-dark"),
                                  ("reopen-light", "reopen-dark")):
        with fitz.open(args.evidence / f"{format}-{light_mode}.pdf") as light, fitz.open(args.evidence / f"{format}-{dark_mode}.pdf") as dark:
            assert len(light) == len(dark), f"Theme changed page count: {format}"
            for i in range(len(light)):
                left = light[i].get_pixmap(alpha=False)
                right = dark[i].get_pixmap(alpha=False)
                assert (left.width, left.height, left.samples) == (right.width, right.height, right.samples), f"Theme changed exported pixels: {format}, {dark_mode}, page {i+1}"
            report["checks"].append({"format": format, "mode": dark_mode, "exported_pages": len(light), "light_dark_pixels_identical": True})
    with fitz.open(args.evidence / f"{format}-latest.pdf") as before, fitz.open(args.evidence / f"{format}-cold-dark.pdf") as after:
        assert text_colors(before) == text_colors(after), f"Cold reopen changed text colors: {format}"
        report["checks"].append({"format": format, "cold_reopen_text_colors_preserved": True})

for format, fixture, prefix in (("docx", "artigo.docx", "word"), ("pptx", "seminario.pptx", "ppt")):
    initial = office_parts(repo / "android/app/src/androidTest/assets" / fixture, prefix)
    saved = office_parts(args.evidence / f"{format}-reopened.{format}", prefix)
    assert initial == saved, f"Images or table/picture counts changed: {format}"
    report["checks"].append({"format": format, "original_image_pixels_preserved": True, "objects": saved[1]})

args.report.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
print("PASS: all exported pages retain their colors; original pictures and tables preserved")
