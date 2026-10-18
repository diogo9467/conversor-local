"""Download build-only tools locally. Never changes PATH or global installations."""
from __future__ import annotations
import concurrent.futures
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
CACHE = ROOT / '.cache'
TOOLS = ROOT / '.tools'
CACHE.mkdir(exist_ok=True)
TOOLS.mkdir(exist_ok=True)

def fetch(url, name=None, digest=None, algorithm='sha256'):
    target = CACHE / (name or url.split('/')[-1])
    if not target.exists():
        print('Downloading:', url, flush=True)
        part = target.with_suffix(target.suffix + '.download')
        req = urllib.request.Request(url, headers={'User-Agent': 'ConversorLocal-build/1.0'})
        with urllib.request.urlopen(req, timeout=120) as response, part.open('wb') as out:
            while block := response.read(1024 * 1024):
                out.write(block)
        part.replace(target)
    actual = hashlib.file_digest(target.open('rb'), algorithm).hexdigest() if sys.version_info >= (3,11) else file_hash(target, algorithm)
    if digest and actual.lower() != digest.lower():
        raise RuntimeError(f'Hash mismatch: {target.name}')
    print('Ready:', target.name, actual, flush=True)
    return target

def file_hash(path, algorithm):
    h = hashlib.new(algorithm)
    with path.open('rb') as f:
        for block in iter(lambda: f.read(1024 * 1024), b''): h.update(block)
    return h.hexdigest()

def get_json(url):
    return json.load(urllib.request.urlopen(urllib.request.Request(url, headers={'User-Agent': 'ConversorLocal-build/1.0'}), timeout=90))

def sdk():
    dest = TOOLS / 'dotnet'
    if (dest / 'dotnet.exe').exists(): return
    data = get_json('https://builds.dotnet.microsoft.com/dotnet/release-metadata/10.0/releases.json')
    version = '10.0.402'
    release = next(s for r in data['releases'] for s in r.get('sdks', [r.get('sdk', {})]) if s.get('version') == version)
    item = next(f for f in release['files'] if f['rid'] == 'win-x64' and f['name'].endswith('.zip'))
    package = fetch(item['url'], digest=item['hash'], algorithm='sha512')
    print('Extracting .NET SDK', flush=True)
    with zipfile.ZipFile(package) as archive: archive.extractall(dest)

def compiler():
    if (TOOLS / 'w64devkit' / 'bin' / 'g++.exe').exists(): return
    url='https://github.com/skeeto/w64devkit/releases/download/v2.10.1/w64devkit-x64-2.10.1.7z.exe'
    package=fetch(url,digest='add1edf984ce4d473cfeb7c52e0ab38db73a855bba6c7f564d5e16648eb2896a')
    subprocess.run([str(package), '-y', f'-o{TOOLS}'],check=True,stdout=subprocess.DEVNULL)

def libreoffice():
    version = '26.2.6'
    name=f'LibreOffice_{version}_Win_x86-64.msi'
    url=f'https://download.documentfoundation.org/libreoffice/stable/26.2.6/win/x86_64/{name}'
    import re
    metadata=urllib.request.urlopen(url+'.mirrorlist',timeout=90).read().decode()
    match=re.search(r'SHA-256.*?([a-fA-F0-9]{64})',metadata,re.S)
    if not match: raise RuntimeError('Official LibreOffice SHA-256 not found')
    digest=match.group(1)
    fetch(url,digest=digest)

def inno():
    if (TOOLS / 'inno' / 'ISCC.exe').exists(): return
    package=fetch('https://github.com/jrsoftware/issrc/releases/download/is-7_1_0/innosetup-7.1.0-x64.exe',digest='0362a383ed217d4c4239b5933866dd96d3eb2102737da92f80f6057a4b40df2f')
    subprocess.run([str(package), '/VERYSILENT', '/SUPPRESSMSGBOXES', '/NORESTART', '/CURRENTUSER', '/NOICONS', f'/DIR={TOOLS / "inno"}'],check=True)

def main():
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        jobs={pool.submit(fn):fn.__name__ for fn in [sdk,compiler,libreoffice,inno]}
        for future in concurrent.futures.as_completed(jobs):
            try: future.result()
            except Exception as exc: print('FAILED',jobs[future],repr(exc),flush=True); raise
    for name in ['pdf2docx','pymupdf','python-docx','python-pptx','pyinstaller','pillow','opencv-python-headless','numpy']:
        j=get_json(f'https://pypi.org/pypi/{name}/json')
        print('PYPI',name,j['info']['version'],j['info'].get('requires_dist'),flush=True)

if __name__ == '__main__': main()
