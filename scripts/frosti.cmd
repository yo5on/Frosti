@echo off
rem `frosti` from any cmd/PowerShell: start the Core if it isn't running, then open the laptop console.
powershell -NoProfile -Command ^
  "if (-not (Get-NetTCPConnection -State Listen -LocalPort 8765 -ErrorAction SilentlyContinue)) {" ^
  "  Write-Host 'Starting FROSTI...'; Start-ScheduledTask -TaskName 'FROSTI wireless';" ^
  "  for ($i = 0; $i -lt 60 -and -not (Get-NetTCPConnection -State Listen -LocalPort 8765 -ErrorAction SilentlyContinue); $i++) { Start-Sleep 1 }" ^
  "  if ($i -ge 60) { Write-Host 'FROSTI did not start; see E:\sparky\data\core-error.log'; exit 1 } }"
if errorlevel 1 exit /b 1
start "" http://127.0.0.1:8765/
