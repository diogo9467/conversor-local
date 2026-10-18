# Arquitetura e contratos

## Contrato de publicação

1. A origem é normalizada pelo .NET e precisa existir como arquivo.
2. O destino é exclusivamente `Path.GetDirectoryName(origem)`.
3. Um arquivo de sondagem com nome aleatório testa a escrita nessa pasta e se apaga ao fechar.
4. A origem é lida com `FileShare.Read` e copiada para um trabalho privado com nome curto.
5. O worker nunca recebe a pasta original nem decide o nome final.
6. O motor valida o formato de entrada; executa a conversão; reabre a saída.
7. PDF: todas as páginas são abertas e renderizadas em miniatura; PPTX: slides, XML e imagens são reabertos; DOCX: XML, texto, tabelas e imagens são reabertos. PPTX → PDF e PDF → PPTX verificam também a contagem de páginas/slides.
8. Só depois do sinal de sucesso, código de saída zero e arquivo não vazio, o host copia os bytes para `.conversor-<id>.partial` ao lado da origem, faz `Flush(true)` e chama `File.Move(overwrite:false)`.
9. A tentativa de renomear arbitra colisões entre processos. Se outro processo vencer, o próximo sufixo é tentado. Não existe janela de sobrescrita entre testar e publicar.
10. A publicação é o ponto de confirmação. Cancelamento recebido depois dele não transforma um resultado válido em falha.
11. A pasta recebe `SHCNE_CREATE` e `SHCNE_UPDATEDIR` sem alteração de visualização, ordenação ou foco.

## Catálogo e formatos adicionais

`formats.json` define as rotas usadas pelo núcleo C#, worker Python, tela de configurações e extensão COM. `tools/catalog.py` valida o catálogo e gera o cabeçalho C++, as chaves Inno Setup e o manifesto MSIX em `.build/generated`. Isso impede oferecer uma conversão no menu que o host não aceite. A DLL usa a interseção dos destinos para seleções mistas.

Writer converte DOCX/ODT/RTF/TXT; Impress converte PPTX/ODP; Calc converte XLSX/ODS. Os filtros são explícitos. O perfil privado desliga macros e atualização de vínculos. TXT de entrada é normalizado em outra cópia UTF-8, sem modificar o snapshot. TXT de PDF extrai todas as páginas em ordem, usando o OCR por página quando necessário.

OpenDocument e XLSX são validados por estrutura ZIP/XML, tipo, integridade, limites, scripts/macros e vínculos. RTF é percorrido respeitando escapes, grupos e blocos binários; campos/objetos ativos são recusados na entrada. Na saída, RTF e TXT são reabertos e validados. Imagens usam Pillow, com orientação EXIF aplicada e limites por quadro/total. TIFF/PDF preservam todos os quadros; destinos estáticos recusam entradas multipágina. O PDF de imagem usa o DPI salvo (96 quando ausente/inválido), sem reduzir os pixels, limitando o tamanho físico da página a 200 polegadas.

Os filtros seguem a [documentação oficial do LibreOffice](https://help.libreoffice.org/latest/en-US/text/shared/guide/convertfilters.html). A exportação de planilhas mantém a configuração de impressão; `SinglePageSheets` não é ativado.

## Protocolo do motor

O executável recebe `--request <job/request.json>`. O JSON contém o snapshot, a saída temporária, o formato, o diretório dos motores e a preferência OCR. `stdout` é reservado para JSONL: `progress`, `warning`, `error`, `result`. Logs de bibliotecas vão para `stderr`. O host não interpreta texto comum como sucesso. O resultado final de lote é opcionalmente gravado por `--report`, utilizado nos testes.

Cancelamento mata a árvore de processos. O Job Object tem `JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE`. Falha em um arquivo retorna um resultado e o próximo arquivo é processado. O limite do processo é 30 minutos; o LibreOffice tem limite interno menor. Uma invocação do shell grava a seleção em JSON UTF-8 para evitar os limites de tamanho/escape de argumentos de lotes grandes.

## PDF para Word e OCR

pdf2docx 0.5.13 está fixado. Seu backend de página é registrado temporariamente por trabalho: páginas nativas usam o modo normal com texto, imagens e tabelas; páginas com OCR usam o modo 2 para recuperar texto invisível. Isso evita descartar OCR e preserva páginas nativas em PDFs mistos. A regressão `test_ocr_engine.py` verifica um documento misto.

O reconhecimento usa Tesseract incorporado no MuPDF e modelos `tessdata_fast/4.1.0` por/eng, sem executar comandos obtidos do documento. A qualidade depende da digitalização; texto manuscrito, matemática, tabelas raster e baixa resolução exigem revisão.

## Fronteiras de segurança

Nenhum comando é montado a partir de conteúdo do documento. `ProcessStartInfo.ArgumentList` e `subprocess.run(list)` preservam espaços/caracteres especiais. A DLL de shell serializa caminhos em JSON e passa somente o arquivo de fila ao host. O registro altera apenas chaves próprias sob HKCU; não substitui ProgIDs ou associações padrão.

OOXML é lido sem extrair entradas ZIP na origem. XML com DTD/entidades é recusado; pacotes têm limites de expansão e entradas. Modelos anexos do Word são removidos apenas do snapshot antes de abrir no LibreOffice: alteram-se somente `word/settings.xml` e suas relações, mantendo os bytes das partes de conteúdo, estilos, tabelas e imagens. O DOM preserva os prefixos referidos por `mc:Ignorable`. Nenhum modelo é buscado em disco, UNC ou rede. Outras relações externas que não sejam hiperlinks continuam recusadas. Macros não são executadas. PDFs protegidos/corrompidos são recusados antes de gerar saída pública. O snapshot impede que um motor grave no documento original.

As dependências de Office são cópias privadas oficiais. As DLLs VC++ que o MSI distribuiria em System32 são copiadas para `libreoffice/program` (app-local), para não exigir runtime previamente instalado nem privilégios administrativos. Fonts livres do LibreOffice ficam disponíveis no runtime privado; fontes comerciais do documento não são distribuídas.

## Atualização

- Atualize primeiro versões fixadas e os fontes/avisos correspondentes.
- Recompile o worker sempre que mudar `worker.py`; o instalador usa o worker congelado.
- Repita testes afetados, instalação/desinstalação e inspeção visual. Testes de função isolada não validam o congelamento do Python.
- Para produção com menu moderno, assine EXE/DLL/MSIX e configure o mesmo Publisher no manifesto de aplicação, pacote e certificado. Nunca publique uma chave privada.
