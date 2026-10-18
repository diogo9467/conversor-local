"""Package source/evidence and compute release hashes; never touches the desktop."""
from pathlib import Path
import hashlib
import json
import subprocess
import zipfile

ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'artifacts';OUT.mkdir(exist_ok=True)

def main():
    version=(ROOT/'VERSION').read_text(encoding='utf-8').strip()
    source=OUT/f'ConversorLocal-{version}-source.zip'
    subprocess.run(['git','archive','--format=zip',f'--output={source}','HEAD'],cwd=ROOT,check=True)
    evidence=OUT/f'ConversorLocal-{version}-test-evidence.zip'
    names=['integration.xml','external-links-fix/unit.xml','external-links-fix/real-documents/validation.json','external-links-fix/update.json','external-links-fix/installed-validation.json']
    with zipfile.ZipFile(evidence,'w',zipfile.ZIP_DEFLATED) as archive:
        for name in names:
            path=ROOT/'test-results'/name
            if path.exists():archive.write(path,name)
        archive.write(ROOT/'docs'/'test-report.md','test-report.md')
        if (ROOT/'docs'/'external-template-fix.md').exists():archive.write(ROOT/'docs'/'external-template-fix.md','external-template-fix.md')
    commit=subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip()
    (OUT/f'BUILD-INFO-{version}.txt').write_text(f'Conversor Local {version}\nSource commit: {commit}\nWindows 11 x64 build 26200\nFix: detach attached Word templates only in private conversion snapshots.\nCurrent validation: see test-evidence.zip and docs/external-template-fix.md.\nOriginal 1.0.0 artifacts retained unchanged.\nModern shell component unchanged and unsigned.\n',encoding='utf-8')
    hashes=[]
    for path in sorted(OUT.iterdir()):
        if not path.is_file() or path.name=='SHA256SUMS.txt':continue
        with path.open('rb') as stream: value=hashlib.file_digest(stream,'sha256').hexdigest()
        hashes.append(f'{value}  {path.name}')
    (OUT/'SHA256SUMS.txt').write_text('\n'.join(hashes)+'\n',encoding='utf-8')
    print('\n'.join(f'{p.name}: {p.stat().st_size:,} bytes' for p in sorted(OUT.iterdir()) if p.is_file()))

if __name__=='__main__':main()
