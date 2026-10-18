$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $PSScriptRoot
Push-Location $project
try {
    $env:DOTNET_CLI_HOME = Join-Path $project '.tools\dotnet-home'
    $env:NUGET_PACKAGES = Join-Path $project '.cache\nuget'
    $env:DOTNET_CLI_TELEMETRY_OPTOUT = '1'
    $env:DOTNET_GENERATE_ASPNET_CERTIFICATE = 'false'
    $env:PYTHONUTF8 = '1'
    New-Item -ItemType Directory -Force -Path test-results | Out-Null
    & .tools/dotnet/dotnet.exe run --project tests/Conversor.Core.Tests/Conversor.Core.Tests.csproj -c Release
    if ($LASTEXITCODE -ne 0) { throw 'Testes de publicação falharam.' }
    & .tools/build-python/Scripts/python.exe -m pytest tests/test_integration.py tests/test_ocr_engine.py tests/test_external_templates.py tests/test_extra_formats.py -v --junitxml=test-results/integration.xml --basetemp=test-results/pytest-temp
    if ($LASTEXITCODE -ne 0) { throw 'Testes de integração falharam.' }
    & .tools/dotnet/dotnet.exe run --project tests/Conversor.Windows.Tests/Conversor.Windows.Tests.csproj -c Release -- (Join-Path $project '.build\app') (Join-Path $project 'test-results\fixtures\Pasta A com acentos\Pesquisa com texto e tabelas.pdf')
    if ($LASTEXITCODE -ne 0) { throw 'Testes de cancelamento/ACL falharam.' }
    & .tools/w64devkit/bin/g++.exe -std=c++17 -O2 -static -static-libgcc -static-libstdc++ -municode -I .build/generated tests/ShellTests.cpp -o .build/ShellTests.exe -lole32 -lshell32 -luuid
    if ($LASTEXITCODE -ne 0) { throw 'Compilação dos testes COM falhou.' }
    $catalog = Get-Content formats.json -Raw | ConvertFrom-Json
    & .build/ShellTests.exe (Join-Path $project ('.build\app\' + $catalog.shell.dll)) (Join-Path $project 'test-results\fixtures')
    if ($LASTEXITCODE -ne 0) { throw 'Testes da extensão COM falharam.' }
    & .tools/build-python/Scripts/python.exe tools/render_evidence.py
}
finally { Pop-Location }
