"""Acquire fixed offline OCR data, MSIX packaging tools and license/source material."""
from __future__ import annotations
import importlib.metadata as metadata
import json
from pathlib import Path
import re
import shutil
import sys
import tarfile
import urllib.request
import zipfile
from bootstrap import fetch,get_json,ROOT,TOOLS

APP=ROOT/'.build'/'app'

def main():
    lock=sorted(f'{d.metadata["Name"]}=={d.version}' for d in metadata.distributions())
    (ROOT/'tools'/'requirements-build.lock.txt').write_text('\n'.join(lock)+'\n',encoding='utf-8')
    tessdata=APP/'ocr'/'tessdata';tessdata.mkdir(parents=True,exist_ok=True)
    ledger=[]
    for name in ['eng','por']:
        url=f'https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/4.1.0/{name}.traineddata'
        hashes={'eng':'7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2','por':'c4932b937207a9514b7514d518b931a99938c02a28a5a5a553f8599ed58b7deb'}
        p=fetch(url,digest=hashes[name])
        shutil.copy2(p,tessdata/p.name)
        ledger.append({'component':name+'.traineddata','source':url})
    license_file=fetch('https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/4.1.0/LICENSE','tessdata-LICENSE')
    shutil.copy2(license_file,APP/'ocr'/'LICENSE')

    sdk_version='10.0.26100.3916'
    url=f'https://api.nuget.org/v3-flatcontainer/microsoft.windows.sdk.buildtools/{sdk_version}/microsoft.windows.sdk.buildtools.{sdk_version}.nupkg'
    sdk=fetch(url,'windows-sdk-buildtools.nupkg',digest='9cb064e5815cd3f3ae89ee223c82f7091be0caa07507e38b871137e4ea3d5d33')
    with zipfile.ZipFile(sdk) as archive: archive.extractall(TOOLS/'windows-sdk')

    licenses=APP/'licenses';licenses.mkdir(exist_ok=True)
    packages=['pdf2docx','PyMuPDF','python-docx','python-pptx','Pillow','opencv-python-headless','numpy','fonttools','fire','lxml','typing_extensions','XlsxWriter','termcolor','pyinstaller']
    for name in packages:
        dist=metadata.distribution(name)
        dest=licenses/(name+'-'+dist.version);dest.mkdir(exist_ok=True)
        for f in dist.files or []:
            if '.dist-info/' in str(f).replace('\\','/') and (any(w in f.name.lower() for w in ['license','copying','notice','metadata']) or 'licenses' in f.parts):
                p=Path(dist.locate_file(f));out=dest/str(f).split('.dist-info',1)[1].lstrip('/\\');out.parent.mkdir(parents=True,exist_ok=True)
                if p.is_file():shutil.copy2(p,out)
        ledger.append({'component':name,'version':dist.version,'license':dist.metadata.get('License-Expression') or dist.metadata.get('License') or 'See bundled license files','source':f'https://pypi.org/project/{name}/{dist.version}/'})
    python_license=Path(sys.base_prefix)/'LICENSE.txt'
    if python_license.exists():shutil.copy2(python_license,licenses/'Python-LICENSE.txt')
    mingw=TOOLS/'w64devkit'/'COPYING.MinGW-w64-runtime.txt'
    if mingw.exists():shutil.copy2(mingw,licenses/mingw.name)
    pyinstaller=Path(metadata.distribution('pyinstaller').locate_file('PyInstaller'))
    for name in ['COPYING.txt','COPYING']:
        if (pyinstaller/name).exists():shutil.copy2(pyinstaller/name,licenses/'PyInstaller-COPYING.txt')

    sources=APP/'sources'/'third-party';sources.mkdir(parents=True,exist_ok=True)
    # Supply the exact AGPL engine source beside the binary, with its upstream MuPDF source.
    pymupdf=get_json('https://pypi.org/pypi/PyMuPDF/1.27.2.3/json')
    source=next(item for item in pymupdf['urls'] if item['packagetype']=='sdist')
    p=fetch(source['url'],digest=source['digests']['sha256'])
    shutil.copy2(p,sources/p.name)
    with tarfile.open(p) as archive:
        member=next((m for m in archive.getmembers() if m.name.endswith('/setup.py')),None)
        text=archive.extractfile(member).read().decode() if member else ''
    version=re.search(r"version_mupdf = '([^']+)'",text)
    if not version: raise RuntimeError('Exact MuPDF source version not found')
    urls=[f'https://mupdf.com/downloads/archive/mupdf-{version.group(1)}-source.tar.gz']
    for url in sorted(set(urls)):
        p=fetch(url,digest='553867b135303dc4c25ab67c5f234d8e900a0e36e66e8484d99adc05fe1e8737')
        shutil.copy2(p,sources/p.name)
        ledger.append({'component':'MuPDF corresponding source','source':url})
    (APP/'dependency-manifest.json').write_text(json.dumps(ledger,indent=2,ensure_ascii=False),encoding='utf-8')
    print('Offline OCR, SDK and notices ready',flush=True)

if __name__=='__main__':main()
