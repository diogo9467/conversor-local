# Conversor Local para Windows

Aplicativo gratuito e offline para Windows 10/11 **x64**, com C#/.NET 10, uma extensão nativa `IExplorerCommand` e motores privados de conversão. O File Converter não é instalado nem utilizado.

## Instalação e uso

1. Abra `artifacts/ConversorLocal-1.0.1-win-x64-Setup.exe`.
2. Instale para seu usuário. Não são necessários administrador, Python, Office, LibreOffice pré-instalado, conta ou internet.
3. No Explorador, selecione um ou vários arquivos e clique com o botão direito. No Windows 11, abra **Mostrar mais opções**.
4. Em **Converter arquivo**, escolha a operação. A janela permite acompanhar e cancelar.

| Origem | Destino | Como funciona |
| --- | --- | --- |
| DOCX | PDF | LibreOffice Writer headless |
| PPTX | PDF | LibreOffice Impress headless |
| PDF | DOCX | Reconstrução de texto, tabelas e imagens com pdf2docx; OCR local opcional |
| PDF | PPTX | Uma imagem de alta resolução por página/slide, sem recorte |

O resultado sempre fica **na pasta de origem**, com o mesmo nome-base. Arquivos existentes recebem `(1)`, `(2)`, etc. O original e qualquer destino existente são preservados. A falta de permissão de escrita gera erro, sem salvar em outra pasta. Não há diálogo de destino, abertura automática do resultado ou nova janela do Explorador. A notificação `SHChangeNotify` atualiza a pasta e mantém a ordenação do Windows.

Um lote processa os arquivos sequencialmente para limitar memória; conversões iniciadas separadamente são isoladas e podem executar simultaneamente. Uma falha não interrompe os demais arquivos. Uma seleção mista DOCX/PPTX oferece PDF; uma seleção de PDFs oferece DOCX/PPTX. Seleções sem uma conversão comum não exibem um comando inválido.

### OCR

Em **Iniciar → Conversor Local → Configurações**, é possível ativar/desativar o OCR. Ele está ativado inicialmente, funciona apenas na máquina e inclui português/inglês. O motor Tesseract é incorporado ao MuPDF; os modelos estão em `ocr/tessdata`. A desativação impede a execução de novo reconhecimento; uma camada OCR já existente pode ser recuperada. Revise palavras e números reconhecidos. Páginas digitalizadas geram texto editável com reconstrução aproximada; imagens/tabelas existentes somente no raster não são necessariamente recuperadas como objetos separados.

### Windows 11 e assinatura

Esta edição tem instalador **sem assinatura digital** e registra a integração no menu clássico. O Windows pode mostrar editor desconhecido. O aplicativo não importa certificados, muda políticas, desativa verificações ou reinicia o Explorador. O instalador e a extensão COM foram testados; a checagem visual de uma conversão acionada no Explorador foi interrompida com Esc e permanece pendente no relatório de testes.

A extensão para o menu moderno e seu manifesto de identidade estão implementados. O pacote `artifacts/ConversorLocal-Shell-1.0.0-x64-unsigned.msix` foi gerado pelo MakeAppx oficial, mas **não pode ser instalado normalmente sem assinatura confiável**. O fluxo de assinatura/instalação está em [docs/windows-shell.md](docs/windows-shell.md). Não confundir um MSIX empacotado com uma integração moderna instalada/testada.

### Desinstalação

Use **Configurações do Windows → Aplicativos → Conversor Local → Desinstalar**, ou o atalho do menu Iniciar. O desinstalador remove os binários e apenas as chaves de shell pertencentes ao aplicativo. As associações padrão e os documentos não são alterados. Preferências e logs locais são preservados para diagnóstico; podem ser removidos manualmente depois.

## Limitações reais

