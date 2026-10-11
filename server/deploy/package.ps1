$ErrorActionPreference = 'Stop'
$taskRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$taskWeb = Join-Path $taskRoot 'web/dist'
if (-not (Test-Path (Join-Path $taskWeb 'index.html'))) {
    throw 'Build web first: cd web; npm run build'
}
$taskOutput = Join-Path $taskRoot 'server/build'
New-Item -ItemType Directory -Force -Path $taskOutput | Out-Null
$taskArchive = Join-Path $taskOutput ('shelest-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.tar.gz')
# Explicit allowlist: no database, credentials, virtualenv, or Android signing keys.
$taskEntries = @(
    'server/manage.py', 'server/requirements.txt', 'server/.env.example',
    'server/README.md', 'server/config', 'server/syncapi', 'server/deploy', 'web/dist'
)
& tar.exe -czf $taskArchive --exclude=__pycache__ --exclude='*.pyc' -C $taskRoot @taskEntries
if ($LASTEXITCODE -ne 0) { throw 'Packaging failed' }
Write-Output $taskArchive
Get-FileHash -LiteralPath $taskArchive -Algorithm SHA256 | Select-Object Hash
