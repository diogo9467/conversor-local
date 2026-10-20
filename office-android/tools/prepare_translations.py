#!/usr/bin/env python3
"""Pin the official Brazilian Portuguese catalog without replacing a checkout."""
import json
import os
from pathlib import Path
import subprocess

suite = Path(__file__).resolve().parents[1]
lock = json.loads((suite / "upstream.lock.json").read_text())
root = Path(os.environ.get("OFFICE_BUILD_ROOT", "/opt/office-local"))
translations = root / "upstream/engine/translations"


def git(*args):
    return subprocess.check_output(["git", "-C", str(translations), *args], text=True).strip()


if (translations / ".git").exists():
    if git("rev-parse", "HEAD") != lock["translations_commit"]:
        raise SystemExit("Translation checkout differs from the lock; preserved without changes.")
else:
    if translations.exists() and any(translations.iterdir()):
        raise SystemExit("Translation directory is not empty; preserved without changes.")
    translations.mkdir(parents=True, exist_ok=True)
    git("init")
    git("remote", "add", "origin", lock["translations_repository"])
    git("fetch", "--depth", "1", "origin", lock["translations_commit"])
    git("sparse-checkout", "init", "--cone")
    git("sparse-checkout", "set", "source/pt-BR")
    git("checkout", "--detach", "FETCH_HEAD")
assert (translations / "source/pt-BR").is_dir()
print("PT_BR_CATALOG_READY " + git("rev-parse", "HEAD"))
