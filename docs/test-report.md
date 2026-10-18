# Relatório de testes — 18/10/2026

Ambiente real: Windows 11 x64, build 26200; .NET SDK 10.0.402 / runtime 10.0.13; Python de compilação 3.12.14; motores empacotados sem exigir Python/LibreOffice instalados pelo usuário. Os arquivos usados são sintéticos e estão definidos em `tests/make_fixtures.py`.

## Resultados automatizados

Execução final de `tools/test.ps1`: **15 testes pytest aprovados**, **10 verificações do núcleo aprovadas**, **2 verificações específicas do Windows aprovadas**, **4 verificações COM nativas aprovadas**. Evidência de execução: `test-results/integration.xml`; duração dos 15 testes: 86,31 segundos. O relatório XML registra somente os testes pytest; os demais resultados constam na saída dos respectivos executáveis/scripts.

| Caso efetivamente executado | Resultado |
| --- | --- |
| DOCX com três páginas, cabeçalho/rodapé, texto, tabela e imagem | PDF reaberto; três páginas; marcadores, tabela e imagens encontrados |
| PPTX 16:9 com três layouts, tabela, imagens e um slide oculto | PDF reaberto; três slides exportados, inclusive o oculto; proporção 16:9 |
| PDF textual de três páginas com tabelas/imagens → DOCX | Texto editável nas três páginas, tabela e mídias recuperados |
| PDF de três páginas → PPTX | Três slides, uma imagem por página, imagens com mais de 1.000 pixels de largura |
| PDF digitalizado → DOCX com OCR | Texto reconhecido, editável e aviso OCR presente |
| PDF misto: página textual + página digitalizada | Texto de ambas recuperado; imagem da página nativa preservada |
| OCR desativado em PDF digitalizado | Falha clara pedindo ativação; nenhum DOCX publicado |
| PDF com senha / PDF inválido / DOCX inválido | Falhas isoladas com mensagem; nenhum documento incompleto publicado |
| Lote com PDF inválido seguido de PDF válido | Primeiro falha; segundo é convertido |
| Arquivos em pastas diferentes e nomes com espaços/acentos | Destino sempre no diretório exato da respectiva origem |
| Caminho com mais de 260 caracteres e nome com `$`, `&`, `[ ]`, `#` | Conversão concluída na mesma pasta |
| Destino já existente | Bytes existentes preservados; resultado recebe sufixo numérico |
| Duas conversões simultâneas do mesmo PDF | Dois resultados íntegros, com nomes distintos |
| Dezesseis publicações concorrentes no núcleo | Nenhuma sobrescrita; dezesseis nomes distintos e bytes completos |
| Cancelamento de conversão real | Worker interrompido; somente o original permanece |
| Diretório com ACL Windows negando criação de arquivo | Erro de permissão explícito; nenhum salvamento alternativo |
| Arquivo/diretório no nome de destino | Arquivo preservado; diretório não substituído |
| Limpeza após êxito, erro e cancelamento | Nenhum `.partial`/`.probe` encontrado nas pastas de teste |
| Integridade da origem | SHA-256 da origem conferido antes/depois de cada conversão de integração |
| COM nativa | DLL carregada/descarregada; PDFs oferecem dois destinos; DOCX/PPTX oferecem só PDF |

## Inspeção visual e aplicativos convencionais

- PDFs de Word e PowerPoint renderizados e inspecionados em `test-results/docx-to-pdf.png` e `test-results/pptx-to-pdf.png`: títulos, tabelas, imagens, cabeçalhos/rodapés, páginas/slides e proporções íntegros nas amostras.
- DOCX recuperado do PDF aberto no **Microsoft Word instalado**, sem pedido de reparo: três páginas, 138 palavras, tabelas e imagens identificadas, texto editável na superfície do documento.
- PPTX gerado do PDF aberto no **Microsoft PowerPoint instalado**, sem pedido de reparo: três slides, primeira página inteira no slide e uma imagem por slide; barra de status “Slide 1 de 3”.
- Janela de configurações aberta e inspecionada no Windows: texto legível, checkbox OCR e botão Salvar sem cortes.

