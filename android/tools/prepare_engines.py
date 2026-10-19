"""Extract the pinned upstream Android engines; no Office app is installed/required.

Run from any directory. Downloads occur only during development, never on the phone.
"""
from pathlib import Path, PurePosixPath
import argparse, hashlib, json, shutil, urllib.request, zipfile

ROOT = Path(__file__).resolve().parents[1]
LOCK = json.loads((ROOT / 'engine-lock.json').read_text(encoding='utf-8'))
CACHE = ROOT.parent / '.cache/android'
OUT = ROOT / 'app/build/generated/office'

def download(url, expected, name):
    CACHE.mkdir(parents=True, exist_ok=True)
    path = CACHE / name
    if not path.exists():
        request = urllib.request.Request(url, headers={'User-Agent': 'ConversorLocal-build/0.1'})
        with urllib.request.urlopen(request, timeout=180) as response, path.with_suffix('.part').open('wb') as stream:
            shutil.copyfileobj(response, stream)
        path.with_suffix('.part').replace(path)
    with path.open('rb') as stream:
        actual = hashlib.file_digest(stream, 'sha256').hexdigest()
    if actual != expected:
        raise RuntimeError(f'SHA-256 incorreto: {name}')
    return path

def prepare(abis):
    first = True
    office = LOCK['libreoffice']
    for abi in abis:
        spec = office['artifacts'][abi]
        apk = download(spec['url'], spec['sha256'], {'arm64-v8a': 'lo-arm64.apk', 'x86_64': 'lo-x64.apk'}[abi])
        with zipfile.ZipFile(apk) as archive:
            for entry in archive.infolist():
                parts = PurePosixPath(entry.filename).parts
                if entry.is_dir() or '..' in parts:
                    continue
                dest = None
                if parts[:2] == ('lib', abi):
                    dest = OUT / 'jniLibs' / abi / parts[-1]
                elif first and parts[0] == 'assets' and parts[1] in ('program', 'share', 'unpack'):
                    dest = OUT / entry.filename
                if dest:
                    dest.parent.mkdir(parents=True, exist_ok=True)
                    dest.write_bytes(archive.read(entry))
        first = False
        print('Prepared LibreOfficeKit ' + office['version'] + ' ' + abi, flush=True)
    for name, digest in office['java'].items():
        url = f"https://raw.githubusercontent.com/LibreOffice/core/{office['source_ref']}/android/Bootstrap/src/org/libreoffice/kit/{name}"
        source = download(url, digest, 'lo-' + name)
        dest = OUT / 'java/org/libreoffice/kit' / name
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, dest)
        if name == 'LibreOfficeKit.java':
            # JNI signatures stay unchanged. A Service needs Context, not Activity.
            text = dest.read_text(encoding='utf-8')
            text = text.replace('import android.app.Activity;', 'import android.content.Context;')
            text = text.replace('init(Activity activity)', 'init(Context activity)')
            text = text.replace('activity.getApplication().getCacheDir()', 'activity.getCacheDir()')
            text = text.replace('activity.getApplication().getPackageResourcePath()', 'activity.getPackageResourcePath()')
            dest.write_text(text, encoding='utf-8')
    mupdf = LOCK['mupdf']
    aar = download(f"https://maven.ghostscript.com/com/artifex/mupdf/fitz/{mupdf['version']}/fitz-{mupdf['version']}.aar", mupdf['aar_sha256'], f"fitz-{mupdf['version']}.aar")
    from build_mupdf import build
    build(abis, download, CACHE, OUT, LOCK)

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--abis', default='arm64-v8a,x86_64')
    prepare(parser.parse_args().abis.split(','))
