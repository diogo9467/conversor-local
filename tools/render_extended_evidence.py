"""Render representative new PDF outputs from the route-test manifest."""
from pathlib import Path
import io
import json
import pymupdf as fitz
from PIL import Image, ImageDraw

ROOT=Path(__file__).resolve().parents[1]
DEST=ROOT/'test-results/extended-formats'


def main():
    routes=[json.loads(line) for line in (ROOT/'test-results/extended-routes.jsonl').read_text(encoding='utf-8').splitlines()]
    samples=[]
    for ext in ['odt','rtf','txt','odp','xlsx','ods','png','tiff']:
        entry=next(item for item in reversed(routes) if Path(item['source']).suffix=='.'+ext and item['target']=='pdf')
        with fitz.open(entry['output']) as pdf:
            for index,page in enumerate(pdf):
                pix=page.get_pixmap(matrix=fitz.Matrix(1.3,1.3),alpha=False)
                rendered=Image.open(io.BytesIO(pix.tobytes('png'))).convert('RGB')
                samples.append((f'{ext.upper()} -> PDF / page {index+1}',rendered))
    cell_w=480;cell_h=390;columns=3;rows=(len(samples)+columns-1)//columns
    sheet=Image.new('RGB',(cell_w*columns,cell_h*rows),'#E4EAF2');draw=ImageDraw.Draw(sheet)
    for n,(label,rendered) in enumerate(samples):
        col=n%columns;row=n//columns
        rendered.thumbnail((cell_w-28,cell_h-48))
        draw.text((col*cell_w+14,row*cell_h+12),label,fill='#163D61')
        sheet.paste(rendered,(col*cell_w+(cell_w-rendered.width)//2,row*cell_h+34))
    DEST.mkdir(exist_ok=True,parents=True);sheet.save(DEST/'output-review.png')
    print(f'Rendered {len(samples)} pages from 8 new input formats.')


if __name__=='__main__':main()
