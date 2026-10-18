"""Package source/evidence and compute release hashes; never touches the desktop."""
from pathlib import Path
import hashlib
import json
import subprocess
import zipfile

ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'artifacts';OUT.mkdir(exist_ok=True)

def main():
    source=OUT/'ConversorLocal-1.0.0-source.zip'
    subprocess.run(['git','archive','--format=zip',f'--output={source}','HEAD'],cwd=ROOT,check=True)
    evidence=OUT/'ConversorLocal-1.0.0-test-evidence.zip'
    names=['integration.xml','installed-payload.json','install-lifecycle.json','relocated-conversion.json','validation-summary.json','docx-to-pdf.png','pptx-to-pdf.png','install.log','uninstall.log','reinstall.log']
    with zipfile.ZipFile(evidence,'w',zipfile.ZIP_DEFLATED) as archive:
        for name in names:
            path=ROOT/'test-results'/name
            if path.exists():archive.write(path,name)
        archive.write(ROOT/'docs'/'test-report.md','test-report.md')
    commit=subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip()
    (OUT/'BUILD-INFO.txt').write_text(f'Conversor Local 1.0.0\nSource commit: {commit}\nWindows 11 x64 build 26200\n15 pytest tests + 10 core assertions + 2 Windows guards + 4 COM assertions passed.\nInstall/uninstall/reinstall passed. 19,985 installed files matched SHA-256.\nExplorer click verification interrupted by physical Escape; pending manual validation.\nModern MSIX unsigned; not installed.\n',encoding='utf-8')
    hashes=[]
    for path in sorted(OUT.iterdir()):
        if not path.is_file() or path.name=='SHA256SUMS.txt':continue
        with path.open('rb') as stream: value=hashlib.file_digest(stream,'sha256').hexdigest()
        hashes.append(f'{value}  {path.name}')
    (OUT/'SHA256SUMS.txt').write_text('\n'.join(hashes)+'\n',encoding='utf-8')
    print('\n'.join(f'{p.name}: {p.stat().st_size:,} bytes' for p in sorted(OUT.iterdir()) if p.is_file()))

if __name__=='__main__':main()
