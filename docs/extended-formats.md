# Formatos adicionais — versão 1.1.0

## Escopo

A versão amplia o conversor local para documentos DOCX/ODT/RTF/TXT, apresentações PPTX/ODP, planilhas XLSX/ODS e imagens PNG/JPG/WEBP/BMP/TIFF. PDF recebe documentos, apresentações, planilhas e imagens; também pode gerar DOCX, PPTX e TXT. JPEG e TIF são aliases de entrada. `formats.json` registra **16 extensões de origem, 14 destinos e 62 pares permitidos**. A contagem inclui os aliases; não representa conversão arbitrária entre todos os formatos.

Os resultados continuam na pasta da origem, com sufixos numéricos para colisões. A cópia privada, a publicação atômica, o isolamento de falhas de lote e o cancelamento são mantidos. O visual existente foi preservado e o cartão de configurações passou a mostrar quatro grupos. O botão **Converter arquivos** abre a seleção local e oferece os destinos comuns ao lote.

## Validação

**108/108 testes pytest aprovados em 568,77 segundos**, com o host C# e o worker congelado que integram a distribuição. A suíte inclui os 26 testes anteriores, todos os **62 pares do catálogo** e 20 casos adicionais de codificação, OCR, múltiplos quadros, entradas inválidas, conteúdo externo e isolamento de falhas. Cada invocação compara o hash da origem, reabre as saídas e confere a pasta de destino.

Também passaram **14 verificações do núcleo**, **2 proteções Windows** (cancelamento e ACL sem escrita), **23 seleções IExplorerCommand e 23 IContextMenu** (16 extensões e 7 seleções mistas/incompatíveis em cada contrato), IDs de comandos, filtro de comando padrão, invocação inválida e liberação da DLL. O manifesto MSIX contém as 16 extensões e a nova classe; sua instalação moderna permanece não validada.

Após a integração final da GUI e do menu clássico, o motor foi recompilado. A comparação confirmou **281 arquivos**, todos os **11 membros do CArchive idênticos**, os mesmos bytes dos membros da biblioteca base e as mesmas bibliotecas nativas. As diferenças externas são o empacotamento do EXE/ZIP e a identidade da DLL no catálogo; as rotas e o bytecode de conversão permanecem iguais aos que passaram os 108 testes. A suíte Python completa não foi repetida depois dessa etapa; os contratos nativos foram testados na DLL final e a GUI instalada foi exercitada.

Foram inspecionadas a tela de configurações e **11 páginas PDF de 8 formatos novos**. Um DOCX gerado de ODT foi aberto no Microsoft Word sem pedido de reparo, com texto acentuado, tabela e imagem. Um XLSX gerado de ODS foi aberto no Microsoft Excel sem pedido de reparo, mostrando duas abas, valores 12 e 17 e total 29. As fórmulas e os marcadores das duas abas também foram conferidos no pacote. Isso verifica estas amostras; diferenças de estilo/fontes e documentos mais complexos continuam sujeitos a revisão.

Na interface instalada, uma imagem PNG foi escolhida no seletor, o destino JPG foi selecionado e o resultado foi publicado na mesma pasta, em 360 × 240 pixels e com fundo branco. O hash do PNG permaneceu igual. Uma segunda imagem PNG com nome acentuado foi convertida para WEBP pela API registrada do Shell, preservando transparência e origem.

**Limite visual do Explorador:** o novo item não apareceu na sessão atual. O teste de clique no menu permanece pendente, e o Explorador original não foi reiniciado por haver uma cópia em andamento. A seleção de arquivos pela janela do aplicativo está validada e permite usar todos os formatos.

