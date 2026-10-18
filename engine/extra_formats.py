"""Additional local formats, with one validated output per job."""
from pathlib import Path
import codecs
import io
import json
import math
import posixpath
import re
from urllib.parse import unquote, urlsplit
import zipfile
import xml.etree.ElementTree as ET

class ConversionError(Exception): pass

_catalog=Path(__file__).with_name('formats.json')
if not _catalog.exists():_catalog=Path(__file__).resolve().parents[1]/'formats.json'
CATALOG=json.loads(_catalog.read_text(encoding='utf-8'))
ODF_MIME={'odt':'application/vnd.oasis.opendocument.text','odp':'application/vnd.oasis.opendocument.presentation','ods':'application/vnd.oasis.opendocument.spreadsheet'}
REMOTE_FORMULA=re.compile(r'(?i)(?:_xlfn\.|_xlws\.)?(?:WEBSERVICE|RTD|DDE|IMAGE)\s*\(')
RTF_CONTROL=re.compile(rb'([a-zA-Z]+)(-?\d+)? ?')

def checked_xml(data):
    if b'<!DOCTYPE' in data.upper() or b'<!ENTITY' in data.upper():raise ConversionError('Documento contém uma declaração XML não permitida.')
    return ET.fromstring(data)

def check_package(archive):
    entries=archive.infolist()
    if len(entries)>30000 or sum(e.file_size for e in entries)>2*1024**3:raise ConversionError('Documento excede o limite de 2 GB descompactados ou 30 mil entradas.')
    if len({e.filename for e in entries})!=len(entries):raise ConversionError('Documento contém entradas duplicadas no pacote.')
    if archive.testzip():raise ConversionError('O documento contém dados corrompidos.')
    return entries

def validate_xlsx(path):
    try:
        with zipfile.ZipFile(path) as archive:
            entries=check_package(archive); names=archive.namelist()
            if not {'[Content_Types].xml','xl/workbook.xml','xl/_rels/workbook.xml.rels'}<=set(names):raise ConversionError('Estrutura XLSX incompleta.')
            if any(n.startswith('xl/externalLinks/') or n.lower().endswith('vbaproject.bin') for n in names):raise ConversionError('A planilha contém vínculos externos ou macros. Use uma cópia autossuficiente sem macros.')
            workbook=checked_xml(archive.read('xl/workbook.xml'))
            if not any(node.tag.rsplit('}',1)[-1]=='sheet' for node in workbook.iter()):raise ConversionError('A planilha não contém abas.')
            if not any(n.startswith('xl/worksheets/') and n.endswith('.xml') for n in names):raise ConversionError('As células da planilha estão ausentes.')
            for entry in entries:
                if not entry.filename.endswith(('.xml','.rels')):continue
                root=checked_xml(archive.read(entry))
                if entry.filename=='xl/connections.xml' and len(root):raise ConversionError('A planilha depende de conexões de dados externas.')
                for node in root.iter():
                    if node.tag.rsplit('}',1)[-1]=='Relationship' and node.get('TargetMode','').lower()=='external' and not node.get('Type','').endswith('/hyperlink'):
                        raise ConversionError('A planilha contém conteúdo externo não incorporado.')
                    if node.tag.rsplit('}',1)[-1]=='f' and REMOTE_FORMULA.search(node.text or ''):raise ConversionError('A planilha contém uma fórmula que consulta recursos externos.')
    except ConversionError:raise
    except Exception as exc:raise ConversionError('Não foi possível validar a planilha XLSX: arquivo inválido, corrompido ou protegido por senha.') from exc

