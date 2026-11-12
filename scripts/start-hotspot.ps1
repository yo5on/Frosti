# Offline access point. Keep this process alive: Windows stops advertising when it exits.
param([switch]$HotspotOnly)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot
Start-Transcript -Path (Join-Path $root 'data/hotspot.log') -Append | Out-Null  # runs hidden at sign-in; this is the only trace
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
function Log($text) { Write-Host "$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss') $text" }
# The publisher can still report 'Started' after the driver drops the access point, so check the address itself.
function HotspotUp {
    $addresses = [System.Net.NetworkInformation.NetworkInterface]::GetAllNetworkInterfaces() |
        Where-Object { $_.Description -like '*Wi-Fi Direct*' -and $_.OperationalStatus -eq 'Up' } |
        ForEach-Object { $_.GetIPProperties().UnicastAddresses } |
        ForEach-Object { $_.Address.ToString() }
    $publisher.Status -eq 'Started' -and $settings.host -in $addresses
}
function Restart-Hotspot {
    try { $publisher.Stop() } catch {}
    try { $publisher.Start() } catch { Log "Hotspot start failed; retrying: $_" }
}
$core = $null
try {
    # Start the Core before the hotspot: the laptop console must work even when the access point is stuck.
    if (-not $HotspotOnly) {
        $core = Start-Process -FilePath $settings.python -ArgumentList '-m', 'frosti_core' -WorkingDirectory (Join-Path $root 'core') -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $root 'data/core.log') -RedirectStandardError (Join-Path $root 'data/core-error.log')
    }
    # Wi-Fi can come up late after boot: keep trying instead of giving up.
    Restart-Hotspot  # Start() throws while Wi-Fi is still off; the loop below retries
    for ($attempt = 1; -not (HotspotUp) -and ($HotspotOnly -or -not $core.HasExited); $attempt++) {
        Start-Sleep -Seconds 1
        if ($attempt % 20 -eq 0) {
            Log "Hotspot address $($settings.host) not up yet (is Windows Mobile Hotspot on?); retrying."
            Restart-Hotspot
        }
    }
    Log "FROSTI hotspot running: $($settings.ssid)"
    $down = 0
    while ($HotspotOnly -or -not $core.HasExited) {
        Start-Sleep -Seconds 3
        $up = HotspotUp
        if ($up -and $down -ge 2) { Log 'Hotspot back.' }
        $down = if ($up) { 0 } else { $down + 1 }
        if ($down -eq 2) { Log 'Hotspot dropped; restarting.' }
        if ($down -ge 2 -and $down % 5 -eq 2) { Restart-Hotspot }  # restart now, then every 15 s while still down
    }
    if ($core.ExitCode -ne 0) { throw 'Core failed; see data/core-error.log.' }
} finally {
    $publisher.Stop()
    if ($core -and -not $core.HasExited) { & taskkill.exe /PID $core.Id /T /F | Out-Null }
    Stop-Transcript | Out-Null
}
