"""Narrow compatibility fixes to the pinned MuPDF/Extract DOCX writer.

No layout algorithm is introduced. Pristine files come from the verified archive.
The upstream native engine keeps text ordering, paragraph and table reconstruction.
"""
from pathlib import Path

def once(text, old, new):
    assert text.count(old) == 1, f'Pinned MuPDF source changed: {old[:90]!r}'
    return text.replace(old, new, 1)

def writer(text):
    start = text.index('static void dev_fill_image(')
    end = text.index('/*\n * Support for sending information', start)
    image = r'''static void dev_fill_image(fz_context *ctx, fz_device *dev_, fz_image *img, fz_matrix ctm, float alpha, fz_color_params color_params)
{
    fz_docx_device *dev = (fz_docx_device*) dev_;
    fz_buffer *png = NULL;
    fz_pixmap *pix = NULL, *mask = NULL, *combined = NULL;
    unsigned char *data = NULL;
    fz_rect box = fz_transform_rect(fz_unit_rect, ctm);
    fz_var(png); fz_var(pix); fz_var(mask); fz_var(combined); fz_var(data);
    assert(!dev->writer->ctx);
    dev->writer->ctx = ctx;
    fz_try(ctx)
    {
        if (img->w <= 0 || img->h <= 0 || (uint64_t)img->w * img->h > 16000000)
            fz_throw(ctx, FZ_ERROR_LIMIT, "Imagem do PDF excede 16 megapixels");
        pix = fz_get_unscaled_pixmap_from_image(ctx, img);
        if (img->mask)
        {
            mask = fz_get_unscaled_pixmap_from_image(ctx, img->mask);
            combined = fz_new_pixmap_from_color_and_mask(ctx, pix, mask);
            fz_drop_pixmap(ctx, pix); pix = combined; combined = NULL;
        }
        png = fz_new_buffer_from_pixmap_as_png(ctx, pix, color_params);
        size_t size = fz_buffer_storage(ctx, png, &data);
        data = NULL;
        if (size > 128 * 1024 * 1024 - dev->writer->image_bytes)
            fz_throw(ctx, FZ_ERROR_LIMIT, "Imagens do PDF excedem 128 MB");
        size = fz_buffer_extract(ctx, png, &data);
        dev->writer->image_bytes += size;
        /* Transfer a fresh PNG; never consume the PDF's shared image buffer. */
        int error = extract_add_image(dev->writer->extract, "png", box.x0, box.y0,
            sqrtf(ctm.a * ctm.a + ctm.b * ctm.b), sqrtf(ctm.c * ctm.c + ctm.d * ctm.d),
            data, size, writer_image_free, dev->writer);
        data = NULL;
        if (error) fz_throw(ctx, FZ_ERROR_LIBRARY, "Failed to add PNG image");
    }
    fz_always(ctx)
    {
        fz_free(ctx, data);
        fz_drop_buffer(ctx, png);
        fz_drop_pixmap(ctx, combined); fz_drop_pixmap(ctx, mask); fz_drop_pixmap(ctx, pix);
        dev->writer->ctx = NULL;
    }
    fz_catch(ctx) { fz_rethrow(ctx); }
}

'''
    text = text[:start] + image + text[end:]
    text = once(text, '\tint images;\n', '\tint images;\n\tsize_t image_bytes;\n')
    block = '\n\t\tif (extract_process(writer->extract, writer->spacing, writer->rotation, writer->images))\n\t\t\tfz_throw(ctx, FZ_ERROR_LIBRARY, "Failed to process page");'
    text = once(text, block, '')
    # Processing per page discarded earlier pages' image relations. Process the
    # complete native document once, under an explicit image-memory limit.
    index = text.index('static void writer_close(')
    end = text.index('static void writer_drop(', index)
    text = text[:index] + once(text[index:end], '\tfz_try(ctx)\n\t{\n', '\tfz_try(ctx)\n\t{\n\t\tif (extract_process(writer->extract, writer->spacing, writer->rotation, writer->images))\n\t\t\tfz_throw(ctx, FZ_ERROR_LIBRARY, "Failed to process document");\n') + text[end:]
    text = once(text, '\t\twriter->output = out;', '\t\twriter->output = out;\n\t\twriter->image_bytes = 0;')
    return text

def docx(text):
    old = 'snprintf(font_size_text, sizeof(font_size_text), "%f", content_state->font.size * 2);'
    assert text.count(old) == 2
    text = text.replace(old, old.replace('"%f"', '"%.0f"'))
    start = text.index('docx_append_image(')
    brace = text.index('{', start)
    text = text[:brace+1] + '\n\tint cx = (int)(image->w * 12700 + 0.5);\n\tint cy = (int)(image->h * 12700 + 0.5);\n' + text[brace+1:]
    lines = text.splitlines(keepends=True)
    for i, line in enumerate(lines):
        if '//extract_astring_cat(' in line and '<wp:extent cx=' in line:
            lines[i] = '\textract_astring_catf(alloc, output, "             <wp:extent cx=\\"%i\\" cy=\\"%i\\"/>\\n", cx, cy);\n'
        if '//extract_astring_cat(' in line and '<a:ext cx=\\"2933700' in line:
            lines[i] = '\textract_astring_catf(alloc, output, "                       <a:ext cx=\\"%i\\" cy=\\"%i\\"/>\\n", cx, cy);\n'
        if '<wp:docPr id=\\"1\\" name=\\"Picture 1' in line:
            lines[i] = '\textract_astring_catf(alloc, output, "             <wp:docPr id=\\"%s\\" name=\\"Picture %s\\"/>\\n", image->id + 3, image->id + 3);\n'
    result = ''.join(lines)
    assert 'wp:extent cx=\\"%i' in result and 'a:ext cx=\\"%i' in result
    return result

def zip_headers(text):
    # Every member is deflated and uses a trailing data descriptor. The central
    # directory must carry the SAME bit 3 as the local header (ZIP APPNOTE).
    text = once(text, 'zip->general_purpose_bit_flag = 0;', 'zip->general_purpose_bit_flag = 8;')
    return once(text, 'zip->version_extract = 10;              /* 10 means 1.0. */', 'zip->version_extract = 20;              /* Deflate needs ZIP 2.0. */')

PATCHES = {'source/fitz/output-docx.c': writer, 'thirdparty/extract/src/docx.c': docx,
           'thirdparty/extract/src/zip.c': zip_headers}

def apply(archive, source: Path):
    members = {m.name.split('/', 1)[1]: m for m in archive.getmembers() if '/' in m.name and m.isfile()}
    # Keep the unused ODT exporter pristine in an existing build cache as well.
    names = set(PATCHES) | {'thirdparty/extract/src/odt.c'}
    for name in names:
        pristine = archive.extractfile(members[name]).read().decode('utf-8')
        modified = PATCHES[name](pristine) if name in PATCHES else pristine
        dest = source / name
        if not dest.exists() or dest.read_text(encoding='utf-8') != modified:
            dest.write_text(modified, encoding='utf-8', newline='\n')
