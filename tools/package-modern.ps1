param([string]$CertificatePath, [string]$CertificatePassword)
$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $PSScriptRoot
$staging = Join-Path $project '.build\modern'
New-Item -ItemType Directory -Force -Path $staging | Out-Null
Copy-Item -LiteralPath (Join-Path $project 'installer\modern\AppxManifest.xml') -Destination $staging
Copy-Item -LiteralPath (Join-Path $project 'installer\Assets') -Destination $staging -Recurse -Force
$makeAppx = Get-ChildItem -LiteralPath (Join-Path $project '.tools\windows-sdk') -Filter makeappx.exe -Recurse | Where-Object FullName -Match '\\x64\\' | Select-Object -First 1
if (!$makeAppx) { throw 'MakeAppx não encontrado. Execute tools/dependencies.py.' }
$package = Join-Path $project 'artifacts\ConversorLocal-Shell-1.0.0-x64-unsigned.msix'
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $package) | Out-Null
& $makeAppx.FullName pack /o /d $staging /nv /p $package
if ($LASTEXITCODE -ne 0) { throw 'Falha ao gerar o pacote MSIX.' }
if ($CertificatePath) {
    $signTool = Get-ChildItem -LiteralPath (Join-Path $project '.tools\windows-sdk') -Filter signtool.exe -Recurse | Where-Object FullName -Match '\\x64\\' | Select-Object -First 1
    & $signTool.FullName sign /fd SHA256 /f $CertificatePath /p $CertificatePassword $package
    if ($LASTEXITCODE -ne 0) { throw 'Assinatura do MSIX falhou.' }
    $signed = Join-Path $project '.build\app\modern\ConversorLocal-Shell.msix'
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $signed) | Out-Null
    Copy-Item -LiteralPath $package -Destination $signed -Force
    Write-Output 'Pacote assinado preparado. Nenhum certificado foi confiado ou importado no Windows.'
}
