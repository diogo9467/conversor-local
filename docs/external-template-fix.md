# Correção 1.0.1 — modelo externo do Word

Correção do bloqueio mostrado pelo usuário em 18/10/2026. A instalação local foi atualizada e o DOCX que apresentou o erro foi convertido com sucesso em um PDF de 11 páginas, publicado na pasta da origem. O SHA-256 do DOCX permaneceu idêntico.

## Causa e alteração

Os três documentos examinados tinham uma relação externa `attachedTemplate` para `microorganisms-template.dot`. A versão 1.0.0 recusava qualquer relação externa que não fosse hiperlink, tratando esse vínculo de configuração como imagem ou dado ausente.

Antes de abrir o DOCX no LibreOffice privado, a versão 1.0.1 desvincula esse modelo somente no snapshot temporário do trabalho. A alteração limita-se a `word/_rels/settings.xml.rels` e `word/settings.xml`: retira a relação `attachedTemplate`, sua referência e a opção `linkStyles`. Não resolve nem abre o caminho do modelo. Todas as demais partes ZIP, incluindo conteúdo, estilos, tabelas, cabeçalhos e imagens, mantêm seus bytes descompactados. O DOM conserva declarações de namespace, inclusive as referidas por `mc:Ignorable`.

Imagens, objetos OLE e conteúdo importado que dependam de referências externas continuam recusados, com mensagem específica para imagens. O original nunca é regravado. O host C# e a extensão do Explorador permaneceram iguais; a correção está no motor de conversão.

## Evidência executada

- **26/26 testes pytest aprovados em 80,79 segundos**, usando o host C# e o motor congelado reconstruído nos testes de integração. As quatro modalidades de conversão, OCR, nomes especiais, preservação da origem, colisões e falhas permanecem cobertas. Evidência: `test-results/integration.xml`.
- Oito testes específicos verificam alteração restrita aos metadados, preservação das partes e namespaces, destinos de modelo `file:`, HTTPS e UNC, recusa de conteúdo externo e ausência de regravação para DOCX autossuficiente. Evidência: `test-results/external-links-fix/unit.xml` e suíte completa.
- Um servidor HTTP local de teste recebeu **zero solicitações** durante uma conversão completa com um modelo apontando para ele. Isso verifica a ausência de busca desse modelo; não equivale a uma captura geral de tráfego do sistema.
- Cópias privadas dos três DOCX reais foram convertidas. Cada PDF tem **11 páginas**, todas decodificadas e renderizadas; os três originais conservaram seus hashes. As primeiras páginas foram inspecionadas visualmente, com títulos, cabeçalhos, imagens e texto legíveis. Os documentos e suas prévias ficam fora do Git e dos pacotes de entrega.

| Documento anonimizado | Páginas | Ocorrências de imagem | Cobertura de palavras distintas do corpo |
| --- | ---: | ---: | ---: |
| 1 | 11 | 22 | 99,85% |
| 2 | 11 | 22 | 99,91% |
| 3 | 11 | 22 | 99,52% |

A cobertura compara conjuntos de tokens, permitindo diferenças de quebra de linha e hifenização; não comprova equivalência visual integral. Evidência anonimizada: `test-results/external-links-fix/real-documents/validation.json`.

## Atualização instalada

Foram substituídos somente `engine/Conversor.Engine.exe` e `engine/_internal/base_library.zip`, após conferir os hashes anteriores, guardar os arquivos substituídos na área de backup do projeto e verificar os hashes novos. Evidência: `test-results/external-links-fix/update.json`. A integração de shell e as associações padrão não foram alteradas por esta atualização.

O host da pasta instalada foi então executado contra o DOCX original que apresentou o erro: retorno 0, PDF válido de 11 páginas no mesmo diretório, todas as páginas renderizadas e SHA-256 da origem preservado. A primeira página do PDF publicado foi inspecionada visualmente. Evidência anonimizada: `test-results/external-links-fix/installed-validation.json`.

O clique no menu do Explorador não foi repetido nesta correção. O teste da instalação executou diretamente o mesmo host e o motor atualizado. Os limites de plataforma e fidelidade registrados no relatório da versão 1.0.0 continuam aplicáveis. Os artefatos originais da versão 1.0.0 foram conservados; a versão 1.0.1 tem instalador, fontes e evidências separados.

## Referências técnicas

- [Microsoft — attachedTemplate](https://learn.microsoft.com/en-us/openspecs/office_standards/ms-oe376/7713efa6-b1ff-4cbd-9339-5bf9018433ac): relação entre a configuração do documento e o modelo anexado.
- [LibreOffice — UpdateDocMode](https://api.libreoffice.org/docs/idl/ref/namespacecom_1_1sun_1_1star_1_1document_1_1UpdateDocMode.html): modo `NO_UPDATE` usado pelo perfil privado da conversão.
