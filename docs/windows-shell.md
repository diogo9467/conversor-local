# Menu de contexto do Windows

## Integração entregue

`Conversor.Shell.v3.dll` é uma DLL nativa x64. O menu clássico usa `IShellExtInit`/`IContextMenu` e a seleção `CF_HDROP`; a identidade moderna usa `IExplorerCommand`/`IEnumExplorerCommand`. Ambos compartilham o catálogo e o mesmo despacho JSON para o host. O trabalho pesado começa somente em outro processo.

O instalador registra a COM class `{EDBFA846-34E1-41EC-9FF9-144E71B1763B}` em HKCU e o manipulador próprio `Software/Classes/*/shellex/ContextMenuHandlers/ConversorLocal`. A extensão oferece somente destinos comuns aos arquivos selecionados; tipos incompatíveis não recebem o menu. As 16 extensões vêm de `formats.json`, incluindo JPEG e TIF. Não usa associação padrão nem altera políticas. A desinstalação remove a chave do manipulador e a classe COM.

A atualização 1.1.0 usa uma nova identidade COM e um novo nome de DLL. Assim, a DLL anterior já carregada no Explorador não precisa ser sobrescrita nem o Explorador reiniciado. O instalador remove a classe COM antiga pertencente ao aplicativo e registra a nova; o desinstalador acompanha os novos arquivos e as novas chaves.

Reinstalações mantêm uma DLL de shell cujo SHA-256 já corresponde ao payload, evitando substituição desnecessária de um módulo carregado. A tarefa de menu preserva a seleção anterior; o sinalizador `checkedonce`, que a desmarcava nas atualizações, foi retirado. Veja [Inno Setup: tarefas](https://jrsoftware.org/ishelp/topic_taskssection.htm) e [checagens de instalação](https://jrsoftware.org/ishelp/topic_scriptcheck.htm).

Windows 10 usa o menu clássico. No Windows 11, ele fica em **Mostrar mais opções**. Um lote é despachado por um único JSON, sem executar um comando por arquivo. A GUI do programa também permite selecionar arquivos e o destino compatível.

Validação 1.1.0: 23 seleções para cada interface, IDs de comandos, filtro de comando padrão, invocação inválida e liberação da DLL passaram. Uma seleção PNG foi convertida em WEBP pela API real do Shell (`GetUIObjectOf` → `QueryContextMenu` → `InvokeCommand`), com origem e transparência preservadas. Isso não foi contado como clique visual: nesta sessão o Explorador não exibiu o novo item. O Explorador original, responsável por uma cópia de arquivos em andamento, não foi reiniciado. Use **Converter arquivos** na janela do programa enquanto a verificação visual do menu permanece pendente.

## Menu moderno e assinatura

A Microsoft exige identidade de pacote para o `IExplorerCommand` aparecer na primeira camada do menu do Windows 11. Estão incluídos:

- Modelo sparse MSIX em `installer/modern/AppxManifest.xml`; o manifesto final em `.build/generated/AppxManifest.xml` recebe versão, COM class e extensões do catálogo, com COM surrogate e `windows.fileExplorerContextMenus`.
- Metadados de identidade no manifesto do host C#.
- Ícones e um pacote unsigned gerado pelo MakeAppx oficial.
- `tools/package-modern.ps1` para empacotar e, se fornecido um certificado válido, assinar.

O pacote unsigned não é uma edição instalável do menu moderno. Não há certificado de assinatura confiável disponível nesta entrega. Nenhum certificado autoassinado é importado, modo desenvolvedor ativado ou política alterada. A alternativa clássica permanece funcional.

Para uma distribuição assinada, o mantenedor deve usar um certificado cujo Subject corresponda ao Publisher (`CN=Conversor Local`, ou alterar ambos os manifestos para seu Subject). O Windows deve confiar legitimamente na cadeia desse certificado.

```powershell
# Somente na máquina de compilação do mantenedor. Não compartilhe PFX/senha.
./tools/package-modern.ps1 -CertificatePath <certificado.pfx> -CertificatePassword <senha-local>
```

O script deixa o pacote assinado em `.build/app/modern/ConversorLocal-Shell.msix`. O instalador detecta essa variante, registra com `Add-AppxPackage -ExternalLocation <app>` e a remove na desinstalação. A senha não é salva nos arquivos do projeto; prefira uma sessão protegida para assinar.

Limites da validação: empacotamento de um sparse package usa `/nv`, conforme orientação Microsoft para referências externas; isso não equivale a uma instalação assinada. O comportamento da primeira camada no Windows 11 só pode ser afirmado depois de instalar uma edição assinada e testar no Explorador.

Referências primárias:

- [Microsoft: integrar um comando no Explorador](https://learn.microsoft.com/en-us/windows/apps/desktop/modernize/integrate-packaged-app-with-file-explorer)
- [Microsoft: identidade com localização externa e assinatura](https://learn.microsoft.com/en-us/windows/apps/desktop/modernize/grant-identity-to-nonpackaged-apps)
