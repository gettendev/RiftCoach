$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$shots = "$env:TEMP\rc\shots"
New-Item -ItemType Directory -Force $shots | Out-Null

function Get-Ui {
    & $adb shell uiautomator dump /sdcard/ui.xml | Out-Null
    $raw = (& $adb shell cat /sdcard/ui.xml) -join ""
    return [xml]$raw
}

function Show-Ui {
    $x = Get-Ui
    $x.SelectNodes("//node") | Where-Object { $_.text -ne "" -or $_.'content-desc' -ne "" } | ForEach-Object {
        "{0} | {1} | {2} | {3}" -f $_.text, $_.'content-desc', $_.class.Split('.')[-1], $_.bounds
    }
}

function Tap-Text([string]$pattern) {
    $x = Get-Ui
    $n = $x.SelectNodes("//node") | Where-Object { $_.text -match $pattern -or $_.'content-desc' -match $pattern } | Select-Object -First 1
    if (-not $n) { Write-Host "NAO ACHEI: $pattern"; return }
    if ($n.bounds -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {
        $cx = [int](([int]$Matches[1] + [int]$Matches[3]) / 2)
        $cy = [int](([int]$Matches[2] + [int]$Matches[4]) / 2)
        & $adb shell input tap $cx $cy
        Write-Host "toquei em '$($n.text)' ($cx,$cy)"
    }
}

function Shot([string]$name) {
    $f = Join-Path $shots "$name.png"
    & $adb shell screencap -p /sdcard/s.png | Out-Null
    & $adb pull /sdcard/s.png $f 2>$null | Out-Null
    return $f
}

function Show-Print([string]$n) {
    & $adb shell am start -n com.riftcoach.app/.DevScreenActivity --es f "$n.jpg" 2>$null | Out-Null
}

function Rc-Log {
    & $adb logcat -d -s RiftCoach:D | ForEach-Object { $_ -replace '^\S+\s+\S+\s+\d+\s+\d+\s+\w\s+RiftCoach:\s*', '' }
}

# Liga a bolha com captura de tela. $mode = "Entire screen" ou "A single app"
function Start-Bubble([string]$mode) {
    & $adb shell am force-stop com.riftcoach.app
    & $adb shell appops set com.riftcoach.app SYSTEM_ALERT_WINDOW allow
    & $adb shell pm grant com.riftcoach.app android.permission.POST_NOTIFICATIONS
    & $adb shell am start -n com.riftcoach.app/.MainActivity | Out-Null
    Start-Sleep -Seconds 3
    Tap-Text "LIGAR BOLHA"
    Start-Sleep -Seconds 3
    if ($mode -eq "Entire screen") {
        Tap-Text "A single app"
        Start-Sleep -Seconds 2
        Tap-Text "Entire screen"
        Start-Sleep -Seconds 2
    }
    Tap-Text '^(Start|Next)$'
    Start-Sleep -Seconds 4
}
