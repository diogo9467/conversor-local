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
    report='external-template-fix.md'
    summary='Detach attached Word templates only in private conversion snapshots.'
    if version=='1.0.2':
        names=['ui-refresh/integration.xml','ui-refresh/validation.json','ui-refresh/update.json','ui-refresh/settings.png','ui-refresh/progress.png','ui-refresh/result.png']
        report='ui-refresh.md'
        summary='Refresh progress, results and settings appearance; conversion engine unchanged.'
    if version=='1.1.0':
        names=['integration.xml','extended-test-run.log','extended-routes.jsonl','extended-formats/validation.json','extended-formats/engine-preservation.json','extended-formats/ui-conversion.json','extended-formats/shell-invoke.json','extended-formats/shell-invoke-test.cpp','extended-formats/install-run.json','extended-formats/install-menu-repair.json','extended-formats/final-install.json','extended-formats/install-update.json','extended-formats/installed-validation.json','extended-formats/final-checks.log','extended-formats/settings.png','extended-formats/target-choice.png','extended-formats/result.png','extended-formats/word-review.png','extended-formats/excel-review.png','extended-formats/output-review.png']
        report='extended-formats.md'
        summary='Add OpenDocument, RTF, text, spreadsheets and images; add local file/format selection and classic shell handler.'
    with zipfile.ZipFile(evidence,'w',zipfile.ZIP_DEFLATED) as archive:
        for name in names:
            path=ROOT/'test-results'/name
            if path.exists():archive.write(path,name)
        archive.write(ROOT/'docs'/'test-report.md','test-report.md')
        if (ROOT/'docs'/report).exists():archive.write(ROOT/'docs'/report,report)
    commit=subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip()
    (OUT/f'BUILD-INFO-{version}.txt').write_text(f'Conversor Local {version}\nSource commit: {commit}\nWindows 11 x64 build 26200\nChange: {summary}\nCurrent validation: see test-evidence.zip and docs/{report}.\nEarlier version artifacts retained unchanged.\nModern shell package is unsigned; modern menu installation unverified.\n',encoding='utf-8')
    hashes=[]
    for path in sorted(OUT.iterdir()):
        if not path.is_file() or path.name=='SHA256SUMS.txt':continue
        with path.open('rb') as stream: value=hashlib.file_digest(stream,'sha256').hexdigest()
        hashes.append(f'{value}  {path.name}')
    (OUT/'SHA256SUMS.txt').write_text('\n'.join(hashes)+'\n',encoding='utf-8')
    print('\n'.join(f'{p.name}: {p.stat().st_size:,} bytes' for p in sorted(OUT.iterdir()) if p.is_file()))

if __name__=='__main__':main()
