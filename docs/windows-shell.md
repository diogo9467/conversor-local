# Menu de contexto do Windows

## Integração entregue

`Conversor.Shell.dll` é uma DLL nativa x64 com `IExplorerCommand`, `IEnumExplorerCommand`, `DllGetClassObject` e `DllCanUnloadNow`. O estado dos comandos depende da extensão de todos os arquivos selecionados; o trabalho pesado começa somente em outro processo, após `Invoke`.

O instalador registra a COM class `{43CF6A2E-7404-4F6B-9D87-C869F5A11C70}` e `ExplorerCommandHandler` nas chaves próprias `HKCU/Software/Classes/SystemFileAssociations/.pdf|.docx|.pptx/shell/ConversorLocal`. Não usa associação padrão nem altera as políticas do Explorador. A desinstalação remove essas chaves e a classe COM.

Windows 10 usa esse menu. No Windows 11, abra **Mostrar mais opções**. A extensão usa a seleção real `IShellItemArray`, inclusive para lotes, e despacha todos os caminhos por um arquivo JSON, sem executar um comando por arquivo.

## Menu moderno e assinatura

A Microsoft exige identidade de pacote para o `IExplorerCommand` aparecer na primeira camada do menu do Windows 11. Estão incluídos:

- Manifesto sparse MSIX em `installer/modern/AppxManifest.xml` com COM surrogate e `windows.fileExplorerContextMenus`.
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
