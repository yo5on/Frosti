# Offline access point. Keep this process alive: Windows stops advertising when it exits.
param([switch]$HotspotOnly)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot
$settings = Get-Content (Join-Path $root 'data/wireless.json') -Raw | ConvertFrom-Json
[Windows.Devices.WiFiDirect.WiFiDirectAdvertisementPublisher,Windows.Devices.WiFiDirect,ContentType=WindowsRuntime] | Out-Null
[Windows.Security.Credentials.PasswordCredential,Windows.Security.Credentials,ContentType=WindowsRuntime] | Out-Null
$publisher = New-Object Windows.Devices.WiFiDirect.WiFiDirectAdvertisementPublisher
$publisher.Advertisement.IsAutonomousGroupOwnerEnabled = $true
$publisher.Advertisement.LegacySettings.IsEnabled = $true
$publisher.Advertisement.LegacySettings.Ssid = $settings.ssid
$credential = New-Object Windows.Security.Credentials.PasswordCredential
$credential.Password = $settings.password
$publisher.Advertisement.LegacySettings.Passphrase = $credential
$core = $null
try {
    $publisher.Start()
    $ready = $false
    for ($attempt = 0; $attempt -lt 30; $attempt++) {
        Start-Sleep -Seconds 1
        if ($publisher.Status -eq 'Aborted') { throw 'Windows rejected the offline hotspot.' }
        $addresses = [System.Net.NetworkInformation.NetworkInterface]::GetAllNetworkInterfaces() |
            Where-Object { $_.Description -like '*Wi-Fi Direct*' } |
            ForEach-Object { $_.GetIPProperties().UnicastAddresses } |
            ForEach-Object { $_.Address.ToString() }
        if ($publisher.Status -eq 'Started' -and $settings.host -in $addresses) { $ready = $true; break }
    }
    if (-not $ready) { throw "Hotspot address $($settings.host) unavailable. Check Windows Mobile Hotspot is off." }
    Write-Host "FROSTI hotspot running: $($settings.ssid)"
    if (-not $HotspotOnly) {
        $core = Start-Process -FilePath $settings.python -ArgumentList '-m', 'frosti_core' -WorkingDirectory (Join-Path $root 'core') -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $root 'data/core.log') -RedirectStandardError (Join-Path $root 'data/core-error.log')
    }
    while ($HotspotOnly -or -not $core.HasExited) {
        Start-Sleep -Seconds 3
        if ($publisher.Status -in @('Stopped', 'Aborted')) {
            try {
                $publisher.Stop()
                $publisher.Start()
                Write-Host 'Restarting offline hotspot after interruption.'
            } catch { Write-Warning "Hotspot temporarily unavailable; retrying: $_" }
        }
    }
    if ($core.ExitCode -ne 0) { throw 'Core failed; see data/core-error.log.' }
} finally {
    $publisher.Stop()
    if ($core -and -not $core.HasExited) { & taskkill.exe /PID $core.Id /T /F | Out-Null }
}
