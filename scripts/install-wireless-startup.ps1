# Run once as administrator after preparing and testing the wireless identity.
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot
$settings = Get-Content (Join-Path $root 'data/wireless.json') -Raw | ConvertFrom-Json
if (-not (Test-Path $settings.python)) { throw 'Core interpreter not found.' }
$rule = 'FROSTI offline hotspot TLS'
$program = & $settings.python -c 'import sys; print(sys._base_executable)'
if ($LASTEXITCODE -ne 0 -or -not (Test-Path $program)) { throw 'Could not resolve the Core interpreter.' }
if (-not (Get-NetFirewallRule -DisplayName $rule -ErrorAction SilentlyContinue)) {
    New-NetFirewallRule -DisplayName $rule -Direction Inbound -Action Allow -Protocol TCP -LocalPort 8766 -LocalAddress $settings.host -RemoteAddress '192.168.137.0/24' -Program $program -Profile Any | Out-Null
}
Get-NetFirewallRule -DisplayName $rule | Get-NetFirewallApplicationFilter | Set-NetFirewallApplicationFilter -Program $program | Out-Null
$action = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument "-NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File `"$PSScriptRoot\start-hotspot.ps1`"" -WorkingDirectory $root
$user = [Security.Principal.WindowsIdentity]::GetCurrent().Name
$trigger = New-ScheduledTaskTrigger -AtLogOn -User $user
$options = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -ExecutionTimeLimit ([TimeSpan]::Zero) -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 1) -MultipleInstances IgnoreNew
Register-ScheduledTask -TaskName 'FROSTI wireless' -Action $action -Trigger $trigger -Settings $options -User $user -RunLevel Limited -Force | Out-Null
Write-Host 'FROSTI will start its hotspot and Core at sign-in.'
