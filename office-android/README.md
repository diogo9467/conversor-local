# Office Local — Android

Desenvolvimento da suíte a partir do aplicativo Android real do Collabora Office. O conversor funcional permanece em `../android/`, sem alterações. A referência inicial e os testes anteriores à integração estão em [docs/baseline.md](docs/baseline.md).

## Estado

Preparação da prova da base. A interface upstream já foi compilada; os motores nativos x86_64 e ARM64 estão em compilação. Ainda não há uma versão integrada aprovada nem um novo APK de entrega. O andamento não substitui a prova de abrir, editar, salvar e reabrir.

## Compilar a base

Use Linux x86_64 (Ubuntu 24.04), com espaço para fontes, dependências e duas compilações nativas. A implementação usa um checkout raso independente; não incorpora o histórico do Collabora neste repositório. Versões e hashes estão em `upstream.lock.json`.

Em um ambiente Linux dedicado, execute `sudo bash tools/bootstrap_linux.sh`. Depois, como o usuário `officebuild`, com este repositório acessível:

```sh
python3 tools/prepare_linux_toolchain.py
bash tools/build_engine.sh x86_64
python3 tools/install_proof.py /opt/office-local/upstream
bash tools/build_editor.sh x86_64
```

O diretório de trabalho padrão é `/opt/office-local`. O motor ARM64 é preparado com `bash tools/build_engine.sh arm64-v8a`; configure e compile o editor para apenas uma ABI por vez. Os dois motores usam diretórios de saída separados. `OFFICE_JOBS` limita os processos de compilação nativa.

A prova inicial usa o catálogo inglês. Para incluir os recursos nativos em português brasileiro, obtenha a revisão oficial com `python3 tools/prepare_translations.py` e compile com `OFFICE_LANGUAGES='en-US pt-BR' bash tools/build_engine.sh ABI`. O catálogo tem commit fixo e não depende da revisão mais recente de uma branch.

O Android Studio/Gradle isoladamente não compila os componentes nativos dessa base. O guia upstream é <https://www.collaboraoffice.org/post/build-code-android/>; os scripts seguem as versões exigidas pelo código fixado, que diferem de alguns exemplos antigos do guia.

## Prova em emulador

`proof/EditorProofTest.java` usa o editor Collabora real. Abre o DOCX sintético de teste do conversor, insere texto com acentos, salva, fecha, reabre e salva novamente. Confere texto, tabela e imagens nos arquivos resultantes e registra capturas de tela. Não usa aplicativo Collabora externo.

Depois de gerar os APKs de aplicativo e teste, use `tools/run_base_proof.py --adb CAMINHO_ADB --serial emulator-5580 --apk APK --test-apk APK_TESTE --output DIRETORIO`. O script exige um emulador explícito, desliga suas conexões antes da instalação e preserva evidências. Não altera o conversor instalado nem apaga arquivos do usuário.

A prova da base não cobre integração, salvamento sobre um provedor SAF, exportação das alterações, recuperação, tablet físico ou ARM64 em execução. Esses critérios precisam de testes próprios nas etapas seguintes.

## Licenças

A revisão upstream e seus componentes mantêm suas licenças originais. O Collabora inclui código MPL-2.0 e componentes sob outras licenças. O conversor utiliza MuPDF/Extract sob AGPL-3.0; avisos e fontes correspondentes devem acompanhar a distribuição da suíte. Office Local é uma adaptação independente e não é o aplicativo oficial da Collabora.
