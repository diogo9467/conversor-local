# Conversor Local Android

Aplicativo independente para Android **8.0 ou superior, ARM64**. Uma tela em Kotlin/Compose, quatro conversões locais, seleção múltipla e recebimento pelo menu Compartilhar. Os motores e as fontes acompanham o APK: nenhuma conversão exige computador, conta, aplicativo Office ou download inicial.

O aplicativo Windows, seu catálogo, motores, instalador e workflow permanecem nas pastas originais, sem alterações. O Android usa somente os quatro pares abaixo, que também existem em `../formats.json`.

| Conversão | Motor incorporado | Resultado e limites |
| --- | --- | --- |
| DOCX → PDF | LibreOfficeKit 26.2.6.3 / Writer | Texto, imagens e tabelas; fontes e recursos específicos podem alterar a paginação. |
| PPTX → PDF | LibreOfficeKit 26.2.6.3 / Impress | Um PDF com todos os slides, inclusive ocultos. |
| PDF → PPTX | Android PdfRenderer + empacotador OOXML pequeno | Uma imagem por slide, até 200 dpi, 4.096 pixels por lado e 8 MP por página. Proporção preservada; páginas diferentes recebem margens. |
| PDF → DOCX | MuPDF/Extract 1.28.5, writer DOCX nativo | Texto realmente editável, reconstrução de tabelas e imagens pelo motor. Colunas, fontes, ordem de leitura e layout precisam de revisão. |

Não há OCR nesta versão. PDFs digitalizados ou páginas predominantemente rasterizadas com texto insuficiente, inclusive apenas número de página, são recusados com explicação. Não são anunciados como DOCX editável. PDFs vetoriais sem texto também não servem para esse destino. Use PDF → PPTX quando desejar preservar a aparência por imagens.

## Instalação e uso

O APK entregue em `../artifacts/android/ConversorLocal-android-0.1.0-arm64-debug.apk` é **uma compilação de teste assinada com chave de debug**, não uma edição assinada para produção. Transfira o APK para o Android e abra-o pelo instalador de pacotes. A autorização para instalar a partir daquele aplicativo é controlada pelo próprio Android.

1. Abra **Conversor Local → Selecionar arquivos**, ou use **Compartilhar → Conversor Local** em outro aplicativo.
2. Escolha o formato compatível com todos os selecionados.
3. Autorize uma pasta, se solicitado. Depois toque em **Converter**.
4. Confira o nome e o URI de destino mostrados após a gravação. **Abrir resultado** usa um visualizador escolhido pelo Android; ele não participa da conversão.

O app não pede acesso irrestrito a arquivos. Escolher um documento não autoriza criar arquivos em sua pasta. Sem acesso prévio a uma árvore, o seletor do Android solicita uma pasta: escolha a pasta original para manter os dois arquivos juntos, ou uma pasta de saída explícita.

Permissões persistentes de diretórios são reaproveitadas. Para arquivos escolhidos no aplicativo, a pasta original só é usada automaticamente quando comprovada pela API do provedor ou pela enumeração de um filho direto de uma árvore autorizada. IDs de documentos são opacos: não são transformados em caminhos físicos. Para arquivos recebidos por compartilhamento, a pasta original não é inferida; escolha o destino. **Usar pasta original autorizada** cancela a escolha explícita de saída.

Colisões recebem `(1)`, `(2)` etc., preservando os arquivos existentes. A confirmação mostra o nome realmente atribuído pelo provedor. Escolher a raiz do armazenamento ou Downloads pode ser proibido pelo Android; escolha uma subpasta. Arquivos de provedores em nuvem precisam estar disponíveis localmente antes de usar o modo avião.

Mantenha a tela do aplicativo disponível durante o processamento. A fila é sequencial e sobrevive à rotação, mas não retoma após reiniciar o aparelho ou encerrar o aplicativo. Sem banco de dados, agendamento ou histórico próprio.

## Motores e tamanho

Não foi presumida uma dependência Gradle pronta de LibreOfficeKit. A prova inicial executou a integração JNI usando bibliotecas Android compiladas pelo F-Droid para LibreOffice Viewer **26.2.6.3**, com seus dados e fontes. O Viewer não é instalado nem chamado: somente as bibliotecas/ativos necessários são incorporados ao nosso APK. O bootstrap Java upstream foi adaptado de `Activity` para `Context`, permitindo execução em um serviço. Os binários nativos não foram modificados.

