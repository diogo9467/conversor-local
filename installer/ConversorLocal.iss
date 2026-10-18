#define AppVersion "1.1.0"
#define AppSource "..\.build\app"
#include "..\.build\generated\ShellPayload.iss"
[Setup]
AppId={{DBB2BF33-A215-42BD-B45A-07EC023A892A}
AppName=Conversor Local
AppVersion={#AppVersion}
AppPublisher=Conversor Local
DefaultDirName={localappdata}\Programs\ConversorLocal
DefaultGroupName=Conversor Local
PrivilegesRequired=lowest
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
MinVersion=10.0.19041
OutputDir=..\artifacts
OutputBaseFilename=ConversorLocal-{#AppVersion}-win-x64-Setup
SetupIconFile=..\src\Conversor.App\app.ico
UninstallDisplayIcon={app}\ConversorLocal.exe
Compression=lzma2/fast
SolidCompression=yes
WizardStyle=modern
ChangesAssociations=yes
DisableProgramGroupPage=yes
LicenseFile=..\LICENSE
InfoBeforeFile=..\docs\installation-notes.txt
; Never let Restart Manager close Explorer or unrelated work during an update.
CloseApplications=no
RestartApplications=no
UninstallFilesDir={app}\uninstall

[Languages]
Name: "brazilianportuguese"; MessagesFile: "compiler:Languages\BrazilianPortuguese.isl"

[Tasks]
Name: "contextmenu"; Description: "Adicionar Converter arquivo ao menu de contexto"

[Files]
Source: "{#AppSource}\*"; DestDir: "{app}"; Excludes: "{#ShellDll}"; Flags: ignoreversion recursesubdirs createallsubdirs
Source: "{#AppSource}\{#ShellDll}"; DestDir: "{app}"; Flags: ignoreversion; Check: ShellNeedsUpdate

[Icons]
Name: "{group}\Configurações do Conversor Local"; Filename: "{app}\ConversorLocal.exe"; Parameters: "--settings"
Name: "{group}\Desinstalar Conversor Local"; Filename: "{uninstallexe}"

[Registry]
; Replace only this application's previous COM class; the old mapped DLL need not be overwritten.
Root: HKCU; Subkey: "Software\Classes\CLSID\{{43CF6A2E-7404-4F6B-9D87-C869F5A11C70}"; Flags: deletekey
Root: HKCU; Subkey: "Software\Classes\CLSID\{{E47B5FF3-B025-48CA-A9DD-B56352157252}"; Flags: deletekey
#include "..\.build\generated\FormatRegistry.iss"

[Run]
Filename: "{app}\ConversorLocal.exe"; Parameters: "--register-modern"; Flags: runhidden waituntilterminated; Tasks: contextmenu; Check: FileExists(ExpandConstant('{app}\modern\ConversorLocal-Shell.msix'))

[UninstallRun]
Filename: "{app}\ConversorLocal.exe"; Parameters: "--unregister-modern"; Flags: runhidden waituntilterminated; RunOnceId: "RemoveConversorLocalModern"; Check: FileExists(ExpandConstant('{app}\modern\ConversorLocal-Shell.msix'))

[Code]
function ShellNeedsUpdate(): Boolean;
var
  Path: String;
begin
  Path := ExpandConstant('{app}\{#ShellDll}');
  Result := True;
  if FileExists(Path) then
    try
      Result := CompareText(GetSHA256OfFile(Path), '{#ShellBinarySHA256}') <> 0;
      if not Result then Log('Keeping identical shell DLL; no replacement required.');
    except
      Log('Cannot hash existing shell DLL; normal installation checks apply.');
    end;
end;

function InitializeSetup(): Boolean;
begin
  Result := True;
end;
