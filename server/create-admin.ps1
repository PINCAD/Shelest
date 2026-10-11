$ErrorActionPreference = "Stop"

$serverDir = $PSScriptRoot
$python = Join-Path $serverDir ".venv\Scripts\python.exe"
if (-not (Test-Path -LiteralPath $python)) {
    throw "Local environment is not installed yet. Start it once with .\server\run-local.ps1"
}

# Django probes the Windows Terminal registry for color support during startup. ANSICON skips
# that probe in restricted environments; it does not enable or change any terminal setting.
$env:ANSICON = "1"
$env:SHELEST_SECRET_KEY = "local-admin-management-only"
$env:SHELEST_DEBUG = "true"
$env:SHELEST_ALLOWED_HOSTS = "127.0.0.1,localhost"
$env:SHELEST_FORCE_HTTPS = "false"
$env:SHELEST_DATA_DIR = Join-Path $serverDir "var"

Push-Location $serverDir
try {
    & $python manage.py createsuperuser
    if ($LASTEXITCODE -ne 0) { throw "Administrator creation failed" }
}
finally {
    Pop-Location
    Remove-Item Env:ANSICON,Env:SHELEST_SECRET_KEY,Env:SHELEST_DEBUG,Env:SHELEST_ALLOWED_HOSTS,Env:SHELEST_FORCE_HTTPS,Env:SHELEST_DATA_DIR -ErrorAction SilentlyContinue
}
