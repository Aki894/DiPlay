param(
    [Parameter(Mandatory=$true)][string]$Adb,
    [Parameter(Mandatory=$true)][string]$BackupDirectory
)
$ErrorActionPreference = "Stop"
function Invoke-Adb {
    & $Adb @args
    if ($LASTEXITCODE -ne 0) { throw "ADB failed; rollback stopped." }
}
$phone = Join-Path $BackupDirectory "com.shihab.diplay.hudtest.apk"
$car = Join-Path $BackupDirectory "com.projection.car.apk"
foreach ($file in @($Adb,$phone,$car)) {
    if (-not (Test-Path -LiteralPath $file -PathType Leaf)) { throw "Missing rollback file: $file" }
}
Invoke-Adb root
Invoke-Adb wait-for-device
# Also prevent vendor init from launching the board-only entry point in the restored phone APK.
Invoke-Adb shell setprop persist.wukong.bridge.enabled 0
Invoke-Adb shell setprop ctl.stop wukong_bridge
Invoke-Adb install -r -d $car
Invoke-Adb install -r -d $phone
Write-Host "Previous APKs restored. Application data and pairings retained."
Write-Host "To re-enable the board helper after reinstalling board APKs: adb shell setprop persist.wukong.bridge.enabled 1"
