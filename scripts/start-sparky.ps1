# Starts SPARKY Core (gateway + voice + LLM) and re-links the phone over USB.
# Extra args pass through, e.g. -NoLlm for an instant fast-path-only start.
param([switch]$NoLlm, [switch]$NoVoice)
$root = Resolve-Path (Join-Path $PSScriptRoot '..')
& 'D:\sparky-tools\platform-tools\adb.exe' reverse tcp:8765 tcp:8765 2>$null  # lost on every USB reconnect
$a = @('-m', 'sparky_core'); if ($NoLlm) { $a += '--no-llm' }; if ($NoVoice) { $a += '--no-voice' }
Push-Location (Join-Path $root 'core')
try { & (Join-Path $root '.venv\Scripts\python.exe') @a } finally { Pop-Location }