- Fontes ausentes, campos, diagramas complexos, objetos OLE, efeitos e animações do Office podem diferir no LibreOffice. Animações não aparecem em PDF estático. Slides ocultos são incluídos na exportação para manter um PDF por slide.
- PDF não contém a estrutura original de um Word: paginação, colunas, tabelas complexas, fórmulas e ordem de leitura podem mudar. O resultado requer revisão.
- PDF → PPTX preserva a aparência por imagens (até 200 dpi, máximo de 5.000 pixels por lado e 24 megapixels por página). Os elementos não são editáveis individualmente. PDFs com formatos variados usam margens, sem deformação.
- Arquivos com senha são recusados com mensagem. Não há prompt de senha nem tentativa de removê-la. PDFs que proíbem cópia e documentos que dependem de imagens/dados externos não incorporados também são recusados. Referências a modelos `.dot/.dotm/.dotx` são desligadas somente na cópia temporária: o texto, os estilos e as imagens já gravados no DOCX são preservados, sem acessar o modelo.
- Arquivos grandes exigem memória, espaço no volume temporário e no volume de origem; há limite de 30 minutos por arquivo. Pacotes Office maiores que 2 GB descompactados ou 30 mil entradas são recusados.
- Caminhos longos foram previstos no host .NET; os motores recebem cópias com nomes curtos em uma pasta temporária. Nomes que excedam os limites do sistema de arquivos falham, sem redirecionamento.
- Esta entrega é x64. Windows ARM64 e Windows 32 bits não foram validados.
- Testes estruturais e visuais não equivalem a garantia de fidelidade de todos os documentos possíveis. Veja [docs/test-report.md](docs/test-report.md) para o que foi efetivamente executado.

## Arquitetura e segurança

`src/Conversor.App`: host WinForms e progresso, parâmetros de lote e preferências. `src/Conversor.Core`: snapshot somente de leitura, subprocessos isolados, cancelamento, validação do protocolo, publicação atômica e notificação ao shell. `src/Conversor.Shell`: DLL C++ nativa, que apenas inspeciona extensões e despacha a seleção para o host fora do processo do Explorador. `engine/worker.py`: validação e os quatro motores. `installer/`: instalador por usuário e manifesto MSIX.

O host não entrega o caminho original ao motor. Cada trabalho usa `%LOCALAPPDATA%/ConversorLocal/jobs/<id>`. A saída é validada antes de copiar para um arquivo `.partial` na pasta de origem e renomear atomicamente sem substituição. Um Job Object do Windows encerra os processos filhos ao fechar o host. Temporários são removidos no encerramento normal, falha ou cancelamento. Um encerramento forçado do sistema pode deixar o snapshot temporário; ele não é publicado como documento final.

Não há cliente HTTP, telemetria, login ou upload no aplicativo. Macros são desativadas no perfil LibreOffice exclusivo. Modelos externos do Word são desvinculados na cópia privada; imagens/dados que exigiriam acesso externo são recusados antes de abrir no Office headless. Uma pasta não confiável não deve ser usada como destino de instalação; instale no caminho padrão do instalador. O aplicativo não é um sandbox para documentos hostis e deve receber atualizações de seus motores.

Logs JSONL locais ficam em `%LOCALAPPDATA%/ConversorLocal/logs`. Incluem nomes/caminhos e erros, sem enviar informações. Preferências ficam em `settings.json` nessa mesma raiz. Não envie logs a terceiros sem conferir os caminhos que contêm.

## Desenvolvimento e manutenção

Somente quem recompila precisa de **Python 3.12 x64**, PowerShell, acesso à internet para provisionar as ferramentas e cerca de 8 GB livres. O usuário do instalador não precisa deles.

```powershell
powershell -NoProfile -File tools/prepare.ps1
.tools/build-python/Scripts/python.exe tools/build.py
```

`prepare.ps1` mantém SDK, compilador, dependências e LibreOffice dentro do projeto. Não define PATH permanente nem instala LibreOffice globalmente. Pacotes principais são fixados em `engine/requirements.txt`; o ambiente completo está em `tools/requirements-build.lock.txt`. Hashes upstream são verificados quando publicados e os artefatos finais têm SHA-256 em `artifacts/SHA256SUMS.txt`. Conexão é usada apenas durante a preparação da compilação.

Para testes, execute `tools/test.ps1`. Os documentos são sintéticos e reproduzíveis, criados em `test-results/fixtures`, sem dados pessoais. Os testes de integração chamam o executável C# e o motor congelado que serão distribuídos, não somente funções Python. É necessário executar fora de um sandbox que bloqueie processos Win32 ou a renomeação de arquivos.

Ao atualizar um motor, altere as versões/hashes, recompile, execute os testes e confira visualmente as evidências. Consulte [docs/architecture.md](docs/architecture.md). A CI Windows em `.github/workflows/windows.yml` está preparada; sua execução remota não é presumida nesta entrega.

## Licenças e fontes

O código do Conversor Local é **AGPL-3.0-or-later**, compatível com a distribuição do PyMuPDF/MuPDF. A instalação inclui o código-fonte do aplicativo, os fontes exatos do motor AGPL e seus avisos em `sources/` e `licenses/`. LibreOffice e demais componentes mantêm suas próprias licenças; veja [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md) e `dependency-manifest.json`. Não há dependência do File Converter nem código copiado desse projeto.
