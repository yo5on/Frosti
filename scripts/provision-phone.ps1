# One-time phone setup over USB: install the Shell, grant mic + system-settings permission,
# link it to the Core (adb reverse + pairing token) and optionally make it the Home screen.
# Does NOT provision Device Owner. Undo Home: phone Settings > Apps > Default apps > Home screen.
param([switch]$SetHome)
$ErrorActionPreference = 'Stop'
$adb = 'D:\sparky-tools\platform-tools\adb.exe'
$root = Resolve-Path (Join-Path $PSScriptRoot '..')
$apk = Join-Path $root 'shell\app\build\outputs\apk\debug\app-debug.apk'
$tokenFile = Join-Path $root 'data\token.txt'
if (-not (Test-Path $apk)) { throw "Build first: scripts\build-shell.ps1" }
if (-not (Test-Path $tokenFile)) {
  New-Item -ItemType Directory -Force (Split-Path $tokenFile) | Out-Null
  $b = New-Object byte[] 24; [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b)
  [Convert]::ToBase64String($b).Replace('+','-').Replace('/','_').TrimEnd('=') | Set-Content -NoNewline -Encoding ascii $tokenFile
}
$token = (Get-Content $tokenFile -Raw).Trim()
$pkg = 'com.sparky.shell'

& $adb install -r $apk
& $adb shell pm grant $pkg android.permission.RECORD_AUDIO
& $adb shell appops set $pkg WRITE_SETTINGS allow
& $adb reverse tcp:8765 tcp:8765
if ($SetHome) { & $adb shell cmd package set-home-activity "$pkg/.MainActivity" }
& $adb shell am start -n "$pkg/.MainActivity" --es token $token
"Phone provisioned. Start the Core with scripts\start-sparky.ps1"
