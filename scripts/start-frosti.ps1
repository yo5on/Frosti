# Starts FROSTI Core (gateway + voice + LLM) and re-links the phone over USB.
# Extra args pass through, e.g. -NoLlm for an instant fast-path-only start.
param([switch]$NoLlm, [switch]$NoVoice, [switch]$Usb)
$root = Resolve-Path (Join-Path $PSScriptRoot '..')
# wireless set up: this USB-only Core would take port 8765 from the hotspot task's Core
if (-not $Usb -and (Test-Path (Join-Path $root 'data\wireless.json'))) {
  Write-Host 'Wireless is set up: run scripts\start-hotspot.ps1 (or the "FROSTI wireless" task) instead.' -ForegroundColor Yellow
  Write-Host 'To run over USB anyway, add -Usb.'
  exit 1
}
& 'D:\frosti-tools\platform-tools\adb.exe' reverse tcp:8765 tcp:8765 2>$null  # lost on every USB reconnect
$a = @('-m', 'frosti_core'); if ($NoLlm) { $a += '--no-llm' }; if ($NoVoice) { $a += '--no-voice' }
Push-Location (Join-Path $root 'core')
try { & (Join-Path $root '.venv\Scripts\python.exe') @a } finally { Pop-Location }
