$ErrorActionPreference = 'Stop'
$pythonPath = Join-Path $PSScriptRoot '.venv-langchain313\Scripts\python.exe'
& $pythonPath -m PyInstaller --noconfirm (Join-Path $PSScriptRoot 'Weather.spec')
if ($LASTEXITCODE -ne 0) { throw 'Weather executable build failed.' }
Write-Output ('Executable: ' + (Join-Path $PSScriptRoot 'dist\Weather.exe'))
