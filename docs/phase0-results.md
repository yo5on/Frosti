# SPARKY Phase 0 — measured results

Measured on 2026-11-11 on the HP EliteBook 840 G5 (i7-8650U, 16 GB, plugged in) and the Galaxy J7 Pro (SM-J730GM).
To reproduce: `bench/llm_tools.py`, `bench/speech.py`, `scripts/probe-phone.ps1`. Model files are in `D:\sparky-models` (`scripts/fetch-models.ps1`).

## 1. LLM (llama.cpp b12262, `llama-server`, CPU, 4 threads, ctx 4096)

### Raw speed (`llama-bench`, Qwen3.5-4B Q4_K_M)

| Backend | Prompt processing | Generation |
|---|---|---|
| CPU (AVX2, 4 threads) | 20.6 tok/s | 4.8 tok/s |
| Vulkan (UHD 620) | 9.9 tok/s | 2.6 tok/s |

The iGPU is about 2× slower. **Run CPU-only.**

### SPARKY tool-calling benchmark (32 cases, 13 tools, temperature 0, thinking off)

| | Qwen3.5-4B Q4_K_M | **Gemma 4 E4B-it Q4_0** |
|---|---|---|
| File size | 2.7 GB | 4.6 GB |
| RAM (working set / private) | 4.3 GB / 2.2 GB | 5.3 GB / 2.6 GB |
| Load time | 6 s | 14 s |
| **Accuracy** | **32/32** | 30/32 |
| Cold first request | 77 s (1,181 prompt tokens at 17 tok/s) | 77 s (804 prompt tokens at 11.6 tok/s) |
| **Warm median / p90** | 8.8 s / 11.6 s | **4.7 s / 6.6 s** |
| Output tokens per tool call (median) | 35 | 17 |
| New prompt tokens per warm request (median) | 19 | 12 |
| Generation speed (median) | 4.5 tok/s | 4.5 tok/s |

Failures:
- Gemma: "push sparky to github" → asked *"What is the name of the project you want to push?"*. "remind me in half an hour" → asked *"What should I remind you about?"* Both are clarifying questions, not wrong actions, so they are safe failures.
- Qwen: none.

Both runs overlapped with background model downloads (curl), so absolute numbers may be a few percent pessimistic.

### Cold vs. warm: the latency finding

- **The prefix cache already works with default settings.** llama-server b12262 runs 4 slots with a unified KV cache and picks a slot by longest common prefix. The log shows `f_keep ≈ 0.95` on every warm request, and only 12–21 new tokens get processed. So `-np 1` and `--cache-reuse` have nothing left to fix, and I didn't spend benchmark runs on them.
- **Cold prefill is the expensive part:** 69–77 s for the system prompt plus 13 tool schemas. Phase 1 must **warm the prefix at startup** by sending one dummy request after the server loads, and must keep the system prompt and tool list byte-identical between requests. Any change invalidates the cache and costs another minute.
- **Warm latency is generation-bound.** Generation runs at the same ~4.5 tok/s on both models, so latency follows the number of output tokens. Gemma's tool-call format takes about half as many tokens as Qwen's XML-style `<tool_call><function=…><parameter=…>` format, which is why Gemma is 2× faster per call. Small prompts also process slowly (6–10 tok/s for 20 tokens), which adds about 2 s.
- The proposal's target of LLM tool calls in under 3 s can't be reached on this CPU. Realistic numbers are about 5 s with Gemma and about 9 s with Qwen. That makes the fast path (rules plus embeddings, no LLM) and instant feedback (earcon, orb animation) a requirement, not polish.

## 2. Speech (sherpa-onnx 1.13.8, Python, 4 threads unless noted)

ASR test clips are 10 SPARKY commands voiced by Piper and resampled to 16 kHz. That is clean synthetic audio, so expect real-mic WER to be higher. Re-test in Phase 2 with the phone mic.

### ASR

| Model | Type | WER | RTF | Avg latency per utterance |
|---|---|---|---|---|
| **Parakeet TDT 0.6B v2 int8** | offline | **0.0%** | 0.23 | 0.52 s |
| Moonshine base en (2026-02-27, quantized) | offline | 3.3% ("RAM" → "or AM") | 0.11 | 0.24 s |
| Streaming Zipformer en 20M (mobile) | streaming, 2 threads | **56%** (drops the first word) | 0.07 | — |

### TTS