## Instalação e Explorador

O instalador EXE foi executado para o usuário atual e retornou **0**, sem pedir reinicialização. Foram comparados por tamanho e SHA-256 **19.985 arquivos instalados**, totalizando **2.080.799.320 bytes**: nenhum ausente e nenhuma diferença. Evidência: `test-results/installed-payload.json`.

Uma conversão PDF → DOCX foi executada com o host e os motores na pasta instalada `%LOCALAPPDATA%/Programs/ConversorLocal`, retornando **0**. Isso confirma a execução após mudar o local dos binários. Evidência: `test-results/relocated-conversion.json`.

A desinstalação retornou **0**, retirou as quatro chaves próprias (três tipos e a classe COM) e removeu a pasta instalada, sem pedir reinicialização. As associações padrão de PDF/DOCX/PPTX foram comparadas e permaneceram idênticas. Depois, o mesmo pacote foi reinstalado e retornou **0**. O aplicativo ficou instalado para uso. Evidência: `test-results/install-lifecycle.json`, `install.log`, `uninstall.log` e `reinstall.log`.

**O clique pelo menu do Explorador não foi validado até o fim.** A pasta de demonstração foi aberta com dois PDFs, mas, ao retomar a automação depois da reinstalação, o controle de tela informou interrupção pelo usuário com a tecla física Esc. Nenhuma ação posterior de interface foi executada. Registro das chaves e testes COM aprovados não foram contados como prova visual do menu. A atualização imediata da pasta, a aparência e a ordenação após um clique real continuam pendentes de validação manual.

O menu moderno sem assinatura não foi instalado nem considerado validado. O sparse MSIX foi empacotado pelo MakeAppx oficial; `/nv` é o modo previsto pela Microsoft para referências a arquivos externos.

## Correção encontrada durante os testes

Uma execução inicial no sandbox não conseguiu iniciar processos Win32 e renomear arquivos; esses erros de ambiente não foram tratados como validação do produto. Na primeira execução dos binários fora do sandbox, houve 13 testes aprovados e um caso de OCR reprovado: a biblioteca descartava texto invisível. A validação impediu publicar um DOCX vazio. O backend passou a selecionar o modo OCR por página, com regressão para PDF misto. Após reconstruir o executável do motor, a suíte final teve 15/15 aprovados. Esse histórico não foi contado como aprovação.

## Limites da evidência

- Não houve execução em Windows 10, ARM64 ou Windows 32 bits; Windows 10 x64 permanece alvo de compatibilidade, não uma plataforma validada nesta sessão.
- Não houve instalação/teste do menu moderno com certificado confiável. A entrega operacional sem assinatura usa o menu clássico.
- Não foi usada VM limpa sem Office/Python já instalados no sistema. Os testes invocam os motores privados incluídos, mas isso não substitui uma matriz de máquinas limpas. As DLLs VC++ do LibreOffice são app-local para reduzir essa dependência.
- Não houve teste de pasta de rede/UNC, FAT32/exFAT, pendrive, OneDrive somente online, disco cheio, encerramento abrupto do sistema, exaustão de memória ou de documentos maliciosos arbitrários.
- O funcionamento local foi verificado pelos motores usados e pela ausência de chamadas de rede no código do aplicativo. Não foi feita captura de tráfego nem teste com o adaptador de rede desligado.
- Não há promessa de fidelidade universal. PDF → Word e OCR precisam de revisão; PDF → PPTX usa imagens. Fontes/efeitos não suportados pelo LibreOffice podem diferir.
- O GitHub Actions foi preparado para execução futura; nenhum resultado remoto foi presumido.

## Atualização 1.0.1 — modelo externo do Word

O relato posterior do usuário mostrou que um vínculo `attachedTemplate` bloqueava documentos com conteúdo incorporado. A correção desvincula somente esse modelo na cópia temporária, mantendo a recusa de imagens/dados que realmente dependem de recursos externos.

