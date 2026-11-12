# One-time phone setup over USB: install the Shell, grant mic + system-settings permission,
# link it to the Core (adb reverse + pairing token) and optionally make it the Home screen.
# -OsMode makes FROSTI Device Owner: it becomes the whole phone (no status bar, shade, recents or lock screen).
# Needs 0 accounts on the phone. Undo: Setup > OS mode on the phone, or
#   adb shell dpm remove-active-admin com.frosti.shell/.FrostiAdmin
param([switch]$SetHome, [switch]$Wireless, [switch]$Usb, [switch]$OsMode)
$ErrorActionPreference = 'Stop'
$adb = 'D:\frosti-tools\platform-tools\adb.exe'
$root = Resolve-Path (Join-Path $PSScriptRoot '..')
# once wireless is set up, a plain re-provision (e.g. to install a new build) must not silently move the phone back to USB
if (-not $Usb -and (Test-Path (Join-Path $root 'data\wireless.json'))) { $Wireless = $true }
$apk = Join-Path $root 'shell\app\build\outputs\apk\debug\app-debug.apk'
$tokenFile = Join-Path $root 'data\token.txt'
if (-not (Test-Path $apk)) { throw "Build first: scripts\build-shell.ps1" }
if (-not (Test-Path $tokenFile)) {
  New-Item -ItemType Directory -Force (Split-Path $tokenFile) | Out-Null
  $b = New-Object byte[] 24; [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b)
  [Convert]::ToBase64String($b).Replace('+','-').Replace('/','_').TrimEnd('=') | Set-Content -NoNewline -Encoding ascii $tokenFile
}
$token = (Get-Content $tokenFile -Raw).Trim()
$pkg = 'com.frosti.shell'

& $adb install -r -t $apk  # -t: the APK is testOnly (OS mode escape hatch)
if ($LASTEXITCODE -ne 0) { throw 'APK installation failed; pairing was not changed.' }
& $adb shell pm grant $pkg android.permission.RECORD_AUDIO
& $adb shell appops set $pkg WRITE_SETTINGS allow
& $adb shell dumpsys deviceidle whitelist +$pkg
& $adb shell ime enable "$pkg/.SplitKeyboard"  # split keyboard for landscape typing
& $adb shell ime set "$pkg/.SplitKeyboard"
& $adb shell settings put system surface_palm_swipe 0  # Samsung palm-swipe screenshot fires while thumb-typing
& $adb shell settings put system enable_smart_capture 0
if (-not $Wireless) { & $adb reverse tcp:8765 tcp:8765 }
if ($OsMode) {
  & $adb shell dpm set-device-owner "$pkg/.FrostiAdmin"
  if ($LASTEXITCODE -ne 0) { throw 'Device Owner failed. Remove every account in phone Settings > Accounts, then retry.' }
}
if ($SetHome) { & $adb shell cmd package set-home-activity "$pkg/.MainActivity" }
if ($Wireless) {
  $settings = Get-Content (Join-Path $root 'data/wireless.json') -Raw | ConvertFrom-Json
  $cert = [Convert]::ToBase64String([IO.File]::ReadAllBytes((Join-Path $root 'data/wireless.cer')))
  & $adb shell am start -n "$pkg/.MainActivity" --es token $token --es host $settings.host --ei port 8766 --es tls_cert $cert --es wifi_ssid $settings.ssid --es wifi_password $settings.password
  if ($LASTEXITCODE -ne 0) { throw 'Wireless provisioning failed.' }
  "FROSTI will join Wi-Fi '$($settings.ssid)' on Android 9. If prompted, stay connected without internet."
} else {
  & $adb shell am start -n "$pkg/.MainActivity" --es token $token
}
if ($Wireless) { 'Phone provisioned. Start scripts\start-hotspot.ps1 or use the installed sign-in task.' }
else { 'Phone provisioned. Start the Core with scripts\start-frosti.ps1' }
