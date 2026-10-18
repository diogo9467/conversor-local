"""Validate local DOCX copies without putting private document content in Git or logs."""
from pathlib import Path
import argparse
import hashlib
import json
import re
import shutil
import subprocess
import zipfile
import xml.etree.ElementTree as ET
import pymupdf as fitz

ROOT=Path(__file__).resolve().parents[1]
W='{http://schemas.openxmlformats.org/wordprocessingml/2006/main}'

def digest(path):return hashlib.sha256(path.read_bytes()).hexdigest()
def words(text):return re.findall(r'\w{3,}',text.casefold())

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--input-directory',required=True);parser.add_argument('--app',default=str(ROOT/'.build/app/ConversorLocal.exe'))
    args=parser.parse_args();folder=Path(args.input_directory);dest=ROOT/'test-results/external-links-fix/real-documents';dest.mkdir(parents=True,exist_ok=True)
    evidence=[]
    for number,source in enumerate(sorted(folder.glob('*.docx')),start=1):
        before=digest(source);copy=dest/source.name;shutil.copy2(source,copy)
        report=dest/f'conversion-{number}.json'
        process=subprocess.run([args.app,'--no-ui','--to','pdf','--report',str(report),'--',str(copy)],capture_output=True,timeout=300)
        if not report.exists():raise RuntimeError(f'Conversion report missing for document {number}: {process.returncode}')
        result=json.loads(report.read_text(encoding='utf-8-sig'))[0]
        if process.returncode!=0 or not result['Success']:raise RuntimeError(f'Conversion failed for document {number}: {result.get("Error")}')
        with zipfile.ZipFile(source) as archive:
            body=ET.fromstring(archive.read('word/document.xml'))
            original_text=' '.join(node.text or '' for node in body.iter(W+'t'))
            # Compare paragraphs through token sets to allow PDF line wrapping and hyphenation.
            original_words=set(words(original_text))
        with fitz.open(result['Output']) as pdf:
            text='\n'.join(page.get_text() for page in pdf)
            pdf_words=set(words(text))
            coverage=len(original_words&pdf_words)/len(original_words) if original_words else 1
            page_count=len(pdf);image_count=sum(len(p.get_images()) for p in pdf)
            for page in pdf:page.get_pixmap(matrix=fitz.Matrix(.15,.15),alpha=False)
            preview=dest/f'preview-{number}.png';pdf[0].get_pixmap(matrix=fitz.Matrix(.85,.85),alpha=False).save(preview)
            if page_count==0 or not text.strip() or coverage<.85:raise RuntimeError(f'Output content validation failed for document {number}: coverage {coverage:.3f}')
        unchanged=digest(source)==before
        if not unchanged:raise RuntimeError(f'Original changed: document {number}')
        evidence.append({'document':number,'source_sha256':before,'original_unchanged':unchanged,'pages':page_count,'images':image_count,'word_coverage':round(coverage,4),'conversion_exit':process.returncode})
        print(json.dumps(evidence[-1]),flush=True)
    (dest/'validation.json').write_text(json.dumps(evidence,indent=2),encoding='utf-8')
    print('Validated local document copies:',len(evidence),flush=True)

if __name__=='__main__':main()
