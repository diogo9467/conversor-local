#define AppVersion "1.0.2"
#define AppSource "..\.build\app"
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
CloseApplications=yes
RestartApplications=no
UninstallFilesDir={app}\uninstall

[Languages]
Name: "brazilianportuguese"; MessagesFile: "compiler:Languages\BrazilianPortuguese.isl"

[Tasks]
Name: "contextmenu"; Description: "Adicionar Converter arquivo ao menu de contexto"; Flags: checkedonce

[Files]
Source: "{#AppSource}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{group}\Configurações do Conversor Local"; Filename: "{app}\ConversorLocal.exe"; Parameters: "--settings"
Name: "{group}\Desinstalar Conversor Local"; Filename: "{uninstallexe}"

[Registry]
Root: HKCU; Subkey: "Software\Classes\CLSID\{{43CF6A2E-7404-4F6B-9D87-C869F5A11C70}"; ValueType: string; ValueName: ""; ValueData: "Conversor Local"; Flags: uninsdeletekey; Tasks: contextmenu
Root: HKCU; Subkey: "Software\Classes\CLSID\{{43CF6A2E-7404-4F6B-9D87-C869F5A11C70}\InprocServer32"; ValueType: string; ValueName: ""; ValueData: "{app}\Conversor.Shell.dll"; Tasks: contextmenu
Root: HKCU; Subkey: "Software\Classes\CLSID\{{43CF6A2E-7404-4F6B-9D87-C869F5A11C70}\InprocServer32"; ValueType: string; ValueName: "ThreadingModel"; ValueData: "Apartment"; Tasks: contextmenu
Root: HKCU; Subkey: "Software\Classes\SystemFileAssociations\.pdf\shell\ConversorLocal"; ValueType: string; ValueName: "MUIVerb"; ValueData: "Converter arquivo"; Flags: uninsdeletekey; Tasks: contextmenu
Root: HKCU; Subkey: "Software\Classes\SystemFileAssociations\.pdf\shell\ConversorLocal"; ValueType: string; ValueName: "ExplorerCommandHandler"; ValueData: "{{43CF6A2E-7404-4F6B-9D87-C869F5A11C70}"; Tasks: contextmenu
Root: HKCU; Subkey: "Software\Classes\SystemFileAssociations\.pdf\shell\ConversorLocal"; ValueType: string; ValueName: "MultiSelectModel"; ValueData: "Player"; Tasks: contextmenu
Root: HKCU; Subkey: "Software\Classes\SystemFileAssociations\.docx\shell\ConversorLocal"; ValueType: string; ValueName: "MUIVerb"; ValueData: "Converter arquivo"; Flags: uninsdeletekey; Tasks: contextmenu
Root: HKCU; Subkey: "Software\Classes\SystemFileAssociations\.docx\shell\ConversorLocal"; ValueType: string; ValueName: "ExplorerCommandHandler"; ValueData: "{{43CF6A2E-7404-4F6B-9D87-C869F5A11C70}"; Tasks: contextmenu
Root: HKCU; Subkey: "Software\Classes\SystemFileAssociations\.docx\shell\ConversorLocal"; ValueType: string; ValueName: "MultiSelectModel"; ValueData: "Player"; Tasks: contextmenu
Root: HKCU; Subkey: "Software\Classes\SystemFileAssociations\.pptx\shell\ConversorLocal"; ValueType: string; ValueName: "MUIVerb"; ValueData: "Converter arquivo"; Flags: uninsdeletekey; Tasks: contextmenu
Root: HKCU; Subkey: "Software\Classes\SystemFileAssociations\.pptx\shell\ConversorLocal"; ValueType: string; ValueName: "ExplorerCommandHandler"; ValueData: "{{43CF6A2E-7404-4F6B-9D87-C869F5A11C70}"; Tasks: contextmenu
Root: HKCU; Subkey: "Software\Classes\SystemFileAssociations\.pptx\shell\ConversorLocal"; ValueType: string; ValueName: "MultiSelectModel"; ValueData: "Player"; Tasks: contextmenu

[Run]
Filename: "{app}\ConversorLocal.exe"; Parameters: "--register-modern"; Flags: runhidden waituntilterminated; Tasks: contextmenu; Check: FileExists(ExpandConstant('{app}\modern\ConversorLocal-Shell.msix'))

[UninstallRun]
Filename: "{app}\ConversorLocal.exe"; Parameters: "--unregister-modern"; Flags: runhidden waituntilterminated; RunOnceId: "RemoveConversorLocalModern"; Check: FileExists(ExpandConstant('{app}\modern\ConversorLocal-Shell.msix'))

[Code]
function InitializeSetup(): Boolean;
begin
  Result := True;
end;
