param([string]$Python = 'python')
$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $PSScriptRoot
Push-Location $project
try {
    & $Python -c "import sys; assert sys.version_info[:2] == (3,12), 'Use Python 3.12 x64 para recompilar'"
    if ($LASTEXITCODE -ne 0) { throw 'Python 3.12 x64 necessário somente para desenvolvimento.' }
    & $Python tools/bootstrap.py
    if ($LASTEXITCODE -ne 0) { throw 'Falha ao provisionar ferramentas.' }
    & $Python -m venv .tools/build-python
    if ($LASTEXITCODE -ne 0) { throw 'Falha ao criar ambiente Python de compilação.' }
    & .tools/build-python/Scripts/python.exe -m pip install -r tools/requirements-build.lock.txt
    if ($LASTEXITCODE -ne 0) { throw 'Falha ao preparar bibliotecas.' }
    $target = Join-Path $project '.build\lo-extracted'
    New-Item -ItemType Directory -Force -Path $target | Out-Null
    if (!(Test-Path -LiteralPath (Join-Path $target 'program\soffice.com'))) {
        $msi = Join-Path $project '.cache\LibreOffice_26.2.6_Win_x86-64.msi'
        $log = Join-Path $project '.build\lo-extract.log'
        $process = Start-Process -FilePath msiexec.exe -ArgumentList @('/a', ('"' + $msi + '"'), '/qn', ('TARGETDIR="' + $target + '"'), '/L*v', ('"' + $log + '"')) -WindowStyle Hidden -Wait -PassThru
        if ($process.ExitCode -ne 0) { throw "Extração privada do LibreOffice falhou: $($process.ExitCode). Consulte $log" }
    }
    & .tools/build-python/Scripts/python.exe tools/dependencies.py
    if ($LASTEXITCODE -ne 0) { throw 'Falha ao preparar OCR/licenças/fontes.' }
    Write-Output 'Ferramentas prontas. Execute .tools/build-python/Scripts/python.exe tools/build.py.'
}
finally { Pop-Location }
