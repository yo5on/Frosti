# Official Android runtime, pinned to the laptop's speech-runtime version.
param([string]$Tools = 'D:\frosti-tools')
$ErrorActionPreference = 'Stop'
$file = Join-Path $Tools 'sherpa-onnx-1.13.8.aar'
$hash = '633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96'
if (-not (Test-Path $file)) {
    Invoke-WebRequest 'https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-1.13.8.aar' -OutFile "$file.download" -UseBasicParsing
    if ((Get-FileHash "$file.download" -Algorithm SHA256).Hash.ToLower() -ne $hash) { throw 'Android runtime checksum mismatch.' }
    Move-Item -LiteralPath "$file.download" -Destination $file
}
if ((Get-FileHash $file -Algorithm SHA256).Hash.ToLower() -ne $hash) { throw 'Android runtime checksum mismatch.' }
Write-Host 'Android wake-word runtime verified.'
