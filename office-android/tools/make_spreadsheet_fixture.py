#!/usr/bin/env python3
"""Write a small synthetic workbook: two sheets, formulas and a formatted header."""
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED

TARGET = Path(__file__).resolve().parents[1] / "proof/assets/calculos.xlsx"
NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
REL = "http://schemas.openxmlformats.org/package/2006/relationships"
OFFICE_REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
parts = {
    "[Content_Types].xml": '''<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
<Override PartName="/xl/worksheets/sheet2.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
</Types>''',
    "_rels/.rels": f'<Relationships xmlns="{REL}"><Relationship Id="rId1" Type="{OFFICE_REL}/officeDocument" Target="xl/workbook.xml"/></Relationships>',
    "xl/workbook.xml": f'''<workbook xmlns="{NS}" xmlns:r="{OFFICE_REL}"><sheets>
<sheet name="Totais" sheetId="1" r:id="rId1"/><sheet name="Conferência" sheetId="2" r:id="rId2"/>
</sheets><calcPr calcId="0" fullCalcOnLoad="1"/></workbook>''',
    "xl/_rels/workbook.xml.rels": f'''<Relationships xmlns="{REL}">
<Relationship Id="rId1" Type="{OFFICE_REL}/worksheet" Target="worksheets/sheet1.xml"/>
<Relationship Id="rId2" Type="{OFFICE_REL}/worksheet" Target="worksheets/sheet2.xml"/>
<Relationship Id="rId3" Type="{OFFICE_REL}/styles" Target="styles.xml"/></Relationships>''',
    "xl/styles.xml": f'''<styleSheet xmlns="{NS}">
<fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="12"/><name val="Calibri"/></font></fonts>
<fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills>
<borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>
<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
<cellXfs count="2"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/><xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1"/></cellXfs>
<cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles></styleSheet>''',
    "xl/worksheets/sheet1.xml": f'''<worksheet xmlns="{NS}"><sheetData>
<row r="1"><c r="A1"><v>12</v></c><c r="B1" s="1" t="inlineStr"><is><t>Planejamento com ação</t></is></c></row>
<row r="2"><c r="A2"><v>8</v></c></row><row r="3"><c r="A3"><f>SUM(A1:A2)</f><v>20</v></c></row>
</sheetData></worksheet>''',
    "xl/worksheets/sheet2.xml": f'''<worksheet xmlns="{NS}"><sheetData><row r="1">
<c r="A1" t="inlineStr"><is><t>Conferência</t></is></c><c r="B1"><f>Totais!A3</f><v>20</v></c>
</row></sheetData></worksheet>''',
}
TARGET.parent.mkdir(parents=True, exist_ok=True)
with ZipFile(TARGET, "w", ZIP_DEFLATED) as target:
    for name, xml in parts.items():
        target.writestr(name, '<?xml version="1.0" encoding="UTF-8"?>' + xml)
print(TARGET)
