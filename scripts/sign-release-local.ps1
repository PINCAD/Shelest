param(
    [Parameter(Mandatory=$true)][string]$KeyStore,
    [string]$Alias = 'key0',
    [Parameter(Mandatory=$true)][string]$UnsignedApk,
    [Parameter(Mandatory=$true)][string]$OutputApk,
    [Parameter(Mandatory=$true)][string]$StatusFile
)
$ErrorActionPreference = 'Stop'
function Ask-SigningPasswords {
    $form = New-Object System.Windows.Forms.Form
    $form.Text = 'Шелест: подпись обновления'
    $form.Size = New-Object System.Drawing.Size(440, 270)
    $form.StartPosition = 'CenterScreen'
    $form.FormBorderStyle = 'FixedDialog'
    $form.MaximizeBox = $false
    $form.MinimizeBox = $false
    $form.TopMost = $true
    $hint = New-Object System.Windows.Forms.Label
    $hint.Text = "Пароли используются только локально для подписи APK.`nОни не сохраняются и не передаются в чат или сеть."
    $hint.SetBounds(18, 15, 398, 40)
    $form.Controls.Add($hint)
    $fields = @()
    $labels = @('Пароль хранилища ключей', 'Пароль ключа (если другой)')
    for ($index = 0; $index -lt 2; $index++) {
        $label = New-Object System.Windows.Forms.Label
        $label.Text = $labels[$index]
        $label.SetBounds(18, (64 + 56 * $index), 398, 20)
        $inputField = New-Object System.Windows.Forms.TextBox
        $inputField.UseSystemPasswordChar = $true
        $inputField.SetBounds(18, (85 + 56 * $index), 398, 24)
        $fields += $inputField
        $form.Controls.Add($label)
        $form.Controls.Add($inputField)
    }
    $submit = New-Object System.Windows.Forms.Button
    $submit.Text = 'Подписать'
    $submit.SetBounds(280, 185, 136, 32)
    $submit.DialogResult = [System.Windows.Forms.DialogResult]::OK
    $form.Controls.Add($submit)
    $form.AcceptButton = $submit
    try {
        if ($form.ShowDialog() -ne [System.Windows.Forms.DialogResult]::OK) { throw 'Подпись отменена пользователем.' }
        if (!$fields[0].Text) { throw 'Не указан пароль хранилища.' }
        return @($fields[0].Text, $(if ($fields[1].Text) { $fields[1].Text } else { $fields[0].Text }))
    } finally { $form.Dispose() }
}
try {
    Add-Type -AssemblyName System.Windows.Forms
    Add-Type -AssemblyName System.Drawing
    foreach ($inputPath in @($KeyStore, $UnsignedApk)) {
        if (!(Test-Path -LiteralPath $inputPath -PathType Leaf)) { throw "Файл не найден: $inputPath" }
    }
    # Reject quotes/newlines before constructing Windows process arguments.
    foreach ($argument in @($KeyStore, $Alias, $UnsignedApk, $OutputApk)) {
        if ($argument -match '["\r\n]') { throw 'Недопустимый символ в аргументах подписи.' }
    }
    $secrets = Ask-SigningPasswords
    $java = 'C:\Program Files\Android\Android Studio\jbr\bin\java.exe'
    $jar = 'C:\Users\leone\AppData\Local\Android\Sdk\build-tools\36.1.0\lib\apksigner.jar'
    $start = New-Object System.Diagnostics.ProcessStartInfo
    $start.FileName = $java
    $start.Arguments = "-jar `"$jar`" sign --ks `"$KeyStore`" --ks-key-alias `"$Alias`" --ks-pass stdin --key-pass stdin --out `"$OutputApk`" `"$UnsignedApk`""
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardInput = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    $process = [System.Diagnostics.Process]::Start($start)
    $process.StandardInput.WriteLine($secrets[0])
    $process.StandardInput.WriteLine($secrets[1])
    $process.StandardInput.Close()
    $secrets = $null
    $stdout = $process.StandardOutput.ReadToEndAsync()
    $stderr = $process.StandardError.ReadToEndAsync()
    $process.WaitForExit()
    if ($process.ExitCode -ne 0) { throw 'Не удалось подписать APK. Проверьте пароли хранилища и ключа.' }
    $certificate = & $java -jar $jar verify --print-certs $OutputApk 2>&1
    if ($LASTEXITCODE -ne 0 -or ($certificate -join "`n") -notmatch '7045398b2c08a02e3d22f7ccfced88880128887e945373d47045a9efef199d20') {
        throw 'Подпись не совпадает с установленной релизной версией. Установка запрещена.'
    }
    @{ ok=$true; apk=$OutputApk; sha256=(Get-FileHash -LiteralPath $OutputApk -Algorithm SHA256).Hash.ToLower() } |
        ConvertTo-Json | Set-Content -LiteralPath $StatusFile -Encoding UTF8
    [System.Windows.Forms.MessageBox]::Show('Подпись проверена. Сборка готова к установке поверх текущего приложения.', 'Шелест') | Out-Null
} catch {
    @{ ok=$false; error=$_.Exception.Message } | ConvertTo-Json | Set-Content -LiteralPath $StatusFile -Encoding UTF8
    if ('System.Windows.Forms.MessageBox' -as [type]) {
        [System.Windows.Forms.MessageBox]::Show($_.Exception.Message, 'Шелест: подпись не выполнена') | Out-Null
    }
}
