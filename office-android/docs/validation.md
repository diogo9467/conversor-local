# Validação — Office Local

Registro de trabalho de 19/10/2026. Nenhum resultado abaixo equivale a teste em tablet físico.

## Referência do conversor preservada

| Verificação | Resultado observado |
| --- | --- |
| Código inicial | `codex/android-offline`, `7f1490c2f8cc21acdb456e09d72b0af55c5d6c84` |
| Instrumentação original | 11 testes aprovados no Android 15 x86_64, 71,819 s |
| Testes JVM originais | 2 aprovados em nova execução com `--rerun-tasks` |
| Lint original | 0 erros; relatório atualizado reutilizado pelo Gradle |
| Rede durante a instrumentação | Modo avião, Wi-Fi e dados desligados |
| Fonte do conversor e Windows | Sem alterações nesta etapa |
| Correção nativa MuPDF | SHA-256 `c0c84ef8fe95ef5e6a373c3411d74802731915c71b298e0a02caf974451aa9a1`, idêntico ao manifesto do motor funcional |

Os relatórios da nova execução estão em `artifacts/office-android/baseline/`, fora do Git. O MuPDF ARM64 depende apenas de bibliotecas do Android; não introduz um segundo `libc++_shared.so`. A compatibilidade com o novo motor Office ainda exige executar as quatro conversões na suíte.

## Prova da base Collabora

| Verificação | Resultado observado |
| --- | --- |
| Fonte | `cp-26.04.4-2`, commit `201368fc8ded1a0f28d1891c77e0410cb9416531` |
| Ambiente Linux | WSL2 isolado, Ubuntu 24.04.5, usuário `officebuild` |
| Recursos da interface upstream | Compilação concluída |
| Configuração Gradle | Gradle 9.5.0 / AGP 9.3.0 carregados com sucesso |
| Java do aplicativo e da prova | Compilação aprovada, 50 tarefas executadas |
| Recursos do pacote de teste | Correção validada, `processDebugAndroidTestResources` aprovado |
| Motor x86_64 | Em compilação a partir das fontes |
| Motor ARM64 | Em compilação a partir das fontes |
| Abrir, editar, salvar e reabrir | Ainda não executado; depende do APK |
| Integração do conversor | Ainda não implementada |
| Dispositivo físico | Não utilizado |

O emulador dedicado é `emulator-5580`, Android 15 x86_64, configurado para 1600 × 1000 pixels com densidade 200 (área de 1280 × 800 dp), em modo avião. Isso exercita o layout de tablet; não representa desempenho ou validação do tablet do usuário.

## Ocorrências resolvidas na preparação

- Docker Desktop não iniciou: falha no Secrets Engine/`engine.sock`. Foi utilizado um WSL separado, sem redefinir a configuração do Docker.
- A documentação introdutória usava opções antigas. Os scripts foram ajustados às versões exigidas pelo código fixado: NDK 29, SDK `platforms/android-37.0`, JDK 21.
- A geração dos recursos de teste precisava do Node fixado no `PATH`. A compilação usa um ambiente explícito.
- O `resValue` herdado pelo pacote de testes apontava para `drawer_header`, que não estava nos recursos do teste. O instalador da prova copia o recurso original para esse pacote; a vinculação foi aprovada depois da correção.
- Uma tentativa antecipada de empacotar o teste confirmou a dependência de `android/jniLibs/x86_64/liblo-native-code.so`, ainda em geração. O código Java aprovado não foi apresentado como APK funcional.

## Evidência esperada da prova

`tools/run_base_proof.py` registra a execução instrumentada, arquivos DOCX salvos/reabertos, capturas e hashes. O teste modifica um documento sintético com tabela e imagens usando o editor upstream real. Sua aprovação será registrada somente depois de executar o APK. As verificações de SAF, recuperação, exportação das alterações e cancelamento com o editor aberto pertencem à etapa de integração, não a essa prova inicial.