MuPDF/Extract **1.28.5** é compilado para Android a partir dos fontes fixados, com **NDK 27.2.12479018**. O AAR oficial fornece a API Java; seu JNI é substituído pelo compilado local. `tools/patch_mupdf.py` corrige problemas comprovados no writer nativo: imagens PDF exportadas como `flate`, consumo do buffer de imagens repetidas, perda de relações de imagens de páginas anteriores, dimensões OOXML ausentes, tamanho de fonte fracionário e flags ZIP inconsistentes entre cabeçalhos locais e centrais. Os testes reabrem o DOCX gerado no LibreOfficeKit para detectar regressões de interoperabilidade.

O motor original conserva a reconstrução de texto, parágrafos e tabelas. Imagens extraídas têm limite de **16 MP por imagem e 128 MB de dados PNG acumulados**; a reconstrução do DOCX é concluída de uma vez para conservar as relações entre páginas. Não há Python no APK nem reconstrutor de layout próprio. O PPTX usa uma apresentação vazia gerada para este projeto e um empacotador ZIP/XML; PdfRenderer libera cada bitmap antes de renderizar a próxima página.

Na recuperação de PDF para DOCX, a paginação e as quebras de parágrafos podem mudar. Títulos podem ficar junto do parágrafo seguinte; revise também a ordem de leitura em documentos com colunas ou diagramas. O teste sintético confirmou três imagens decodificáveis, tabelas e os marcadores das três páginas em ordem; isso não equivale a garantir todos os layouts de PDF.

LibreOfficeKit é a maior dependência e torna o APK significativamente maior que a interface. É necessário para converter Office com texto, tabelas, imagens e paginação reais. Os ativos lidos diretamente pelo motor ficam sem compressão; bibliotecas nativas são comprimidas no APK. Os motores estão presentes desde a instalação.

## Compilação reproduzível

Pré-requisitos de desenvolvimento: **JDK 17**, **Python 3.11+**, Android SDK **35**, Build Tools **35.0.0**, **NDK 27.2.12479018**, platform-tools e as licenças do SDK aceitas. Gradle **8.11.1** com SHA-256 verificado pelo wrapper; AGP **8.9.2**, Kotlin **2.1.20** e Compose BOM **2025.04.01** estão fixados. Nada disso é necessário no telefone.

Dentro de `android/`, com `JAVA_HOME` e `ANDROID_HOME` configurados:

```sh
python tools/prepare_engines.py --abis arm64-v8a,x86_64
./gradlew assembleDebug testDebugUnitTest lintDebug
```

No Windows, use `gradlew.bat` no lugar de `./gradlew`. O script de preparação usa apenas a biblioteca padrão do Python, baixa os artefatos fixados em `engine-lock.json`, verifica SHA-256, extrai JNI/dados/fontes, adapta o bootstrap e compila o MuPDF com o NDK fixado. No Windows, o NDK usa o caminho curto 8.3 do projeto; se esse recurso estiver desativado, use uma pasta sem espaços. No Linux, usa o caminho normal. A compilação falha se os motores da ABI escolhida não estiverem preparados. Após `gradlew clean`, execute a preparação novamente.

Saída ARM64: `app/build/outputs/apk/debug/app-debug.apk`. Para o emulador x86_64:

```sh
./gradlew connectedDebugAndroidTest -Pabis=x86_64
```

Os testes offline esperam um emulador com modo avião, Wi-Fi e dados desligados. A CI faz essa preparação no seu emulador isolado. Os auxiliares de SAF e seus documentos sintéticos existem **somente no APK separado de testes**; não acompanham o APK de uso.

No host Windows desta implementação, o cache do Gradle exigiu desativar sua instrumentação interna e o monitoramento de arquivos. Se necessário, antes dos comandos:

```powershell
$env:GRADLE_OPTS='-Dorg.gradle.internal.instrumentation.agent=false -Dorg.gradle.vfs.watch=false'
```

Para uma edição de release, configure `CONVERSOR_KEYSTORE`, `CONVERSOR_STORE_PASSWORD`, `CONVERSOR_KEY_ALIAS` e `CONVERSOR_KEY_PASSWORD` fora do Git e execute `gradlew assembleRelease`. Sem essas variáveis, a edição release fica sem assinatura. Não há chave/senha de produção no repositório. APKs debug de ambientes diferentes podem ter certificados diferentes; isso pode impedir uma atualização direta, exigindo reinstalação e nova autorização de pastas. Arquivos salvos pelo SAF ficam fora dos dados privados do app.

O workflow novo `../.github/workflows/android.yml` compila o APK ARM64, executa testes JVM/lint e usa um emulador Android 15 x86_64 em modo avião para a integração. O APK e os relatórios ficam como artefato **Android-ARM64-debug-and-tests**. O workflow Windows não foi editado.

