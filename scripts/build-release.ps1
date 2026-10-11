param([switch]$BrowserTests, [switch]$CheckOnly, [string]$GradleExecutable = $env:GRADLE_EXECUTABLE)
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
$webRoot = Join-Path $taskRoot 'web'
function Run-Checked([string]$Executable, [string[]]$Arguments) {
    & $Executable @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Command failed: $Executable (exit $LASTEXITCODE)" }
}
if (-not $env:JAVA_HOME -and (Test-Path -LiteralPath 'C:/Program Files/Android/Android Studio/jbr')) {
    $env:JAVA_HOME = 'C:/Program Files/Android/Android Studio/jbr'
}
if (-not $GradleExecutable) {
    $wrapper = Join-Path $taskRoot 'gradlew.bat'
    if (Test-Path -LiteralPath $wrapper) { $GradleExecutable = $wrapper }
    else {
        $properties = Get-Content -LiteralPath (Join-Path $taskRoot 'gradle/wrapper/gradle-wrapper.properties') -Raw
        $gradleVersion = [regex]::Match($properties, 'gradle-([\d.]+)-bin').Groups[1].Value
        $gradleUserRoot = if ($env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME } else { Join-Path $env:USERPROFILE '.gradle' }
        $gradleCache = Join-Path $gradleUserRoot "wrapper/dists/gradle-$gradleVersion-bin"
        if (Test-Path -LiteralPath $gradleCache) {
            $GradleExecutable = Get-ChildItem -LiteralPath $gradleCache -Directory | ForEach-Object {
                Join-Path $_.FullName "gradle-$gradleVersion/bin/gradle.bat"
            } | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
        }
    }
}
if (-not $GradleExecutable) { throw 'Install Gradle or set GRADLE_EXECUTABLE' }
if (-not (Get-Command $GradleExecutable -ErrorAction SilentlyContinue)) { throw "Gradle executable not found: $GradleExecutable" }
if (-not $env:JAVA_HOME -or -not (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin/java.exe'))) {
    throw 'Set JAVA_HOME to a compatible JDK (Android Studio JBR on this machine)'
}
foreach ($command in @('node', 'npm.cmd', 'tar')) {
    if (-not (Get-Command $command -ErrorAction SilentlyContinue)) { throw "Required command not found: $command" }
}
$nodeMajor = [int]((& node --version).TrimStart('v').Split('.')[0])
if ($LASTEXITCODE -ne 0 -or $nodeMajor -lt 20) { throw 'Node.js 20 or newer is required' }
foreach ($dependency in @('vite/bin/vite.js', 'typescript/bin/tsc', 'react/package.json')) {
    if (-not (Test-Path -LiteralPath (Join-Path $webRoot "node_modules/$dependency"))) {
        throw 'Missing web dependencies: run npm ci in web/ first (requires Gradle/JDK too)'
    }
}
if ($BrowserTests -and (-not $env:PLAYWRIGHT_MODULE -or -not $env:BROWSER_EXECUTABLE)) {
    throw 'Browser tests require PLAYWRIGHT_MODULE and BROWSER_EXECUTABLE (isolated headless profile only)'
}
if ($BrowserTests) {
    if (-not (Test-Path -LiteralPath $env:BROWSER_EXECUTABLE -PathType Leaf)) { throw 'BROWSER_EXECUTABLE does not exist' }
    Run-Checked 'node' @('-e', 'require(process.env.PLAYWRIGHT_MODULE)')
}
$androidBuild = Get-Content -LiteralPath (Join-Path $taskRoot 'app/build.gradle.kts') -Raw
$versionName = [regex]::Match($androidBuild, 'versionName\s*=\s*"([^"]+)"').Groups[1].Value
$versionCode = [int][regex]::Match($androidBuild, 'versionCode\s*=\s*(\d+)').Groups[1].Value
if (-not $versionName -or $versionCode -lt 1) { throw 'Missing Android release version' }
if ($CheckOnly) {
    Write-Output "Build environment ready: Android $versionName ($versionCode), Node $nodeMajor, browser tests: $($BrowserTests.IsPresent)"
    Write-Output "Gradle: $GradleExecutable"
    Write-Output 'No build, device installation or publication performed.'
    return
}
$stamp = (Get-Date).ToUniversalTime().ToString('yyyyMMdd-HHmmss')
$releaseRoot = Join-Path $taskRoot "artifacts/release-$versionName-$stamp"
New-Item -ItemType Directory -Path $releaseRoot | Out-Null
$previewProcess = $null
$oldUiUrl = $env:UI_TEST_URL
$oldReaderUrl = $env:READER_TEST_URL
Push-Location $taskRoot
try {
    Run-Checked $GradleExecutable @(':app:assembleDebug', ':app:assembleDebugAndroidTest', ':app:testDebugUnitTest', ':shared:jvmTest', ':shared:jsBrowserProductionLibraryDistribution', ':shared-ui:jsBrowserProductionLibraryDistribution', '--console=plain')
    Push-Location $webRoot
    try {
        # This pipeline builds a debug APK for QA, not a production update.
        # Preserve the owner's signed public APK and its matching manifest.
        # Replace downloads only through the separate verified release workflow.
        Write-Output 'Keeping the existing signed download; the QA debug APK is not published.'
        Run-Checked 'npm.cmd' @('exec', 'tsc', '--', '-b')
        Run-Checked 'npm.cmd' @('exec', 'vite', '--', 'build')
    } finally { Pop-Location }
    foreach ($test in @('shared-core.test.mjs','sync-core.test.mjs','anchor.test.mjs','cloud-position.test.mjs','personal-sync.test.mjs','collections.test.mjs')) {
        Run-Checked 'node' @((Join-Path $webRoot "tests/$test"))
    }
    if ($BrowserTests) {
        $nodePath = (Get-Command node -ErrorAction Stop).Source
        $vitePath = Join-Path $webRoot 'node_modules/vite/bin/vite.js'
        $previewProcess = Start-Process -FilePath $nodePath -ArgumentList @(('"' + $vitePath + '"'),'preview','--host','127.0.0.1','--port','5181','--strictPort') -WorkingDirectory $webRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $releaseRoot 'preview.stdout.log') -RedirectStandardError (Join-Path $releaseRoot 'preview.stderr.log')
        $address = 'http://127.0.0.1:5181/'
        $ready = $false
        for ($i = 0; $i -lt 40 -and -not $ready; $i++) {
            if ($previewProcess.HasExited) { throw 'Preview server failed; inspect release preview logs' }
            try { $ready = (Invoke-WebRequest -Uri $address -TimeoutSec 1 -UseBasicParsing).StatusCode -eq 200 } catch { Start-Sleep -Milliseconds 250 }
        }
        if (-not $ready) { throw 'Preview server did not start' }
        $env:UI_TEST_URL = $address; $env:READER_TEST_URL = $address
        foreach ($test in @('common-account-ui.test.cjs','common-workspace-ui.test.cjs','shared-reader-production.test.cjs','collections-ui.test.cjs','visual-interactions.test.cjs')) {
            Run-Checked 'node' @((Join-Path $webRoot "tests/$test"))
        }
        Run-Checked 'node' @((Join-Path $taskRoot 'app/tests/paged-reader.test.cjs'))
    }
    $apk = Join-Path $releaseRoot "shelest-$versionName-debug.apk"
    Copy-Item -LiteralPath (Join-Path $taskRoot 'app/build/outputs/apk/debug/app-debug.apk') -Destination $apk
    $archive = Join-Path $releaseRoot "shelest-web-$versionName.tar.gz"
    Run-Checked 'tar' @('-czf',$archive,'-C',(Join-Path $webRoot 'dist'),'.')
    $manifest = [ordered]@{
        versionName=$versionName; versionCode=$versionCode; builtAtUtc=$stamp; variant='debug'; signing='existing Android debug key';
        apk=[ordered]@{file=(Split-Path -Leaf $apk);sha256=(Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLower()};
        web=[ordered]@{file=(Split-Path -Leaf $archive);sha256=(Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLower();indexSha256=(Get-FileHash -LiteralPath (Join-Path $webRoot 'dist/index.html') -Algorithm SHA256).Hash.ToLower()};
        verified=[ordered]@{androidJvm=$true;sharedJvm=$true;compiledJs=$true;webTypeScript=$true;browser=$BrowserTests.IsPresent;androidInstrumentation=$false};
        deploy='Static-only, explicit publication with server/deploy/publish-web.sh ARCHIVE SHA256; never uploads automatically'
    }
    $manifest | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $releaseRoot 'manifest.json') -Encoding utf8
    Write-Output "Verified local artifacts: $releaseRoot"
} finally {
    if ($previewProcess -and -not $previewProcess.HasExited) { Stop-Process -Id $previewProcess.Id }
    $env:UI_TEST_URL = $oldUiUrl; $env:READER_TEST_URL = $oldReaderUrl
    Pop-Location
}
