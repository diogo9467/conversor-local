#!/usr/bin/env python3
"""Small, idempotent Android-only macro policy patch for the pinned source."""
import json
from pathlib import Path
import subprocess
import sys

root = Path(sys.argv[1]).resolve()
lock = json.loads((Path(__file__).resolve().parents[1] / "upstream.lock.json").read_text())
assert subprocess.check_output(["git", "-C", str(root), "rev-parse", "HEAD"], text=True).strip() == lock["commit"]
path = root / "engine/desktop/source/lib/init.cxx"
source = path.read_text()
old = """#ifdef ANDROID
        sal_Int16 nMacroExecMode = document::MacroExecMode::USE_CONFIG;
#else"""
new = """#ifdef ANDROID
        // Office Local: local documents never execute macros, including shared scripts.
        sal_Int16 nMacroExecMode = document::MacroExecMode::NEVER_EXECUTE;
        std::shared_ptr<comphelper::ConfigurationChanges> xAndroidSecurity =
            comphelper::ConfigurationChanges::create();
        officecfg::Office::Common::Security::Scripting::DisableMacrosExecution::set(
            true, xAndroidSecurity);
        xAndroidSecurity->commit();
        SvtSecurityOptions::SetMacroSecurityLevel(3);
#else"""
if new not in source:
    assert source.count(old) == 1, "Unexpected source; refusing fuzzy security patch"
    path.write_text(source.replace(old, new))
print("Android macro execution disabled in the engine source.")