Suíte atual: **26/26 testes pytest aprovados em 80,79 segundos**. Três DOCX reais foram convertidos em cópias privadas, com 11 páginas em cada PDF e hashes originais preservados. Um teste HTTP confirmou zero consultas ao modelo externo. O motor instalado foi atualizado em dois arquivos e o documento original do erro gerou um PDF válido de 11 páginas na mesma pasta. A primeira página publicada foi inspecionada visualmente.

Detalhes, métricas e limites: `docs/external-template-fix.md`. O XML atual em `test-results/integration.xml` corresponde aos 26 testes; o pacote `artifacts/ConversorLocal-1.0.0-test-evidence.zip` preserva a evidência histórica dos 15 testes da primeira versão. Os testes nativos, de instalação/desinstalação e de interface descritos acima são resultados da 1.0.0, não novas execuções nesta correção.

## Atualização 1.0.2 — aparência

As janelas de configurações, progresso e resultados receberam novo layout, tipografia, cores, cartões e botões. O motor, núcleo de conversão, integração com o Explorador e `Program.cs` permaneceram iguais. Foram conferidos 280 arquivos do motor por SHA-256 e a DLL de shell, sem diferenças.

**26/26 testes pytest aprovados em 103,69 segundos**, registrados separadamente em `test-results/ui-refresh/integration.xml`. A interface foi inspecionada no Windows e os botões Salvar, Cancelar e Fechar foram exercitados. Uma conversão PDF → PPTX pela interface instalada gerou três slides na mesma pasta, com origem preservada. O cancelamento pela interface final publicou nenhuma saída e preservou a origem. Os últimos ajustes de pintura/estado foram compilados e verificados na instalação; o resultado final permanece em 100%.

Detalhes, evidências e limites: `docs/ui-refresh.md`. A versão 1.0.2 não repete o ciclo completo de desinstalação/reinstalação; a atualização local substitui o host e preserva os motores. Os resultados históricos das versões anteriores continuam identificados em suas respectivas seções e pacotes.

## Atualização 1.1.0 — formatos e seleção local

Foram acrescentados ODT, RTF, TXT, ODP, XLSX, ODS, PNG, JPG/JPEG, WEBP, BMP e TIF/TIFF, além de PDF → TXT. O catálogo compartilhado possui 62 pares permitidos, incluindo aliases. A janela do programa agora permite escolher arquivos e somente destinos compatíveis com todo o lote.

**108/108 testes pytest aprovados em 568,77 segundos**, incluindo todos os pares e as regressões anteriores. Após a integração da GUI e da extensão clássica, a comparação do motor confirmou os mesmos 11 membros do CArchive, os mesmos bytes dos membros da biblioteca base, bibliotecas nativas idênticas e rotas de conversão iguais; somente a identidade de shell no catálogo e o empacotamento externo mudaram. A suíte completa não foi repetida após essa etapa. A DLL final passou 23 seleções IExplorerCommand e 23 IContextMenu, IDs de comandos, filtro de comando padrão, invocação inválida e liberação de referências.

O fluxo instalado **Converter arquivos → PNG → JPG** foi executado visualmente, preservou o hash original e produziu 360 × 240 pixels com fundo branco. A API registrada do Shell também despachou PNG → WEBP, preservando transparência e origem. DOCX de ODT foi aberto no Word e XLSX de ODS no Excel, sem pedido de reparo. Onze páginas PDF de oito formatos foram renderizadas e inspecionadas.

O primeiro instalador de validação identificou `checkedonce` desmarcando o menu nas atualizações; o sinalizador foi removido. A tarefa de menu foi restaurada, e uma execução sem sobrescrever a seleção confirmou o registro. O instalador final mantém DLLs idênticas por SHA-256 e não fecha aplicativos automaticamente. Preferências, associações padrão e artefatos anteriores são comparados com o baseline em `extended-formats/install-update.json`.

**O clique visual no menu do Explorador permanece pendente:** nesta sessão o novo comando não foi exibido. O Explorador original não foi reiniciado porque havia uma cópia em andamento. A seleção pela janela do aplicativo foi validada e está disponível. O menu moderno continua sem assinatura e não foi instalado. Detalhes e evidências: `docs/extended-formats.md` e o pacote `ConversorLocal-1.1.0-test-evidence.zip`.
