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

A validação do instalador e do fluxo acionado pelo menu será registrada aqui após executar o pacote final. O menu moderno sem assinatura não é instalado nem considerado validado. O sparse MSIX foi empacotado pelo MakeAppx oficial; `/nv` é o modo previsto pela Microsoft para referências a arquivos externos.

## Correção encontrada durante os testes

A primeira execução completa teve 13 testes aprovados e um caso de OCR reprovado: a biblioteca descartava texto invisível. A validação impediu publicar um DOCX vazio. O backend passou a selecionar o modo OCR por página, com regressão para PDF misto. Após reconstruir o executável do motor, a suíte final teve 15/15 aprovados. Esse histórico não foi omitido nem contado como aprovação.

## Limites da evidência

- Não houve execução em Windows 10, ARM64 ou Windows 32 bits; Windows 10 x64 permanece alvo de compatibilidade, não uma plataforma validada nesta sessão.
- Não houve instalação/teste do menu moderno com certificado confiável. A entrega operacional sem assinatura usa o menu clássico.
- Não foi usada VM limpa sem Office/Python já instalados no sistema. Os testes invocam os motores privados incluídos, mas isso não substitui uma matriz de máquinas limpas. As DLLs VC++ do LibreOffice são app-local para reduzir essa dependência.
- Não houve teste de pasta de rede/UNC, FAT32/exFAT, pendrive, OneDrive somente online, disco cheio, encerramento abrupto do sistema, exaustão de memória ou de documentos maliciosos arbitrários.
- O funcionamento local foi verificado pelos motores usados e pela ausência de chamadas de rede no código do aplicativo. Não foi feita captura de tráfego nem teste com o adaptador de rede desligado.
- Não há promessa de fidelidade universal. PDF → Word e OCR precisam de revisão; PDF → PPTX usa imagens. Fontes/efeitos não suportados pelo LibreOffice podem diferir.
- O GitHub Actions foi preparado para execução futura; nenhum resultado remoto foi presumido.
