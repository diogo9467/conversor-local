# Versão 1.0.2 — atualização visual

Solicitação: melhorar somente a aparência, mantendo as funcionalidades já aprovadas pelo usuário. A atualização foi aplicada à instalação local; o motor e a integração com o Explorador conservaram os mesmos arquivos.

## Apresentação

- Paleta clara com azul, cartões brancos, bordas suaves, tipografia Segoe UI e espaçamento uniforme.
- Cabeçalho comum nas configurações e no progresso, com identificação de processamento local.
- Arquivo e formato de saída destacados; etapa, porcentagem e barra de progresso alinhadas.
- Mensagens de resultado em uma área própria, com rolagem, e cores para conclusão, cancelamento e falha.
- Configurações de OCR e formatos disponíveis organizados em cartões. O rodapé esclarece que os resultados ficam na pasta do original.
- Botões com estados de foco, passagem do mouse e desativação; o checkbox continua sendo o controle nativo do Windows. As cores respeitam o modo de alto contraste quando detectado.

`SettingsForm` foi separado em seu próprio arquivo e `AppTheme` reúne a apresentação comum. A exibição final ignora notificações de progresso já enfileiradas depois do término, evitando que o título ou o percentual voltem a indicar processamento. Erros inesperados aparecem integralmente na área de detalhes.

## Funcionalidades preservadas

Não foram alterados `src/Conversor.App/Program.cs`, `src/Conversor.Core`, `src/Conversor.Shell` ou `engine`. Permanecem as quatro conversões, OCR, opções de linha de comando, preferência de OCR, cancelamento, relatórios, proteção dos originais, criação de nomes com sufixos e salvamento na pasta de origem. O fechamento automático após sucesso sem avisos conserva o intervalo de 1.400 ms.

Antes da substituição do host foram conferidos **280 arquivos do motor instalado e do pacote**, todos idênticos à linha de base. A DLL do Explorador também foi comparada por hash e permaneceu igual. O aplicativo anterior foi guardado em `.build/backups/ui-refresh-1.0.1/installed`; a revisão intermediária da interface possui backup separado. Os artefatos 1.0.0 e 1.0.1 foram preservados.

## Verificação realizada em Windows 11 x64

- Compilação final de publicação concluída com o SDK .NET 10.0.402.
- **26/26 testes pytest aprovados em 103,69 segundos**, abrangendo as quatro conversões, OCR, documentos com modelos externos, conteúdo inválido, colisões, nomes especiais e integridade das origens. Evidência separada: `test-results/ui-refresh/integration.xml`.
- Configurações, andamento, falha, cancelamento e sucesso com aviso foram abertos e inspecionados nas janelas reais do Windows. Foram corrigidos os últimos detalhes de pintura e estado final; andamento, cancelamento e sucesso foram novamente conferidos na instalação final.
- O botão Salvar foi acionado com mouse e pelo teclado, usando Tab e espaço. A preferência de OCR permaneceu idêntica à linha de base.
- Um PDF sintético de três páginas foi convertido em PPTX pela interface da instalação final: três slides, publicação na mesma pasta, hash da origem preservado, título final “Conversão concluída” e indicador estável em 100%.
- Uma conversão sintética com OCR foi interrompida pelo botão Cancelar na instalação final. O relatório confirma cancelamento, nenhuma saída publicada e origem preservada.

Evidências locais: `test-results/ui-refresh/validation.json`, `update.json`, `settings.png`, `progress.png` e `result.png`. As imagens mostram somente a aplicação e nomes de documentos sintéticos. Nenhum documento pessoal foi usado nesta atualização visual.

As verificações de interface foram feitas na escala atualmente configurada no computador. Não houve alteração das configurações de tela, nem validação em outras escalas de DPI, modo de alto contraste, Windows 10 ou outra máquina. A suíte automatizada foi executada antes dos últimos ajustes exclusivamente de pintura/estado; esses ajustes finais foram compilados e verificados pela interface instalada, sem alterar o caminho de conversão sem interface.
