param([string]$InstallDirectory = (Join-Path ([Environment]::GetFolderPath('LocalApplicationData')) 'Programs\ConversorLocal'))
$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $PSScriptRoot
$appRoot = [IO.Path]::GetFullPath($InstallDirectory).TrimEnd('\')
$delta = Get-Content -LiteralPath (Join-Path $project 'test-results\external-links-fix\engine-delta.json') -Raw | ConvertFrom-Json -AsHashtable
$backupRoot = Join-Path $project '.build\backups\external-template-fix\installed'
$changes = @()
foreach ($relative in $delta.changed) {
    $target = [IO.Path]::GetFullPath((Join-Path $appRoot ('engine\' + $relative)))
    if (!$target.StartsWith(($appRoot + '\engine\'), [StringComparison]::OrdinalIgnoreCase)) { throw 'Caminho fora do motor instalado.' }
    $source = Join-Path $project ('.build\app\engine\' + $relative)
    $expected = $delta.after[$relative]
    if ((Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expected) { throw 'Pacote de atualização alterado.' }
    $existing = if(Test-Path -LiteralPath $target){(Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash.ToLowerInvariant()}else{$null}
    if ($existing -eq $expected) { continue }
    if ($existing -ne $delta.before[$relative]) { throw ('O motor instalado diverge da versão testada: ' + $relative) }
    $backup = Join-Path $backupRoot $relative
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $backup) | Out-Null
    Copy-Item -LiteralPath $target -Destination $backup -Force
    $changes += @{ relative=$relative; source=$source; target=$target; backup=$backup; hash=$expected }
}
$applied = @()
try {
    foreach ($change in $changes) {
        $temporary = $change.target + '.template-fix-new'
        Copy-Item -LiteralPath $change.source -Destination $temporary -Force
        if ((Get-FileHash -LiteralPath $temporary -Algorithm SHA256).Hash.ToLowerInvariant() -ne $change.hash) { throw 'Cópia incompleta da atualização.' }
        [IO.File]::Replace($temporary,$change.target,$change.backup)
        $applied += $change
    }
    foreach ($change in $changes) {
        if ((Get-FileHash -LiteralPath $change.target -Algorithm SHA256).Hash.ToLowerInvariant() -ne $change.hash) { throw 'Validação do motor instalado falhou.' }
    }
}
catch {
    foreach ($change in $applied) { Copy-Item -LiteralPath $change.backup -Destination $change.target -Force }
    throw
}
finally {
    foreach ($change in $changes) { $temporary=$change.target+'.template-fix-new'; if(Test-Path -LiteralPath $temporary){Remove-Item -LiteralPath $temporary} }
}
$result = @{ version='1.0.1'; engine_files_updated=@($changes | ForEach-Object { $_.relative }); hashes_verified=$true; shell_changed=$false; original_documents_changed=$false }
$result | ConvertTo-Json -Depth 3 | Set-Content -LiteralPath (Join-Path $project 'test-results\external-links-fix\update.json') -Encoding utf8
Write-Output ($result | ConvertTo-Json -Depth 3)
