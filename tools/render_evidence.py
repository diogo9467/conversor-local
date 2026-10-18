from pathlib import Path
import io
import pymupdf as fitz
from PIL import Image,ImageDraw,ImageOps

ROOT=Path(__file__).resolve().parents[1]

def sheet(pdf,output):
    with fitz.open(pdf) as document:
        cell_w=420;cell_h=640 if document[0].rect.height>document[0].rect.width else 270
        image=Image.new('RGB',(cell_w*len(document),cell_h),'#DAE1E7')
        draw=ImageDraw.Draw(image)
        for index,page in enumerate(document):
            pix=page.get_pixmap(matrix=fitz.Matrix(1,1),alpha=False)
            rendered=Image.open(io.BytesIO(pix.tobytes('png')))
            rendered.thumbnail((cell_w-20,cell_h-40))
            image.paste(rendered,(cell_w*index+(cell_w-rendered.width)//2,30))
            draw.text((cell_w*index+10,10),f'Page {index+1}',fill='black')
        image.save(output)

if __name__=='__main__':
    a=ROOT/'test-results'/'fixtures'/'Pasta A com acentos'
    b=ROOT/'test-results'/'fixtures'/'Outra pasta'
    sheet(next(a.glob('Artigo*.pdf')),ROOT/'test-results'/'docx-to-pdf.png')
    sheet(next(b.glob('Semin*pdf')),ROOT/'test-results'/'pptx-to-pdf.png')