| Voice | First sentence | RTF |
|---|---|---|
| **Piper en_GB alan-medium** | **0.10 s** | 0.11 |
| Piper en_GB northern_english_male-medium | 0.12 s | 0.14 |
| Kokoro en v0.19 | 1.61 s | 1.16 (slower than real time) |

Kokoro misses the proposal's "<1 s for the first sentence" bar and is slower than real time, so it is **rejected** for live replies.

### Wake word

| Model | "HEY SPARKY" detected | False alarms | RTF (1 thread, laptop) |
|---|---|---|---|
| KWS Zipformer gigaspeech 3.3M | 3/3 | 0/4 | 0.05–0.07 |

## 3. Phone (read-only `adb` probe, `docs/phone-probe.txt`)

| Item | Value | Consequence |
|---|---|---|
| Model / Android | SM-J730GM, Android 9 (SDK 28), patch 2021-05-01 | As planned; treat as untrusted |
| ABI | **armeabi-v7a only** (32-bit userspace on a 64-bit SoC) | Ship 32-bit native libs (sherpa-onnx AAR v7a, ONNX Runtime v7a) |
| CPU | Exynos 7870, 8× A53 @ 1.59 GHz, 4 cores online at idle | Keep phone-side models tiny (KWS, VAD) |
| RAM | 2.8 GB total, ~1.5 GB available | |
| GPU | Mali-T830, GLES 3.2 | Orb shader at reduced resolution |
| Storage | 53 GB free | |
| Accounts / users | **0 / 1** | **Device Owner can be provisioned without a factory reset** |
| OEM unlock | supported, off; Knox bit 0 | Root path still open but not planned |
| Battery | health good, 39 % at probe | |
| AEC | NXP AcousticEchoCanceler present | Barge-in is plausible |

**Not measured yet:** KWS CPU use and battery drain per hour on the phone. That needs the Shell app (Phase 2). It's the first thing to measure once the app can listen.

## 4. Decisions

| Role | Pick | Why |
|---|---|---|
| Main LLM | **Gemma 4 E4B-it Q4_0** | 2× faster warm tool calls (4.7 s vs 8.8 s median). Its 2 failures were safe clarifying questions. It also covers vision (mmproj), so there's one model to keep loaded. |
| LLM fallback | Qwen3.5-4B Q4_K_M | 32/32 accuracy and 1 GB less RAM. Switch to it if the RAM budget is tight or if Gemma's clarifying questions become a problem. |
| Runtime | llama-server CPU, 4 threads, default slots, prefix warmed at startup | Vulkan is slower; prefix caching works out of the box |
| Laptop ASR | **Parakeet TDT 0.6B v2 int8** | 0 % WER at 0.5 s per command; Moonshine is the low-latency fallback |
| Laptop TTS | **Piper en_GB alan-medium**, sentence-streamed | 0.1 s to first audio. Northern English male is an alternative voice; drop Kokoro |
| Wake word | KWS gigaspeech 3.3M, "HEY SPARKY" | Reliable and very cheap |
| Phone offline commands | **KWS spotter with a fixed phrase list** (not streaming ASR) | The 20M streaming ASR is unusable (56–63 % WER) |
| Speech runtime | sherpa-onnx **pinned to 1.13.8** | 2.x dropped the espeak-ng frontend that Piper needs |

### Changes to the proposal implied by these numbers

1. **LLM latency budget:** a tool call costs about 5 s, not 1–2 s. The fast path must catch the common intents. Phase 1's "<3 s" criterion applies to fast-path commands only. LLM-routed commands target <6 s warm with Gemma.
2. **Startup prefix warm-up** and a frozen system prompt and tool list are required. The proposal treated caching as optional tuning.
3. **Phone:** 32-bit native libraries, and Device Owner provisioning without a reset. Don't add any account before provisioning.
4. Satellite mode uses the KWS phrase list instead of streaming ASR.
5. Kokoro is out; Piper stays.

These changes are already applied to `docs/SPARKY_Technical_Proposal.md`.

## 5. Next: Phase 1 — core brain (text-only)

Python 3.12 core with llama-server (Gemma 4 E4B, prefix warmed at startup), a fast-path router (rules plus bge-small embeddings) in front of the LLM, the first ~10 tools, risk tiers and confirmation gates, an append-only audit log, SQLite memory, and a CLI client. Exit criteria: fast-path commands in under 1 s; LLM-routed tool calls in under 6 s warm.