## Segurança e limites

Até **20 arquivos por lote**, **100 MB por entrada**, **300 páginas/slides** e **3 minutos de motor por arquivo**. Pacotes Office têm limites de entradas, XML e tamanho descompactado. Uma falha não interrompe os demais arquivos do lote.

O original só é aberto para leitura. Cada conversão recebe uma cópia privada no cache; o motor roda fora da thread da interface, em um processo ligado ao aplicativo e descartável por arquivo. Cancelar encerra esse processo, inclusive durante uma chamada nativa. O estado leve da fila fica no ViewModel durante a rotação.

A saída privada é reaberta/validada antes da publicação. O app cria um documento novo pelo SAF, grava, fecha, relê e compara SHA-256; só então confirma sucesso. Cancelamento/erro tenta remover a saída parcial e limpar o cache. Se o provedor impedir a remoção, o URI parcial e a falha de limpeza são informados. Provedores não oferecem todos as mesmas garantias transacionais; o app nunca anuncia como sucesso uma gravação incompleta.

Documentos com senha, macros, objetos ativos incorporados, campos de inclusão ou vínculos externos de recursos são recusados. Hiperlinks comuns não são seguidos. Modelos externos anexados ao DOCX também são recusados nesta versão Android: use uma cópia autossuficiente. Macros ficam desativadas no perfil privado do LibreOfficeKit. Não há permissão de internet, anúncios, login, telemetria, backend ou mecanismo de atualização. Isso não transforma o aplicativo em um sandbox para arquivos hostis.

## Validação

Em **19/10/2026**, passaram **11 testes instrumentados no Android 15 x86_64 em modo avião**, **2 testes JVM** e lint (**0 erros, 13 avisos**). As quatro conversões foram reais; o DOCX e o PPTX gerados foram reabertos pelo LibreOfficeKit incorporado. Houve conferência de texto, tabelas, imagens, contagem de páginas/slides e inspeção visual de PDFs renderizados. Também passaram seletor real, compartilhamento simples/múltiplo, colisões, permissões, lote com arquivo inválido, cancelamento e rotação.

O APK ARM64 foi compilado, assinado com certificado debug e conferido por `apksigner` e `tools/verify_apk.py`. Suas bibliotecas ELF são ARM64 e têm segmentos alinhados a 16 KB. Isso é validação de empacotamento, não execução ARM64.

Consulte `docs/validation.json`, as imagens em `docs/images/` e os relatórios disponibilizados junto ao APK. A validação local distingue Windows/JVM, emulador Android x86_64 e ARM64. **Não foi usado um celular/tablet ARM64 físico**, portanto a execução nessa arquitetura não é anunciada como validada em aparelho real.

## Licenças e fontes

Aplicativo: **AGPL-3.0-or-later**, como o repositório. MuPDF/Extract: AGPL-3.0-or-later. LibreOfficeKit: MPL-2.0 e licenças de seus componentes. AndroidX/Compose/Kotlin: Apache-2.0. Avisos, MPL/LGPL/AGPL e os textos de terceiros do LibreOffice e MuPDF estão em `app/src/main/assets/licenses/`; o botão **Licenças e fontes** mostra a atribuição e os endereços dos fontes. Ao redistribuir um APK, forneça aos destinatários os fontes correspondentes e preserve os avisos.

- [Fontes LibreOffice ARM64 26.2.6.3, build 131](https://f-droid.org/repo/org.documentfoundation.libreoffice_131_src.tar.gz), [build x86_64 129](https://f-droid.org/repo/org.documentfoundation.libreoffice_129_src.tar.gz) e [receita F-Droid](https://gitlab.com/fdroid/fdroiddata/-/blob/master/metadata/org.documentfoundation.libreoffice.yml). A compilação integral do motor exige o ambiente Linux e dependências da receita; não é executada pelo nosso Gradle. A preparação do APK reaproveita esses binários Android fixados por hash, sem alegar compilação nativa local.
- [MuPDF 1.28.5, fontes completos](https://mupdf.com/downloads/archive/mupdf-1.28.5-source.tar.gz), SHA-256 em `engine-lock.json`, incluindo os subcomponentes necessários à compilação nativa. Os avisos de 43 arquivos de licença/atribuição foram reunidos dessa distribuição.
- [LibreOfficeKit Android e bootstrap](https://docs.libreoffice.org/android.html), [API LibreOfficeKit](https://docs.libreoffice.org/libreofficekit.html), [SAF](https://developer.android.com/training/data-storage/shared/documents-files) e [PdfRenderer](https://developer.android.com/reference/android/graphics/pdf/PdfRenderer).
