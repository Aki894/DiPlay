param(
    [Parameter(Mandatory=$true)][string]$Adb,
    [Parameter(Mandatory=$true)][string]$DiPlayApk,
    [Parameter(Mandatory=$true)][string]$CarProjectionApk,
    [string]$BackupDirectory = (Join-Path $PWD ("wukong-backup-" + (Get-Date -Format "yyyyMMdd-HHmmss")))
)
$ErrorActionPreference = "Stop"
foreach ($file in @($Adb,$DiPlayApk,$CarProjectionApk)) {
    if (-not (Test-Path -LiteralPath $file -PathType Leaf)) { throw "File not found: $file" }
}
function Invoke-Adb {
    $output = & $Adb @args
    if ($LASTEXITCODE -ne 0) { throw "ADB failed: $($args -join ' ')" }
    return $output
}
$devices = @(Invoke-Adb devices | Where-Object { $_ -match '^\S+\s+device$' })
if ($devices.Count -ne 1) { throw "Connect exactly one Android device with authorized ADB." }
Invoke-Adb root
Invoke-Adb wait-for-device
if ((Invoke-Adb shell id -u).Trim() -ne "0") { throw "This installer requires the current userdebug adb root build." }
if ((Invoke-Adb shell getprop sys.boot_completed).Trim() -ne "1") { throw "Wait for Android boot completion before installing." }
New-Item -ItemType Directory -Path $BackupDirectory -Force | Out-Null
foreach ($package in @("com.projection.car","com.shihab.diplay.hudtest")) {
    $paths = @(Invoke-Adb shell pm path $package)
    $base = $paths | Where-Object { $_ -match '^package:.*/base.apk\s*$' } | Select-Object -First 1
    if (-not $base) { $base = $paths | Where-Object { $_ -match '^package:' } | Select-Object -First 1 }
    if ($base) { Invoke-Adb pull ($base.Substring(8).Trim()) (Join-Path $BackupDirectory "$package.apk") }
}
# Preserve both application data directories and existing iPhone pairings.
Invoke-Adb install -r -d $CarProjectionApk
Invoke-Adb install -r -d $DiPlayApk
Invoke-Adb shell am force-stop com.projection.car
Invoke-Adb shell am force-stop com.shihab.diplay.hudtest
$vendorHelper = (Invoke-Adb shell "if [ -f /vendor/etc/init/init.wukong-bridge.rc ]; then echo ready; fi") -join ""
if ($vendorHelper -notmatch "ready") {
    throw "APKs installed and backed up. Update super.img with the headless board patch before starting. Do not format userdata."
}
Invoke-Adb shell setprop persist.wukong.bridge.enabled 1
Invoke-Adb shell setprop ctl.stop wukong_bridge
Invoke-Adb shell setprop ctl.start wukong_bridge
Invoke-Adb shell am start-foreground-service -n com.projection.car/.BoardSessionService --es command start
Invoke-Adb shell am start-foreground-service -n com.shihab.diplay.hudtest/com.shilapi.xcertplay.board.BoardService --es command boot
Invoke-Adb forward tcp:8765 tcp:8765
Write-Host "Backups: $BackupDirectory"
Write-Host "Web management: http://127.0.0.1:8765 (keep USB ADB connected for this forwarding route)."
Write-Host "Read the private management token after the service starts:"
Write-Host "& `$Adb shell cat /data/user/0/com.shihab.diplay.hudtest/no_backup/web-token"
Write-Host "No application data was erased. Phone session autostart uses the saved board setting."
