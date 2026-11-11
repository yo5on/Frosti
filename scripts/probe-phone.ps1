# Read-only probe of the connected phone for Phase 0. Changes nothing on the device.
# Prereq: Settings > About phone > Software information > tap "Build number" 7x,
#         then Developer options > USB debugging ON, plug in, accept the RSA prompt.
param([string]$Adb = 'D:\frosti-tools\platform-tools\adb.exe',
      [string]$Out = (Join-Path $PSScriptRoot '..\docs\phone-probe.txt'))

$devs = & $Adb devices | Select-String '\tdevice$'
if (-not $devs) { Write-Error "No authorized device. Check USB debugging and the RSA prompt (and Samsung USB driver)."; exit 1 }

function sh($c) { (& $Adb shell $c 2>&1) -join "`n" }
function prop($p) { (sh "getprop $p").Trim() }

$r = [ordered]@{
  model            = prop ro.product.model
  device           = prop ro.product.device
  android          = prop ro.build.version.release
  sdk              = prop ro.build.version.sdk
  security_patch   = prop ro.build.version.security_patch
  build            = prop ro.build.display.id
  cpu_abi          = prop ro.product.cpu.abi
  soc              = prop ro.hardware
  cpu_abilist      = prop ro.product.cpu.abilist
  cores_possible   = (sh 'cat /sys/devices/system/cpu/possible').Trim()
  cores_online     = (sh 'cat /sys/devices/system/cpu/online').Trim()
  max_freq_khz     = (sh 'cat /sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_max_freq').Trim()
  oem_unlock_supported = prop ro.oem_unlock_supported
  oem_unlock_allowed   = prop sys.oem_unlock_allowed
  knox_warranty_bit    = prop ro.boot.warranty_bit
  bootloader           = prop ro.bootloader
  accounts_present     = ((sh 'dumpsys account' | Select-String 'Account \{').Count)
  device_owner         = (sh "dumpsys device_policy | grep -A1 'Device Owner'").Trim()
  users                = (sh "pm list users | grep -c UserInfo").Trim()
  gles                 = (sh "dumpsys SurfaceFlinger | grep -m1 'GLES:'").Trim()
  display              = (sh 'wm size').Trim()
}
$mem = sh 'cat /proc/meminfo'
$r.mem_total = ($mem -split "`n" | Select-String 'MemTotal').ToString().Trim()
$r.mem_available = ($mem -split "`n" | Select-String 'MemAvailable').ToString().Trim()
$r.storage_data = ((sh 'df -h /data') -split "`n" | Select-Object -Last 1).Trim()
$bat = sh 'dumpsys battery'
foreach ($k in 'level','health','temperature','voltage','status') {
  $r["battery_$k"] = (($bat -split "`n" | Select-String "^\s*$k\s*:" | Select-Object -First 1) -replace '.*:\s*','').Trim()
}
$r.aec_available = (sh "dumpsys media.audio_flinger | grep -im1 'echo canceler'").Trim()
$r.third_party_packages = ((sh 'pm list packages -3') -split "`n").Count

$text = ($r.GetEnumerator() | % { "{0,-22} {1}" -f $_.Key, $_.Value }) -join "`n"
$text
$text | Out-File -Encoding utf8 $Out
"`nSaved to $Out"
"Device Owner provisioning needs accounts_present = 0 (factory reset, skip account sign-in)."
