$ErrorActionPreference = "Stop"

$serverDir = $PSScriptRoot
$python = Join-Path $serverDir ".venv\Scripts\python.exe"
$venvReady = Test-Path -LiteralPath $python

if (-not $venvReady) {
    if (-not (Get-Command py -ErrorAction SilentlyContinue)) {
        throw "Python 3 is required. Install Python 3.12+ or set it up in a virtual environment first."
    }
    py -3 -m venv (Join-Path $serverDir ".venv")
}

$depsMarker = Join-Path $serverDir ".venv\.requirements-installed"
if (-not (Test-Path -LiteralPath $depsMarker)) {
    if ($env:SHELEST_PIP_PYTHON) {
        # Supports embedded Python distributions whose venv omits ensurepip.
        & $env:SHELEST_PIP_PYTHON -m pip --python $python install -r (Join-Path $serverDir "requirements.txt")
    } else {
        & $python -m pip install --upgrade pip
        if ($LASTEXITCODE -ne 0) { throw "pip upgrade failed" }
        & $python -m pip install -r (Join-Path $serverDir "requirements.txt")
    }
    if ($LASTEXITCODE -ne 0) { throw "Server dependency installation failed" }
    Set-Content -LiteralPath $depsMarker -Value "installed" -NoNewline
}

if (-not $env:SHELEST_SECRET_KEY) {
    $env:SHELEST_SECRET_KEY = & $python -c "import secrets; print(secrets.token_urlsafe(50))"
}
$env:SHELEST_DEBUG = "true"
$env:SHELEST_ALLOWED_HOSTS = "127.0.0.1,localhost"
$env:SHELEST_FORCE_HTTPS = "false"
$env:SHELEST_DATA_DIR = Join-Path $serverDir "var"
$env:SHELEST_GOOGLE_CLIENT_IDS = ""

Push-Location $serverDir
try {
    & $python manage.py migrate --noinput
    if ($LASTEXITCODE -ne 0) { throw "Database migration failed" }
    Write-Host "Local API: http://127.0.0.1:8000/api/v1/health"
    Write-Host "To create an admin in another PowerShell window, run: .\server\create-admin.ps1"
    & $python manage.py runserver 127.0.0.1:8000 --noreload
    if ($LASTEXITCODE -ne 0) { throw "Django local server exited with an error" }
}
finally {
    Pop-Location
    Remove-Item Env:SHELEST_SECRET_KEY -ErrorAction SilentlyContinue
    Remove-Item Env:SHELEST_DEBUG -ErrorAction SilentlyContinue
    Remove-Item Env:SHELEST_ALLOWED_HOSTS -ErrorAction SilentlyContinue
    Remove-Item Env:SHELEST_FORCE_HTTPS -ErrorAction SilentlyContinue
    Remove-Item Env:SHELEST_DATA_DIR -ErrorAction SilentlyContinue
    Remove-Item Env:SHELEST_GOOGLE_CLIENT_IDS -ErrorAction SilentlyContinue
}