def validate_odf(path,kind,expected_slides=None):
    try:
        with zipfile.ZipFile(path) as archive:
            entries=check_package(archive); names=set(archive.namelist())
            if not {'mimetype','content.xml','META-INF/manifest.xml'}<=names or archive.read('mimetype').decode('ascii').strip()!=ODF_MIME[kind]:
                raise ConversionError('Estrutura OpenDocument incompleta ou extensão incorreta.')
            if any(n.lower().startswith(('basic/','scripts/')) for n in names):raise ConversionError('Documento OpenDocument contém macros ou scripts.')
            slides=0
            for entry in entries:
                if not entry.filename.endswith('.xml'):continue
                root=checked_xml(archive.read(entry))
                for node in root.iter():
                    local=node.tag.rsplit('}',1)[-1]
                    if local=='encryption-data':raise ConversionError('Documento OpenDocument protegido por senha.')
                    if local in ('event-listener','script','dde-source','dde-link'):raise ConversionError('Documento contém scripts ou vínculos de dados ativos.')
                    if entry.filename=='content.xml' and node.tag=='{urn:oasis:names:tc:opendocument:xmlns:drawing:1.0}page':slides+=1
                    for key,value in node.attrib.items():
                        attribute=key.rsplit('}',1)[-1]
                        if attribute=='formula' and (REMOTE_FORMULA.search(value) or re.search(r"\[\s*'[^']*'\s*#",value)):
                            raise ConversionError('A planilha contém uma fórmula que consulta recursos externos.')
                        if key=='{http://www.w3.org/XML/1998/namespace}base' and value not in ('','.','./'):raise ConversionError('Documento contém uma base de recursos externa.')
                        if attribute not in ('href','src') or not value or value.startswith('#'):continue
                        if local=='a' and node.tag.startswith(('{urn:oasis:names:tc:opendocument:xmlns:text:1.0}','{urn:oasis:names:tc:opendocument:xmlns:drawing:1.0}')):continue
                        link=unquote(value);parsed=urlsplit(link)
                        normalized=posixpath.normpath(parsed.path)
                        internal=not parsed.scheme and not parsed.netloc and not link.startswith(('/','\\')) and '\\' not in link and normalized!='..' and not normalized.startswith('../')
                        internal=internal and (normalized in names or any(n.startswith(normalized.rstrip('/')+'/') for n in names))
                        if not internal:raise ConversionError('Documento contém imagem ou conteúdo externo não incorporado. Incorpore o recurso antes de converter offline.')
            if kind=='odp' and (slides==0 or expected_slides is not None and slides!=expected_slides):raise ConversionError('A apresentação OpenDocument não contém todos os slides esperados.')
            return slides
    except ConversionError:raise
    except Exception as exc:raise ConversionError('Não foi possível validar o OpenDocument: arquivo inválido, corrompido ou protegido por senha.') from exc

def validate_rtf(path,check_active=True):
    data=path.read_bytes()
    if not data.lstrip().startswith(b'{\\rtf'):raise ConversionError('O arquivo não é um RTF válido.')
    depth=0;i=0
    while i<len(data):
        char=data[i]
        if char==123:depth+=1;i+=1
        elif char==125:
            depth-=1;i+=1
            if depth<0:raise ConversionError('Estrutura RTF inválida.')
        elif char==92:
            i+=1
            if i>=len(data):raise ConversionError('Estrutura RTF incompleta.')
            if data[i] in b'{}\\':i+=1;continue
            if data[i]==39:
                if not re.fullmatch(b'[0-9a-fA-F]{2}',data[i+1:i+3]):raise ConversionError('Codificação RTF inválida.')
                i+=3;continue
            match=RTF_CONTROL.match(data,i)
            if not match:i+=1;continue
            word=match[1].lower();i=match.end()
            if check_active and word in (b'object',b'objdata',b'field',b'filetbl'):
                raise ConversionError('O RTF contém campos ou objetos ativos. Salve uma cópia DOCX no Office antes de converter offline.')
            if word==b'bin':
                length=int(match[2] or b'-1')
                if length<0 or i+length>len(data):raise ConversionError('Conteúdo binário RTF incompleto.')
                i+=length
        else:i+=1
    if depth!=0:raise ConversionError('Estrutura RTF incompleta.')

def read_text(path):
    data=path.read_bytes()
    if len(data)>32*1024**2:raise ConversionError('O arquivo TXT excede o limite de 32 MB.')
    try:
        if data.startswith((codecs.BOM_UTF16_LE,codecs.BOM_UTF16_BE)):text=data.decode('utf-16')
        else:
            try:text=data.decode('utf-8-sig')
            except UnicodeDecodeError:text=data.decode('cp1252')
    except UnicodeError as exc:raise ConversionError('Não foi possível ler a codificação do TXT. Use UTF-8, UTF-16 com BOM ou Windows-1252.') from exc
    if '\x00' in text or not text.strip():raise ConversionError('O TXT está vazio ou contém dados binários.')
    return text

def normalized_text(source,job):
    output=job/'input-utf8.txt';output.write_text(read_text(source),encoding='utf-8-sig')
    return output

def validate_text(path):
    try:
        text=path.read_text(encoding='utf-8-sig')
        if not text.strip() or '\x00' in text:raise ValueError('No text')
    except (ValueError,OSError,UnicodeError) as exc:raise ConversionError('A conversão não recuperou texto. Nenhum TXT vazio foi publicado.') from exc

