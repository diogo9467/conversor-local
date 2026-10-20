# Referência anterior à integração

Inspeção de 19/10/2026, branch de trabalho `codex/office-local-android`.

- Repositório limpo no início. `git fetch origin` e consulta de todas as branches remotas confirmaram `codex/android-offline` em `7f1490c2f8cc21acdb456e09d72b0af55c5d6c84` como Android mais recente. `main` permanecia no Windows `54c1a2c`.
- Nenhum `AGENTS.md` encontrado no projeto. Foram lidos README, manifesto, Gradle, motores, validação, SAF, fila e processo de conversão.
- Referência revalidada antes de alterar motores: 11 testes instrumentados, 0 falhas/erros, Android 15 x86_64, modo avião, Wi-Fi e dados desligados. Duração registrada no XML: 71,819 s. Os 2 testes JVM também foram executados com `--rerun-tasks`, aprovados. Lint sem erros, reutilizado pelo Gradle por estar atualizado.
- Relatórios locais preservados em `artifacts/office-android/baseline/`; execução em `.build/office-converter-baseline.log`.
- O teste inclui as quatro conversões reais, reabertura do DOCX/PPTX produzido, imagens e tabelas, seletor SAF, colisões, leitura/escrita, compartilhamento, cancelamento e rotação.

## Fontes escolhidas

Collabora Office **26.04.4.2**, tag `cp-26.04.4-2`, commit `201368fc8ded1a0f28d1891c77e0410cb9416531`, conferido no [espelho oficial](https://github.com/CollaboraOnline/online.mirror/tree/201368fc8ded1a0f28d1891c77e0410cb9416531). O [índice Android oficial](https://www.collaboraoffice.com/downloads/fdroid/repo/) identifica a versão móvel estável 26.04.4.2, build 937.

O [guia upstream](https://www.collaboraoffice.org/post/build-code-android/) confirma que o editor mobile inclui a interface e o serviço nativo local. O código dessa revisão exige NDK 27–29 e seus Gradle fixam NDK 29, SDK 37.0 e Build Tools 37.0.0; o exemplo NDK 23 do texto introdutório está desatualizado em relação ao código. O identificador correto da plataforma é `platforms/android-37.0`.

O motor dessa revisão expõe **COKit**, enquanto o conversor usa JNI de LibreOfficeKit 26.2.6.3. As bibliotecas não são intercambiáveis por nome. A decisão de compartilhar o motor depende de uma ponte compatível com essa revisão e da aprovação dos testes de conversão. MuPDF/Extract 1.28.5 e suas correções PNG/OOXML/ZIP devem ser preservados.

### Pontos de integração identificados no código

- `COKitDocument::saveAs` grava uma cópia do estado em memória. Sem a opção `TakeOwnership`, mantém a identidade do documento aberto; isso permite exportar sem alterar o original.
- `LOActivity.onPause` solicita salvamento automático, e `copyTempBackToIntent` grava na URI recebida. A suíte deverá entregar uma cópia privada persistente ao editor, controlando a publicação no original separadamente.
- `kit/SetupKitEnvironment.hpp` define `CONFIGURATION_LAYERS` sem a camada de preferências comuns do usuário. Portanto, somente copiar o perfil de segurança do conversor antigo não comprova a desativação de macros no editor.
- `engine/desktop/source/lib/init.cxx` usa `MacroExecMode::USE_CONFIG` no Android. A adaptação final deverá impor a desativação de macros no carregamento nativo, além das validações de entrada; essa alteração ainda não foi aplicada à prova inicial.
- `engine/sal/android/cokit-jni.cxx` define `OOO_DISABLE_RECOVERY=1`. As cópias recuperáveis da suíte precisarão ficar em armazenamento privado persistente, fora de `cache/jobs`, que é descartado pelo conversor.
- A extração inicial dos recursos é controlada por `BuildConfig.GIT_COMMIT` em `LOActivity`. Como o fork mantém o commit upstream fixo, a versão dos recursos da adaptação também precisará mudar esse identificador quando os recursos empacotados mudarem; apenas aumentar o `versionCode` não força a extração.

## Ambiente isolado

Docker Desktop falhou ao iniciar com `initializing Secrets Engine ... engine.sock ... The file cannot be accessed by the system`. A alternativa foi criar a distribuição WSL `OfficeLocalBuild`, em `.tools/office-local-linux`, sem redefinir o Docker.

Ubuntu Base 24.04.5 amd64, SHA-256 `e77b6f10c2590cef872b33ee9f635a0e3fd1f57fb074c0e52b5c7f56147a0c86`, conferido com os hashes da Canonical. Compilação sob o usuário Linux `officebuild`, diretório `/opt/office-local`, fontes upstream obtidos em checkout raso separado. Nenhum histórico upstream é incorporado ao repositório do conversor.

Este registro descreve a referência e a preparação. Não constitui aprovação do editor, da integração ou de um APK novo; esses resultados exigem evidências próprias.