Evidências: `test-results/integration.xml`, `extended-test-run.log`, `extended-routes.jsonl` e `extended-formats/validation.json`, `engine-preservation.json`, `ui-conversion.json`, `shell-invoke.json`, `settings.png`, `target-choice.png`, `result.png`, `word-review.png`, `excel-review.png`, `output-review.png`. A suíte completa foi executada no Windows 11 x64 build 26200. Não foi repetida em Windows 10, ARM64, uma máquina limpa ou com adaptador de rede desligado. A CI remota anterior passou os testes Python e as proteções Windows, mas falhou no teste COM com caminhos acentuados sob Windows PowerShell; o teste agora usa uma pasta de seleções com nomes ASCII, e o workflow passa a usar PowerShell 7. O resultado remoto da nova revisão deve ser conferido separadamente.

## Comportamento e limites

- DOCX/ODT/RTF/TXT convertem entre si e para PDF. TXT recebe somente texto; as entradas aceitam UTF-8, UTF-16 com BOM e Windows-1252, até 32 MB. RTF com campos, objetos ou tabelas de arquivos é recusado antes da abertura.
- PPTX/ODP convertem entre si e para PDF. Todos os slides, inclusive ocultos, são incluídos no PDF; as contagens são validadas antes de publicar.
- XLSX/ODS convertem entre si e para PDF usando a configuração de impressão salva. A versão não ativa `SinglePageSheets`, não força abas ocultas e não promete uma página por aba. Fórmulas e recursos específicos precisam de revisão após mudar de formato.
- PDF → TXT extrai todas as páginas e aplica OCR local nas páginas digitalizadas quando ativado. Com OCR desligado, uma digitalização sem texto é recusada com mensagem. O TXT perde imagens e layout.
- Imagens não são redimensionadas em pixels. A orientação EXIF é aplicada. JPG usa qualidade 95 e compressão com perda; WEBP usa compressão sem perda. JPG/BMP/PDF recebem fundo branco onde houver transparência. TIFF/PDF preservam todos os quadros; os demais destinos recusam entradas multipágina/animadas, evitando perda silenciosa. Não há montagem de arquivos separados em um único PDF.
- Limites de imagens: 500 quadros, 40 megapixels por quadro e 120 megapixels no total. Metadados podem ser descartados e tempos de animação não são preservados em PDF/TIFF.
- Pacotes OpenDocument/XLSX com conteúdo externo, conexões, macros, scripts ou criptografia são recusados. A aplicação continua sem upload, login ou telemetria; o teste local não equivale a uma auditoria de documentos hostis nem a uma captura de tráfego.

## Atualização e recuperação

A extensão usa `Conversor.Shell.v3.dll` e a classe COM `{EDBFA846-34E1-41EC-9FF9-144E71B1763B}`. Isso permite atualizar sem sobrescrever DLLs anteriores eventualmente mapeadas no Explorador. O instalador mantém o mesmo AppId, remove as classes/verbos antigos do aplicativo e registra um manipulador clássico que filtra as 16 extensões pelo catálogo. As chaves pertencentes ao programa são incluídas no desinstalador. Associações padrão e preferências são preservadas; o instalador não fecha aplicativos automaticamente.

As versões anteriores permanecem em seus artefatos e releases. Para voltar, desinstale a versão atual e instale o pacote anterior; os documentos e as preferências locais são preservados. O pacote moderno continua sem assinatura e não é considerado uma integração moderna instalada/testada.

O primeiro instalador de validação revelou uma falha anterior: `checkedonce` desmarcava a tarefa de menu nas atualizações. A versão final remove esse sinalizador e conserva a seleção anterior. A DLL de shell idêntica é mantida por comparação de SHA-256, para não substituir um arquivo já mapeado pelo Explorador em reparos/reinstalações. A seleção desmarcada deliberadamente continua sendo respeitada.

## Referências

Filtros de exportação: [LibreOffice — File Conversion Filter Names](https://help.libreoffice.org/latest/en-US/text/shared/guide/convertfilters.html). Configuração de PDF e planilhas: [LibreOffice — PDF Export Parameters](https://help.libreoffice.org/latest/en-US/text/shared/guide/pdf_params.html).
