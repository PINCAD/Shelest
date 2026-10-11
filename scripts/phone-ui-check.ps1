param([string]$Label='', [string]$Screenshot='current', [ValidateSet('none','up','down')][string]$Swipe='none', [switch]$LongPress, [switch]$Back, [string]$QaDirectory='artifacts/phone-qa-20261010', [string]$Serial='RFCXA1F5N6M')
$ErrorActionPreference='Stop'
$taskAdb='C:/Users/leone/AppData/Local/Android/Sdk/platform-tools/adb.exe'
$taskSerial=$Serial
$taskRoot=Split-Path -Parent $PSScriptRoot
$taskQa=Join-Path $taskRoot $QaDirectory
New-Item -ItemType Directory -Path $taskQa -Force | Out-Null
function Read-PhoneUi {
    & $taskAdb -s $taskSerial shell uiautomator dump /sdcard/shelest-qa.xml | Out-Null
    & $taskAdb -s $taskSerial pull /sdcard/shelest-qa.xml (Join-Path $taskQa 'current.xml') 2>$null | Out-Null
    return [xml](Get-Content (Join-Path $taskQa 'current.xml') -Raw)
}
$taskUi=Read-PhoneUi
$taskNodes=@($taskUi.SelectNodes('//node') | Where-Object { $_.package -eq 'com.lexiread.app' })
if (!$taskNodes.Count) { throw 'Shelest is not the foreground application; no action performed' }
if ($Back) { & $taskAdb -s $taskSerial shell input keyevent 4; Start-Sleep -Milliseconds 700 }
if ($Label) {
    $taskNode=$taskNodes | Where-Object {
        $taskRect = [regex]::Matches($_.bounds,'\d+') | ForEach-Object { [int]$_.Value }
        ($_.text -eq $Label -or $_.'content-desc' -eq $Label) -and $taskRect.Count -eq 4 -and
            $taskRect[2] -gt $taskRect[0] -and $taskRect[3] -gt $taskRect[1]
    } | Select-Object -First 1
    if (!$taskNode) { throw "Visible UI element not found: $Label" }
    $taskBounds=[regex]::Matches($taskNode.bounds,'\d+') | ForEach-Object { [int]$_.Value }
    $taskX=[int](($taskBounds[0]+$taskBounds[2])/2); $taskY=[int](($taskBounds[1]+$taskBounds[3])/2)
    if ($LongPress) { & $taskAdb -s $taskSerial shell input swipe $taskX $taskY $taskX $taskY 800 }
    else { & $taskAdb -s $taskSerial shell input tap $taskX $taskY }
    Start-Sleep -Milliseconds 700
}
if ($Swipe -ne 'none') {
    $taskSize=[regex]::Matches((& $taskAdb -s $taskSerial shell wm size | Out-String),'(\d+)x(\d+)') | Select-Object -Last 1
    $taskX=[int]([int]$taskSize.Groups[1].Value * .5)
    $taskLow=[int]([int]$taskSize.Groups[2].Value * .85); $taskHigh=[int]([int]$taskSize.Groups[2].Value * .4)
    if ($Swipe -eq 'up') { & $taskAdb -s $taskSerial shell input swipe $taskX $taskLow $taskX $taskHigh 400 }
    else { & $taskAdb -s $taskSerial shell input swipe $taskX $taskHigh $taskX $taskLow 400 }
    Start-Sleep -Milliseconds 500
}
$taskUi=Read-PhoneUi
$taskUi.SelectNodes('//node') | Where-Object {
    $taskRect = [regex]::Matches($_.bounds,'\d+') | ForEach-Object { [int]$_.Value }
    $_.package -eq 'com.lexiread.app' -and $taskRect.Count -eq 4 -and
        $taskRect[2] -gt $taskRect[0] -and $taskRect[3] -gt $taskRect[1] -and
        ($_.text -or $_.'content-desc')
} | ForEach-Object { '{0} | {1} | {2}' -f ($_.text.Substring(0,[Math]::Min(120,$_.text.Length))),$_.'content-desc',$_.bounds }
& $taskAdb -s $taskSerial shell screencap -p /sdcard/shelest-qa.png
& $taskAdb -s $taskSerial pull /sdcard/shelest-qa.png (Join-Path $taskQa "$Screenshot.png") 2>$null | Out-Null
