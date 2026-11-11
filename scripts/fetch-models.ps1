# Downloads FROSTI's Phase 0 candidate models to D:\frosti-models, smallest/most-needed first.
# Resumable: re-run any time; finished files are skipped, partial ones continue.
# Stalls (<5 KB/s for 60 s) abort the transfer and the loop resumes it.
param([string]$Root = 'D:\frosti-models')

$speech = Join-Path $Root 'speech'
New-Item -ItemType Directory -Force $Root, $speech | Out-Null
$gh = 'https://github.com/k2-fsa/sherpa-onnx/releases/download'
$hf = 'https://huggingface.co'

# url, destination dir, extract?
$queue = @(
  @("$gh/asr-models/silero_vad.onnx", $speech, $false),
  @("$gh/kws-models/sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01.tar.bz2", $speech, $true),
  @("$gh/tts-models/vits-piper-en_GB-alan-medium.tar.bz2", $speech, $true),
  @("$gh/asr-models/sherpa-onnx-streaming-zipformer-en-20M-2023-02-17-mobile.tar.bz2", $speech, $true),
  @("$gh/asr-models/sherpa-onnx-moonshine-base-en-quantized-2026-02-27.tar.bz2", $speech, $true),
  @("$hf/unsloth/Qwen3.5-4B-GGUF/resolve/main/Qwen3.5-4B-Q4_K_M.gguf", $Root, $false),
  @("$gh/asr-models/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8.tar.bz2", $speech, $true),
  @("$hf/ggml-org/gemma-4-E4B-it-GGUF/resolve/main/gemma-4-E4B-it-Q4_0.gguf", $Root, $false),
  @("$gh/tts-models/vits-piper-en_GB-northern_english_male-medium.tar.bz2", $speech, $true),
  @("$gh/tts-models/kokoro-en-v0_19.tar.bz2", $speech, $true)
)

foreach ($q in $queue) {
  $url, $dir, $extract = $q
  $name = Split-Path $url -Leaf
  $out = Join-Path $dir $name
  $done = Join-Path $dir ".$name.done"
  if (Test-Path $done) { "skip  $name"; continue }
  for ($i = 1; $i -le 200; $i++) {
    curl.exe -fsSL --ssl-revoke-best-effort -C - --speed-limit 5000 --speed-time 60 --connect-timeout 30 -o $out $url
    if ($LASTEXITCODE -in 0, 33) { break }
    "      $name attempt $i exit $LASTEXITCODE, resuming at $([math]::Round((Get-Item $out -EA 0).Length/1MB))MB"
    Start-Sleep 3
  }
  if ($LASTEXITCODE -notin 0, 33) { "FAIL  $name"; continue }
  if ($extract) {
    tar -xjf $out -C $dir
    if ($LASTEXITCODE -ne 0) { "FAIL  extract $name"; continue }
    Remove-Item $out
  }
  New-Item -ItemType File $done -Force | Out-Null
  "done  $name  $(Get-Date -Format HH:mm)"
}
