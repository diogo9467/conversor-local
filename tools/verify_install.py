"""Read-only comparison of a installed payload with the built payload."""
from pathlib import Path
import argparse
import hashlib
import json

ROOT=Path(__file__).resolve().parents[1]
def digest(path):
    h=hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda:stream.read(1024*1024),b''):h.update(block)
    return h.hexdigest()

def main():
    parser=argparse.ArgumentParser();parser.add_argument('installed');args=parser.parse_args()
    built=ROOT/'.build'/'app';installed=Path(args.installed).resolve()
    missing=[];different=[];count=0;size=0
    for path in built.rglob('*'):
        if not path.is_file():continue
        relative=path.relative_to(built);target=installed/relative
        if not target.exists():missing.append(str(relative));continue
        if path.stat().st_size!=target.stat().st_size or digest(path)!=digest(target):different.append(str(relative))
        count+=1;size+=path.stat().st_size
    result={'installed':str(installed),'files_compared':count,'bytes_compared':size,'missing':missing,'different':different}
    report=ROOT/'test-results'/'installed-payload.json';report.parent.mkdir(exist_ok=True);report.write_text(json.dumps(result,indent=2),encoding='utf-8')
    print(json.dumps(result,ensure_ascii=False,indent=2))
    return 1 if missing or different else 0

if __name__=='__main__':raise SystemExit(main())
