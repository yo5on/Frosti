# One-time: paste your Spotify developer app's Client ID and Client secret; they are checked, then saved to data/spotify.json.
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot
$id = (Read-Host 'Client ID').Trim()
$secret = [Runtime.InteropServices.Marshal]::PtrToStringBSTR([Runtime.InteropServices.Marshal]::SecureStringToBSTR((Read-Host 'Client secret' -AsSecureString))).Trim()
$basic = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("${id}:${secret}"))
try {
    Invoke-RestMethod -Method Post -Uri 'https://accounts.spotify.com/api/token' -Body 'grant_type=client_credentials' `
        -ContentType 'application/x-www-form-urlencoded' -Headers @{ Authorization = "Basic $basic" } | Out-Null
} catch { throw "Spotify rejected these keys (or the laptop is offline): $($_.Exception.Message)" }
@{ client_id = $id; client_secret = $secret } | ConvertTo-Json | Set-Content -Encoding utf8 (Join-Path $root 'data/spotify.json')
Write-Host 'Spotify keys saved. Say "play <song> on Spotify".' -ForegroundColor Green
