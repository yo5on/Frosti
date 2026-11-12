# Live view of fetch-models.ps1 progress. Ctrl-C to stop.
param([string]$Root = 'D:\frosti-models')
$total = [ordered]@{
  'sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8.tar.bz2' = 460
  'vits-piper-en_GB-northern_english_male-medium.tar.bz2' = 64
  'kokoro-en-v0_19.tar.bz2' = 305
}
$prev = @{}
while ($true) {
  $lines = foreach ($k in $total.Keys) {
    $done = (Test-Path "$Root\.$k.done") -or (Test-Path "$Root\speech\.$k.done")
    $f = Get-ChildItem $Root -Recurse -File -Filter $k -EA 0 | Select-Object -First 1
    $mb = if ($done) { $total[$k] } elseif ($f) { [math]::Round($f.Length / 1MB) } else { 0 }
    $rate = if ($prev.ContainsKey($k)) { ($mb - $prev[$k]) / 5 } else { 0 }
    $prev[$k] = $mb
    $pct = [math]::Min(100, [math]::Round(100 * $mb / $total[$k]))
    $bar = ('#' * [math]::Floor($pct / 5)).PadRight(20, '.')
    $state = if ($done) { 'done' } elseif ($rate -gt 0) { '{0:N1} MB/s' -f $rate } else { '' }
    '{0,-55} [{1}] {2,3}%  {3,5}/{4,5} MB  {5}' -f $k, $bar, $pct, $mb, $total[$k], $state
  }
  Clear-Host
  "FROSTI model downloads  $(Get-Date -Format HH:mm:ss)   (Ctrl-C to close)`n"
  $lines
  Start-Sleep 5
}