def pdf_text(source,output,runtime,job,ocr,open_pdf,ocr_document,warning):
    converted=source
    with open_pdf(source) as pdf:
        scanned=[i for i,p in enumerate(pdf) if len(p.get_text().strip())<5 and p.get_images()]
        if scanned:
            if not ocr:raise ConversionError('O PDF contém páginas digitalizadas. Ative o OCR local nas configurações e tente novamente.')
            converted=ocr_document(pdf,runtime,job,scanned)
    with open_pdf(converted) as pdf:
        text='\n\n'.join(page.get_text(sort=True).strip() for page in pdf)
        if not text.strip():raise ConversionError('Não foi encontrado texto recuperável no PDF.')
        output.write_text(text+'\n',encoding='utf-8-sig')
        pages=len(pdf)
    warning('TXT contém somente texto. Imagens, formatação e layout do PDF não são preservados; revise a ordem de leitura.')
    return pages

IMAGE_FORMATS={'png':'PNG','jpg':'JPEG','webp':'WEBP','bmp':'BMP','tiff':'TIFF'}

def image_frames(source):
    from PIL import Image,ImageOps
    frames=[]
    try:
        with Image.open(source) as image:
            if image.format not in set(IMAGE_FORMATS.values()):raise ConversionError('Formato de imagem não suportado.')
            count=getattr(image,'n_frames',1)
            if count>500:raise ConversionError('A imagem excede o limite de 500 páginas ou quadros.')
            total=0
            for index in range(count):
                image.seek(index);pixels=image.width*image.height;total+=pixels
                if pixels>40000000 or total>120000000:raise ConversionError('A imagem excede o limite de 40 megapixels por página ou 120 megapixels no total.')
                frame=ImageOps.exif_transpose(image)
                mode='RGBA' if 'A' in frame.getbands() or 'transparency' in frame.info else 'RGB'
                converted=frame.convert(mode);frame.close()
                frames.append(converted)
            dpi=image.info.get('dpi',(96,96))
            try:
                dpi=tuple(float(v) for v in dpi)
                if len(dpi)!=2 or not all(math.isfinite(v) and 10<=v<=2400 for v in dpi):dpi=(96,96)
            except (ValueError,TypeError):dpi=(96,96)
        return frames,dpi
    except Exception as exc:
        for frame in frames:frame.close()
        if isinstance(exc,ConversionError):raise
        raise ConversionError('Não foi possível abrir a imagem: arquivo inválido, corrompido ou grande demais.') from exc

def opaque(image):
    from PIL import Image
    rgb=Image.new('RGB',image.size,'white')
    if image.mode=='RGBA':rgb.paste(image,mask=image.getchannel('A'))
    else:rgb.paste(image.convert('RGB'))
    return rgb

def convert_image(source,output,target,warning,progress):
    frames,dpi=image_frames(source)
    try:
        if len(frames)>1 and target not in ('pdf','tiff'):raise ConversionError('Esta imagem tem várias páginas ou quadros. Escolha PDF ou TIFF para preservar todos; nenhum quadro foi descartado.')
        if target=='pdf':
            import pymupdf as fitz
            with fitz.open() as document:
                for index,frame in enumerate(frames):
                    progress(f'Convertendo imagem {index+1} de {len(frames)}',10+int(70*(index+1)/len(frames)))
                    width,height=frame.width*72/dpi[0],frame.height*72/dpi[1]
                    scale=min(1,14400/max(width,height));page=document.new_page(width=max(1,width*scale),height=max(1,height*scale))
                    image=io.BytesIO()
                    with opaque(frame) as rgb:rgb.save(image,format='PNG')
                    page.insert_image(page.rect,stream=image.getvalue())
                document.save(output,deflate=True)
        else:
            image=frames[0]
            if target in ('jpg','bmp'):
                if image.mode=='RGBA':warning('A transparência foi substituída por fundo branco neste formato.')
                image=opaque(image)
            try:
                options={'dpi':dpi}
                if target=='jpg':options.update(quality=95,subsampling=0,optimize=True);warning('JPG usa compressão com perda. As dimensões foram preservadas; detalhes finos podem mudar.')
                if target=='webp':options.update(lossless=True,method=4)
                if target=='tiff':options.update(compression='tiff_lzw',save_all=True,append_images=frames[1:])
                image.save(output,format=IMAGE_FORMATS[target],**options)
            finally:
                if image is not frames[0]:image.close()
        if len(frames)>1:warning('Todas as páginas ou quadros foram preservados. A duração de animações não é mantida em PDF/TIFF.')
        return len(frames)
    finally:
        for frame in frames:frame.close()

def validate_image(path,target,expected_frames):
    from PIL import Image
    try:
        with Image.open(path) as image:
            if image.format!=IMAGE_FORMATS[target] or getattr(image,'n_frames',1)!=expected_frames:raise ValueError('Image type/frame mismatch')
            for index in range(expected_frames):image.seek(index);image.load()
    except Exception as exc:raise ConversionError('Não foi possível validar a imagem convertida. Nenhum arquivo incompleto foi publicado.') from exc
