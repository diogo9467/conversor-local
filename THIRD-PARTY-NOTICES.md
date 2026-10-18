# Componentes de terceiros

O código próprio é distribuído sob AGPL-3.0-or-later (`LICENSE`). Não foi reutilizado código do File Converter. Os componentes abaixo são distribuídos sem modificações em seus binários; o aplicativo utiliza suas interfaces públicas.

| Componente | Versão | Licença / fonte |
| --- | --- | --- |
| .NET Windows Desktop | 10.0.13 | MIT e avisos de terceiros; https://github.com/dotnet/runtime e https://github.com/dotnet/winforms |
| LibreOffice | 26.2.6 | MPL-2.0 / LGPL-3.0+ e licenças de terceiros; licença completa em `libreoffice/LICENSE.html`, `license.txt`, `NOTICE` |
| PyMuPDF | 1.27.2.3 | AGPL-3.0; fonte exata incluída em `sources/third-party` |
| MuPDF, incluindo Tesseract incorporado | 1.27.2 | AGPL-3.0 e avisos de terceiros; fonte com dependências incluída em `sources/third-party` |
| pdf2docx | 0.5.13 | MIT; https://pypi.org/project/pdf2docx/0.5.13/ |
| CPython | 3.12.14 | PSF e avisos; `licenses/Python-LICENSE.txt`; https://www.python.org/downloads/source/ |
| python-docx | 1.2.0 | MIT; avisos e metadados incluídos |
| python-pptx | 1.0.2 | MIT; avisos e metadados incluídos |
| Pillow | 12.2.0 | MIT-CMU e componentes; avisos incluídos |
| OpenCV headless | 4.13.0.92 | Apache-2.0 e componentes; avisos incluídos |
| NumPy | 2.2.6 | BSD-3-Clause e bibliotecas numéricas; avisos incluídos |
| Modelos Tesseract eng/por | tessdata_fast 4.1.0 | Apache-2.0; `ocr/LICENSE`; https://github.com/tesseract-ocr/tessdata_fast/tree/4.1.0 |
| PyInstaller bootloader | 6.22.3 | GPL com exceção de distribuição; https://pyinstaller.org/en/stable/license.html |
| GCC/MinGW runtime estático da DLL shell | w64devkit 2.10.1 / GCC 16.2.0 | GPL com GCC Runtime Library Exception e permissões MinGW; avisos incluídos |

FontTools, lxml, fire, XlsxWriter, typing_extensions, termcolor e bibliotecas transitivas mantêm seus próprios avisos em `licenses`. O manifesto instalado `dependency-manifest.json` registra as versões/links exatos do motor. Os fontes Python próprios e scripts de compilação acompanham o instalador em `sources/ConversorLocal-source.zip`. A fonte exata do PyMuPDF e a árvore fonte completa do MuPDF com suas dependências estão incluídas para redistribuição AGPL.

Fonte do LibreOffice sem alterações: [arquivo oficial da versão 26.2.6](https://download.documentfoundation.org/libreoffice/src/26.2.6/). A Mozilla Public License 2.0 permite o fornecimento dos fontes por endereço acessível; consulte também os termos de cada componente em `libreoffice/LICENSE.html`. Para qualquer redistribuição modificada, atualize os avisos e disponibilize os fontes correspondentes exigidos pelas licenças.

O instalador foi compilado com Inno Setup 7.1.0 (uso gratuito não comercial permitido pelo fornecedor); a ferramenta de compilação não precisa ser instalada pelo usuário. MakeAppx/SignTool são ferramentas de compilação do Windows SDK, não motores necessários para converter documentos.
