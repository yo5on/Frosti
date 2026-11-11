# Project FROSTI — Technical Proposal & Feasibility Analysis

**Date:** 2026-11-11 · **Status:** Pre-implementation. No code written yet.

---

## 0. Executive summary (the honest version)

1. **Do not build a custom OS or ROM for the J7 Pro.** The only Android 11 build (LineageOS 18.1) is an alpha with **broken audio**, which rules it out for a voice-first assistant. postmarketOS lists the J7 Pro as **archived**, running on a 2017 vendor kernel (3.18) and not mainline Linux. Either path would cost months and leave you with a less capable phone.
2. **Build FROSTI as a distributed system:** the **J7 Pro is the body** (screen, mic, speaker, camera, sensors, home-screen shell) and the **laptop is the brain** (LLM, speech recognition, quality TTS, vision, memory). This is architecture **E**, improved with Android *Device Owner* mode so that it behaves like an operating environment rather than an app.
3. **Your laptop has no discrete GPU,** so everything runs on a 4-core, 15 W CPU. That puts a ceiling on model size: a **~4B-parameter model** is the most you can use for conversation. Plan on **about 2–4 s per answer** for LLM replies and **under 1 s** for common commands handled by a fast path. A JARVIS feel is still achievable, but it has to come from latency engineering and design. Model size won't deliver it.
4. **Everything stays local.** Phone and laptop talk over your LAN, or over the laptop's own hotspot when there is no internet. No cloud AI is involved anywhere.
5. When the laptop is unreachable, the phone falls back to a **"Satellite mode":** wake word, a limited set of offline commands and system TTS. It is not a full LLM.

---

## 1. Hardware assessment

### 1.1 Laptop — inspected on this machine

| Component | Measured | Implication |
|---|---|---|
| Model | HP EliteBook 840 G5 | Business ultrabook with a 15 W TDP; it **throttles under sustained load**. |
| CPU | Intel Core i7-8650U, 4 cores / 8 threads, AVX2 | Good llama.cpp support through AVX2. Prompt processing on CPU is the main bottleneck. |
| GPU | Intel UHD 620 (integrated) | **No CUDA.** *Measured in Phase 0:* Vulkan offload is **slower** than the CPU (Qwen3.5-4B: prompt 9.9 vs 20.6 tok/s, generation 2.6 vs 4.8 tok/s). Run CPU-only. |
| RAM | 16 GB DDR4-2400, dual channel (2×8 GB) | About 38 GB/s of theoretical bandwidth, which caps token generation. A 4B Q4 model generates roughly **5–9 tokens/s**; a 7–8B model roughly **2–4 tokens/s**. *Measured:* Qwen3.5-4B Q4_K_M generates **~4.5 tok/s** and processes prompts at **~17–21 tok/s** on 4 threads (see `docs/phase0-results.md`). |
| Free RAM at inspection | 5.4 GB | Your normal workload already uses about 10 GB. The AI stack has to stay under **~5–6 GB**. |
| Storage | 256 GB NVMe. C: 59 GB free, D: 39 GB free, **E: only 6 GB free** | Code lives in `E:\frosti`; **models must go on D:** (`D:\frosti-models`). |
| OS | Windows 11 Pro 25H2 (build 26200) | Built-in **Windows OCR** (offline, free), UI Automation and Mobile Hotspot all help. |
| Tooling present | Python 3.14, Node, Git, VS Code (`code` CLI), WSL | **Python 3.14 is too new** for some ML wheels (onnxruntime, ctranslate2). Use a Python 3.12 venv for the core. `adb` and Ollama are **not installed**. |

### 1.2 Samsung Galaxy J7 Pro — public specs, checked on the device in Phase 0

| Component | Spec | Implication |
|---|---|---|
| SoC | Exynos 7870: 8× Cortex-A53 @ 1.6 GHz, 14 nm | Low-power in-order cores. Fine for UI, wake word, VAD and small streaming ASR. **Not suitable for a useful LLM.** |
| GPU | Mali-T830 MP1 (OpenGL ES 3.2) | Can draw shader animations if you keep them cheap. **Render the core orb at reduced resolution.** |
| RAM | 3 GB | Android takes about 1.3 GB. A 0.5B LLM would fit but run at about 2–4 tok/s, so it stays experimental only. |
| Display | 5.5" 1080p Super AMOLED | **True black is free:** black pixels draw no power. Dark UI is the right choice for power reasons too. Watch for **burn-in** from static HUD elements. |
| Software | Final official release Android 9 (One UI 1.0); out of security support | Vendor maintenance has ended. Treat the phone as an **untrusted endpoint** (see §9). |
| Battery | Original 2017 cell, probably degraded | Always-on listening on battery isn't practical. The best experience comes from a **docked, charging "desk console"**. Check the cell for swelling before running it plugged in 24/7. |

**Confirmed in Phase 0** (`docs/phone-probe.txt`): **SM-J730GM**, Android 9 (SDK 28, patch 2021-05), **32-bit userspace (`armeabi-v7a` only)** even though the SoC is 64-bit, so every native library (sherpa-onnx, ONNX Runtime, TFLite) must ship `armeabi-v7a` builds. 2.8 GB RAM with ~1.5 GB available; only 4 of 8 cores online at idle (hotplug). OEM unlock is supported but off, the Knox warranty bit is 0, battery health is *good*, 53 GB is free, and an NXP `AcousticEchoCanceler` is present. **0 accounts and 1 user**, so Device Owner can be provisioned without a factory reset (§7.1).

---

## 2. Architecture options compared

| | A. Custom Android ROM | B. Custom Android-based environment *(stock Android + privileged shell)* | C. Linux mobile OS (postmarketOS) | D. Android + Termux + services | **E. Hybrid: phone body + laptop brain** |
|---|---|---|---|---|---|
| Feasibility on J7 Pro | Low. LineageOS 18.1 alpha with **audio completely broken**, 3.18 kernel. No confirmed Android 12+. | **High.** Stock Android 9 plus a launcher with Device Owner, Accessibility and Notification Listener. | Very low. J7 Pro **archived** in pmOS, vendor kernel. Expect modem, camera, audio and GPU to be missing or partial. | High, but Termux isn't a UI layer: no launcher, no accessibility. | **High.** Uses B as the phone half. |
| Performance | Unknown, unoptimised | Native Kotlin; good | Poor (no GPU acceleration on vendor kernel) | Fine for scripts, poor as a UX | Best: heavy work runs on the fastest device |
| Offline | Yes | Yes | Yes | Yes | Yes (LAN or laptop hotspot) |
| Dev complexity | Very high (AOSP builds need ~300 GB of disk and 32 GB+ RAM, which this laptop **doesn't have**) | Medium | Very high (kernel and driver work) | Low–medium | Medium–high (two codebases plus a protocol) |
| Maintainability | Poor: you become the sole maintainer of a ROM fork | Good: ordinary Android app | Poor | Medium | Good |
| Brick risk | Medium. Samsung download mode makes a hard brick rare, but RMM/KG lock, FRP and EFS loss are real risks. | **None** | Medium | None | **None** (optional root later) |
| "Feels like an OS"? | Yes, if it worked | **Yes, with Device Owner lock-task mode, hidden status bar and own launcher/notifications** | Yes, if it worked | No | **Yes** |

### Verdict
**E, with B as the phone foundation.** A custom ROM would make the phone *worse* (no audio) and still couldn't run AI locally. The parts that make FROSTI impressive, such as the home screen, voice loop, HUD and system control, are achievable on stock Android 9 with the right privileges, and the AI runs where the compute is.

**Is a custom ROM worthwhile?** No. The only advantages would be a custom boot animation and system-signature permissions. Root via Magisk on stock firmware gets both, as an optional Phase 8 if you accept the trade-offs.

---

## 3. Recommended architecture

```
┌─────────────────────── J7 Pro: "FROSTI Shell" (Kotlin) ───────────────────────┐
│  Home launcher (default HOME) · Lock-task / immersive · Core orb (GLES shader)  │
│  HUD widgets · App launcher · Notification centre · Ambient/lock overlay        │
│  ── Voice front-end ──  mic → VAD → wake word (sherpa-onnx KWS) → stream audio  │
│  ── System agent ──     AccessibilityService · NotificationListener ·           │
│                         DeviceOwner (DevicePolicyManager) · App index · Sensors  │
│  ── Satellite mode ──   offline command grammar · local ASR (small) · Android TTS│
│  ── Link ──             WebSocket/TLS, mutually authenticated, auto-reconnect     │
└──────────────────────────────────┬──────────────────────────────────────────────┘
                                   │ LAN / laptop hotspot (no internet needed)
┌──────────────────────────────────┴──── Laptop: "FROSTI Core" (Python 3.12) ─────┐
│  Gateway (auth, sessions, events)                                                │
│  Voice: ASR (Parakeet/Whisper via sherpa-onnx) · TTS (Piper; Kokoro optional)    │
│  Brain: intent fast-path → LLM (llama.cpp llama-server, grammar-constrained tools)│
│  Tools: Windows · VS Code · PowerShell (gated) · Git · Processes · Files · Input │
│         + phone tools executed remotely by the Shell                              │
│  Memory: SQLite + FTS5 + vector index · Scheduler · Event watchers               │
│  Vision: Windows OCR · VLM on demand · screenshots                                │
│  Security: policy engine, confirmation gates, append-only audit log              │
└───────────────────────────────────────────────────────────────────────────────────┘
```

**Why this split:** the laptop has about 10× the compute and 5× the free RAM. The phone has the sensors, the screen and the "always with you" form factor. Putting the brain in one place also gives you **one memory, one model and one source of truth**, without sync conflicts between two AIs.

### 3.1 Request flow and latency budget

```
"Hey Frosti"   →  phone KWS (local, ~200 ms)  →  orb wakes + earcon (instant feedback)
speech         →  streamed to laptop, VAD endpoint       ~300–600 ms after you stop
ASR final      →  Fast path? (rules + embedding match)    <100 ms  → execute → speak
               →  else LLM with cached system prompt      measured: tool call 6–13 s (median 8.8 s)
LLM stream     →  sentence-chunked TTS → audio to phone   first audio ~0.3 s after first sentence
```

**This is the most important design decision for the "feel".** About 70% of what you'll say ("open Spotify", "battery?", "run the frosti core tests", "what's using my RAM") **must never touch the LLM**. A deterministic fast path answers in under 1 s; the LLM only handles open-ended requests. Instant earcons and orb animation cover the remaining wait.

**Hidden trap:** on this CPU, *prompt processing* runs at only tens of tokens/s. A 2,000-token system prompt with tool definitions could take **30+ seconds** if it isn't cached. llama-server's prefix cache (fixed system prompt kept in a reusable slot) is **mandatory**, and the tool list exposed per request has to stay short (pick tools by intent first).

**Measured in Phase 0:** a 1,181-token system prompt plus 13 tools took **69 s** to prefill cold. llama-server (b12262, default 4 slots, unified KV) *does* reuse the cached prefix: warm requests only process the ~20 new tokens. Warm latency is therefore dominated by **generation** — a tool call is 25–50 output tokens at ~4.5 tok/s, so 6–13 s. Levers, in order: the fast path; warming the prefix at startup; fewer, shorter tool definitions and argument names; constrained output with a compact call format. The <3 s Phase 1 target for LLM requests is not reachable on this CPU by sampling alone.

---

## 4. Local AI model recommendations

Each pick is sized to an **i7-8650U with ~5 GB of RAM budget**, not chosen for popularity. All models get benchmarked in Phase 0 before anything is locked in.

| Role | Primary pick | Alternative | Why |
|---|---|---|---|
| **Inference runtime** | **llama.cpp `llama-server`** | Ollama (fine for quick trials) | Direct control over prefix/KV caching, slots, **GBNF/JSON-schema constrained output** (reliable tool calls from small models), thread pinning, Vulkan experiments. Ollama wraps llama.cpp and hides those controls. |
| **Main conversational + tool LLM** | **Gemma 4 E4B-it, Q4_0** *(Phase 0: 30/32 tool cases, warm median 4.7 s; the 2 misses were clarifying questions)* | **Qwen 3.5 4B, Q4_K_M** *(32/32, but warm median 8.8 s because its tool-call format uses about twice as many output tokens)* | The 4B class is the largest that stays conversational (~5–9 tok/s). In one community CPU tool-calling test these two scored highest (~94% and ~91%), while Phi-4-mini failed with stock llama.cpp because of its tool-call format. That is a single-author result, so **we re-test on your machine**. Gemma 4 E4B also accepts images, so one model could cover vision. |
| Background "deep think" | 7–8B class, Q4 | — | 2–4 tok/s is too slow for conversation but fine for **overnight memory consolidation, summaries and code-error analysis** while plugged in. |
| Phone-side LLM | **None in v1** (experiment: ~0.5B Q4) | — | 3 GB RAM and A53 cores give about 2–4 tok/s with poor reasoning. A grammar-based command parser beats it on reliability. |
| Embeddings (memory, intent matching) | **bge-small-en-v1.5** (33 M) | EmbeddingGemma-300M | Milliseconds per query on CPU, which is enough for intent routing and memory recall. |
| Laptop OCR | **Windows.Media.Ocr** (built in) | RapidOCR | Already on the machine, offline, fast. For "read my screen", OCR plus the UI Automation tree beats a VLM on both speed and accuracy. |
| Laptop VLM (screenshots, camera "what is this?") | **Gemma 4 E4B vision** (if the llama.cpp mmproj works) | Moondream 2 | Runs only on demand. Expect **10–30 s per image** on this CPU; it is not for real-time use. |
| Phone object detection | EfficientDet-Lite0 / YOLO-nano (TFLite, CPU) | — | A few FPS on A53, enough for "point at it" labels. Detailed questions go to the laptop VLM. |

**Pushback:** a 7B+ model as the conversational brain would be smarter but would take 10+ s per answer on this laptop, and that delay destroys the assistant illusion. A fast, reliable 4B with good tools will feel more like JARVIS than a slow 8B.

---

## 5. Offline speech: recognition and synthesis

**Use [sherpa-onnx](https://k2-fsa.github.io/sherpa/onnx/) as the single speech runtime on both devices, pinned to 1.13.8** (2.x dropped the espeak-ng frontend Piper voices need). It provides wake word (keyword spotting), Silero VAD, streaming and offline ASR, and TTS (Piper/VITS, Kokoro), with an Android AAR and Python wheels. One library means one set of model formats and one set of bugs to learn.

| Function | Phone | Laptop | Why |
|---|---|---|---|
| Wake word | **sherpa-onnx KWS**, phrase "hey frosti" (open-vocabulary, no training needed) | — | Must run on the device that has the mic, at low power. openWakeWord needs a custom-trained model and is Python-first, which makes it awkward on Android. |
| VAD | Silero (sherpa-onnx) | Silero | Endpointing decides how quickly FROSTI responds after you stop talking. |
| ASR (main) | streams 16 kHz audio to laptop | **Parakeet-TDT 0.6B v2 (int8)** via sherpa-onnx *(Phase 0: 0% WER, 0.5 s per command)*; alt: Moonshine base *(3.3% WER, 0.24 s)* | Parakeet is very accurate on English and fast on CPU. Whisper is the fallback for multilingual use. |
| Offline commands (Satellite mode) | **sherpa-onnx KWS spotter with a fixed phrase list** ("open spotify", "battery", …), same model as the wake word | — | *Phase 0:* the 20M streaming Zipformer had **~63% WER** and drops the first word, so it is unusable. The 3.3M KWS model detected "hey frosti" 3/3 with 0/4 false alarms at RTF 0.07 on one thread; a fixed phrase list reuses it. |
| TTS (main) | plays audio streamed from the laptop | **Piper** (pick a voice you like; several British English voices exist), sentence-streamed | Faster than real time on CPU; first audio in roughly 0.3 s. |
| TTS (quality option) | — | ~~Kokoro-82M~~ **rejected** | *Phase 0:* first sentence 1.6 s, RTF 1.16 (slower than real time). Piper alan-medium: 0.10 s, RTF 0.11. |
| TTS (offline phone) | Android system TTS (Samsung/Google offline voice) or Piper-low on device | — | Already installed and costs nothing. |

**Continuous conversation:** after FROSTI replies, the mic stays open for about 6 s for follow-ups without the wake word. Barge-in (interrupting FROSTI mid-sentence) works if the phone does basic echo cancellation (Android `AcousticEchoCanceler`; quality varies by device, so test it).

**Limitations:** the J7 Pro mic is designed for a hand-held phone. Expect usable wake-word range of **about 2–3 m in a quiet room**. Hands-free across a room won't match a smart speaker.

---

## 6. Distributing computation between phone and laptop

| Runs on phone (always) | Runs on laptop (when reachable) | Why |
|---|---|---|
| UI, animation, launcher, HUD | LLM, intent routing | UI must never wait on the network; AI needs the bigger CPU. |
| Wake word, VAD, audio capture/playback | ASR, TTS, embeddings | Wake word needs the mic and low latency; ASR quality needs compute. |
| Executing phone actions (open app, settings, notifications) | Deciding *which* action to take | The phone is the only place phone actions can happen. |
| Cached memory (read-only) and the offline command set | Memory (source of truth), scheduler, watchers | One writer avoids sync conflicts. |
| Camera capture, quick object labels | VLM, OCR | Raw frames are cheap to send; understanding them is expensive. |

**Connectivity, all offline-capable:**
1. Shared Wi-Fi (normal).
2. **Windows Mobile Hotspot**, which works without internet; the phone connects to the laptop directly.
3. **USB:** `adb reverse tcp:7777 tcp:7777` lets the phone reach the core over the cable. Zero network setup and charging at the same time, which suits a docked console.

Discovery uses mDNS (`_frosti._tcp`), with a pinned last-known address as fallback.

---

## 7. How FROSTI gets permission to control the system

### 7.1 Phone, without root (recommended baseline)

| Mechanism | Grants | Setup |
|---|---|---|
| **Default Home app** | FROSTI *is* the home screen | One-time settings choice |
| **Device Owner** (`DevicePolicyManager`) | **Lock-task (kiosk) mode, disabling the status bar and keyguard features, silent app install/uninstall, hiding apps, system update policy, setting global settings** | `adb shell dpm set-device-owner com.frosti.shell/.system.FrostiAdmin`. **This requires no accounts on the device.** *Phase 0:* the phone has 0 accounts and 1 user, so it can be provisioned **without a factory reset**. Don't add a Google/Samsung account before provisioning. |
| **AccessibilityService** | Read the screen's UI tree ("what's on my screen"), perform taps and scrolls in other apps, global actions (back, recents, notifications, power dialog) | User toggle in Settings |
| **NotificationListenerService** | Read, dismiss and act on all notifications, the basis for FROSTI's own notification centre | User toggle |
| `WRITE_SETTINGS` / `WRITE_SECURE_SETTINGS` (via adb `pm grant`) | Brightness, screen timeout, location mode, and so on | One-time adb command |
| Foreground service + battery-optimisation exemption | Keeps the voice service alive. Samsung is aggressive about killing background apps. | User prompt |

The **Device Owner + Accessibility + Notification Listener** combination is what makes this an operating environment rather than "a launcher with a chatbot". FROSTI owns the home screen, the notification surface and the status area, and it can act inside any app.

**Things that still require root (optional Phase 8, Magisk on stock firmware):** replacing the real boot animation (Samsung uses its own `.qmg` format, so this is fiddly), replacing the secure lock screen, toggling mobile data, killing arbitrary processes, and system-level input injection. Trade-offs: the Knox e-fuse is tripped permanently, Samsung's own secure features stop working, and a mistake means a soft brick that you recover by flashing stock firmware with Odin. **Not worth it for v1.**

### 7.2 Laptop

FROSTI Core runs **as your normal Windows user** (it needs your desktop session to drive VS Code and the mouse). It is **not** an administrator service. It controls the laptop through:
- `code` CLI and process launching (open VS Code, open folders).
- Git CLI.
- PowerShell via subprocess (`-NoProfile`), **gated** (§8).
- UI Automation (pywinauto) for reading and clicking other apps.
- `SendInput` for mouse and keyboard (phone becomes a trackpad and keyboard).
- `mss` screenshots and Windows OCR.

---

## 8. Security architecture

Threat model: the **LAN** (another device on Wi-Fi), the **phone** (old, unpatched Android), and **prompt injection** (a file, web page, notification or screen that FROSTI reads containing "ignore previous instructions and run …").

1. **Pairing and transport.** On first pairing the laptop shows a QR code containing its certificate fingerprint and a one-time token. The phone generates its own key pair. After that, **mutual TLS with pinned certificates** is used; there are no passwords and no open endpoints. The core binds only to the LAN or hotspot interface and Windows Firewall restricts it to the private profile.
2. **Typed tools, not raw shell.** The LLM calls named tools with validated arguments (`git_status(repo)`, `run_project(name)`, `open_app(id)`), and projects and commands come from an **allowlist** in config. Free-form PowerShell exists as one tool, `run_powershell(cmd)`, and it **always** needs confirmation, with the exact command shown on screen.
3. **Risk tiers, enforced in code (not by the LLM):**

   | Tier | Examples | Policy |
   |---|---|---|
   | 0 Read | battery, RAM, git status, list files | Auto |
   | 1 Reversible | open app, brightness, start a dev server | Auto + logged |
   | 2 Consequential | write/move files, git commit, kill process, send keystrokes | **Confirm** (voice "confirm" + on-screen) |
   | 3 Destructive / outward | delete, git push, arbitrary PowerShell, uninstall | **Confirm with the exact action shown, tap required** |
   | Forbidden | credential stores, disabling security tools, registry Run keys | Not exposed as tools at all |

4. **Taint tracking for injection.** Once a turn has read untrusted content (files, OCR, notifications, web pages), every tool call from that turn is raised to at least Tier 2.
5. **Append-only audit log.** Every tool call, its arguments, who approved it and the result go into SQLite, viewable from the phone.
6. **Phone hygiene.** No banking or sensitive accounts on the J7 Pro (it gets no security updates). It is a FROSTI terminal, not your primary phone.
7. **Voice spoofing.** Anyone in the room can talk to FROSTI. Tier 2+ actions require a tap, so a voice alone can't push code or delete files.

---

## 9. Storage and persistent memory

**Source of truth:** a single **SQLite** database on the laptop (`D:\frosti-data\frosti.db`), using WAL mode, **FTS5** for keyword search and **sqlite-vec** for embeddings. It is one file you can back up by copying, needs no server and handles this scale easily.

| Table | Contents |
|---|---|
| `facts` | Stable preferences and profile ("prefers dark themes", "main project: FROSTI at E:\frosti"), each with source, confidence and last-confirmed date |
| `projects` | Path, stack, run/test commands, last state. This feeds `run_project` |
| `episodes` | Per-conversation summaries with embeddings (raw transcripts are kept for N days, then summarised) |
| `tasks` / `schedules` | Reminders and scheduled automations |
| `events` | Watcher output (build failed, battery low, …) |
| `audit` | Security log (append-only) |

**How memory is written:**
- **Explicitly:** "remember that…" writes immediately.
- **Implicitly:** the LLM *proposes* facts during a conversation, and they are written as `pending`.
- **Nightly consolidation** (7–8B model, laptop plugged in and idle) merges duplicates, expires stale facts and summarises episodes.
- A **memory screen on the phone** lets you see, edit and delete anything FROSTI knows. That is necessary for trust, and it fixes the inevitable wrong memories.

**Recall:** each turn pulls the top-k facts and episodes (hybrid FTS + vector search) into the prompt. That is about 200–400 tokens, kept small because of the prompt-processing cost.

**Phone:** a Room/SQLite cache holding a read-only snapshot of facts and the app index, used by Satellite mode.

---

## 10. UI/UX design direction

**Principle:** FROSTI is a *presence*, not a page of icons. The core orb is the centre of the experience and everything else orbits it.

- **Boot sequence:** a real boot animation needs root (see 7.1). Instead, on `BOOT_COMPLETED` the shell plays a **2–3 s FROSTI initialisation sequence** (system checks scroll, core ignites, greeting). For the user this is the boot experience, and it costs nothing.
- **Ambient / lock layer:** a `showWhenLocked` activity drawn over the keyguard shows a dim core, clock and next event. **Keep the system PIN underneath it.** Replacing the secure lock screen itself would weaken security on an unpatched device.
- **Home:** core orb in the middle; a ring of contextual "pods" around it (laptop status, current project, next task, battery/RAM). Swipe up opens the launcher.
- **Core orb states:** idle (slow breathing), listening (reacts to mic amplitude), thinking (orbiting particles), speaking (waveform), alert (amber), error (red). Drawn with a **custom GLES fragment shader** at ~540p and upscaled, so it stays smooth on a Mali-T830.
- **Launcher:** voice-first, with a fuzzy-search text field. App ranking uses **frequency × time-of-day × recency**, which is a few lines of arithmetic rather than ML and works well.
- **HUD:** monospace numerals, thin vector gauges for CPU/RAM/battery/temperature on both devices, laptop build status.
- **Notification centre:** FROSTI's own panel built on NotificationListener, grouped and summarised by the laptop LLM when it's connected. The system shade is disabled in Device Owner mode.
- **Visual language:** true-black backgrounds, **one accent colour** (e.g. cyan) plus amber for alerts, a geometric display font with a monospace font for data, and motion with physical easing. **Make it original rather than a copy of the Iron Man HUD**, both for legal reasons and because your own identity will age better.
- **AMOLED care:** HUD elements drift a few pixels over time; ambient mode dims after 2 min, which also reduces burn-in.
- **Performance targets:** 60 fps on touch transitions, **30 fps for ambient orb animation** (saves battery and GPU). Profile on the real device from week one.

---

## 11. Development tools and languages

| Area | Choice | Why |
|---|---|---|
| Phone app | **Kotlin + Jetpack Compose**, custom GLES views; `minSdk 28` | First-class Android; Compose supports Android 9; Kotlin gives direct access to DevicePolicyManager, Accessibility and audio APIs that a web or Flutter shell would need native glue for anyway. |
| Phone speech | sherpa-onnx Android AAR | See §5 |
| Laptop core | **Python 3.12** (venv via `uv`), `asyncio` + `websockets`/FastAPI | Best local-AI ecosystem; sherpa-onnx, pywinauto and winrt bindings all exist. 3.12 rather than your installed 3.14 for wheel compatibility. |
| LLM server | Prebuilt **llama.cpp** Windows binaries (AVX2) | Nothing to compile; the core talks to it over local HTTP. |
| Protocol | JSON messages + binary audio frames over WebSocket; schemas in `protocol/` | Human-readable and debuggable; one spec for both sides. |
| Storage | SQLite (+ FTS5, sqlite-vec); Room on Android | |
| Tooling | Android Studio, `adb` (platform-tools), VS Code, Git, pytest | Android Studio needs ~8 GB of disk. **E: doesn't have room**, so install it on D:. |

---

## 12. Project folder structure

```
E:\frosti\
├─ docs\                      proposal, ADRs (one file per decision), protocol spec
├─ protocol\                  message + tool JSON schemas (single source of truth)
├─ core\                      laptop brain — Python
│  ├─ pyproject.toml
│  ├─ frosti_core\
│  │  ├─ gateway\             websocket server, pairing, mTLS, sessions
│  │  ├─ voice\               vad, asr, tts, audio streaming
│  │  ├─ brain\               fast-path router, llm client, prompts, persona
│  │  ├─ tools\               registry + windows\, vscode\, git\, files\, processes\, input\, phone\
│  │  ├─ security\            risk tiers, confirmation, taint, audit
│  │  ├─ memory\              store, embeddings, recall, consolidation
│  │  ├─ scheduler\           tasks, watchers, proactive events
│  │  └─ vision\              screenshot, OCR, VLM
│  ├─ config\                 frosti.toml, projects allowlist, tools policy
│  └─ tests\
├─ shell\                     phone body — Android (Kotlin)
│  ├─ app\src\main\java\…\frosti\
│  │  ├─ ui\                  home, core orb, hud, launcher, notifications, ambient, memory, settings
│  │  ├─ voice\               capture, wake word, local asr, playback
│  │  ├─ link\                core client, discovery, pairing
│  │  ├─ system\              accessibility, notification listener, device admin, app index, settings control
│  │  ├─ satellite\           offline grammar + executor
│  │  └─ data\                Room cache
│  └─ build.gradle.kts
├─ scripts\                   setup-laptop.ps1, provision-phone.ps1 (adb/dpm), start-core.ps1
├─ bench\                     LLM / ASR / TTS latency benchmarks (Phase 0)
└─ models.toml                model names, URLs, SHA-256 — weights live in D:\frosti-models (not in repo)
```

---

## 13. Difficulty estimate

| Component | Difficulty (1–5) | Notes |
|---|---|---|
| Core LLM + tool calling | 3 | Prompt-caching and grammar setup is the fiddly part |
| Voice loop (wake → ASR → TTS) | 4 | Latency tuning, echo cancellation, endpointing |
| Phone shell UI + orb | 3 | Mostly design effort; shader performance on Mali |
| Device Owner / Accessibility control | 3 | Samsung quirks, provisioning flow |
| Laptop control (VS Code, Git, PowerShell, input) | 2–3 | Well-trodden APIs |
| Security (pairing, mTLS, tiers) | 3 | Must be right from the start, not bolted on |
| Memory + consolidation | 3 | Getting *useful* memory is harder than storing it |
| Vision | 3 | Easy to build, slow to run on this CPU |
| Custom ROM / kernel (rejected) | 5 | — |

A solo developer working part-time should expect **about 6–9 months to a polished v1** (Phases 0–6). A demo-quality voice loop is about 4–6 weeks away.

---

## 14. Phased roadmap

Each phase ends with something usable and a measured exit criterion.

| Phase | Goal | Exit criterion |
|---|---|---|
| **0. Measure** (1–2 wk) | Install llama.cpp, sherpa-onnx, adb. Benchmark Gemma 4 E4B vs Qwen 3.5 4B (tok/s, prompt-processing speed, tool-call accuracy on ~30 FROSTI-specific cases), Parakeet vs Whisper, Piper vs Kokoro. On the phone: Android version, model number, KWS CPU usage and battery drain per hour. | A numbers table; models chosen by data |
| **1. Brain** (3–4 wk) | Core text-only: llama-server, fast-path router, 10 tools, risk tiers, audit log, SQLite memory, CLI client | "Open VS Code in frosti and show git status" works by text: fast-path <1 s, LLM-routed <6 s warm (Phase 0 measured ~5 s per tool call) |
| **2. Link + voice** (3–4 wk) | Shell app (plain UI), pairing + mTLS, push-to-talk, streamed ASR/TTS | End-to-end voice answer, median <3 s (LLM) / <1 s (fast path) |
| **3. The OS feel** (4–6 wk) | Launcher as HOME, core orb, HUD, boot sequence, notification centre, Device Owner lock-task | The phone boots straight into FROSTI; no stock UI is visible in normal use |
| **4. Phone intelligence** (3–4 wk) | Wake word, Accessibility actions, settings control, Satellite (offline) mode, continuous conversation | Hands-free control at a 2 m desk distance; useful with the laptop off |
| **5. Laptop control** (3–4 wk) | Projects allowlist, run/test/inspect errors, git workflows, trackpad/keyboard mode, gated PowerShell | "Run the core tests and tell me what failed" works |
| **6. Memory + proactivity** (3–4 wk) | Recall, memory screen, nightly consolidation, scheduler, watchers (build failure, battery, temperature, reminders) | FROSTI brings up something useful without being asked, at most a few times a day |
| **7. Vision** (2–3 wk) | Laptop screen understanding (OCR + UIA), phone screen (accessibility tree), camera "what is this" | Answers about the current screen in <5 s; images in <30 s |
| 8. Optional: root polish | Magisk on stock firmware: real boot animation, deeper control | Only if you accept the Knox and soft-brick trade-offs |

---

## 15. Major limitations and risks

| Risk | Likelihood | Mitigation |
|---|---|---|
| **LLM latency on a 15 W CPU** breaks the illusion | High | Fast path for common intents, prefix caching, short prompts, streaming TTS, earcons and animation |
| Thermal throttling during long sessions | High | Keep the laptop plugged in; limit llama threads to 4; run heavy jobs at night |
| Small models choose wrong tools or hallucinate arguments | Medium | Grammar-constrained output, schema validation, confirmation tiers, a per-intent subset of tools |
| Laptop asleep or off means no brain | High | Satellite mode; optional wake-on-LAN; keep the laptop awake while plugged in |
| Samsung kills background services | High | Foreground service, battery exemption, Device Owner |
| Device Owner provisioning needs an account-free device | Low (0 accounts today) | Provision before adding any account; back up first; provision once |
| Old battery, 24/7 charging, swelling | Medium | Inspect the battery; watch temperature in the HUD; replace the cell if swollen |
| Unpatched Android 9 | Certain | Treat the phone as untrusted; keep sensitive accounts off it; mTLS + tiers |
| Phone mic range and echo | Medium | Desk-console use; tap fallback; tune AEC |
| Scope creep (this is a large project) | High | The phased exit criteria above; nothing in Phase N+1 until Phase N meets its numbers |
| Low free RAM on laptop (~5 GB) | Medium | Load the VLM and big model on demand only; keep the main stack under 5 GB |

### Ideas I'm pushing back on, and what to do instead

| Your idea | Problem | Instead |
|---|---|---|
| Custom OS / ROM on the J7 Pro | Broken audio in the only modern ROM; archived Linux port; AOSP builds impossible on this laptop | Device Owner shell (feels the same, keeps working hardware) |
| AI running fully on the phone | 3 GB RAM, A53 cores | Phone = body, laptop = brain; Satellite mode for offline basics |
| Always-listening on battery | Drains a degraded battery fast | Always-listening while docked/charging; tap or raise-to-wake otherwise |
| Real-time camera/screen understanding by VLM | 10–30 s per image on this CPU | Accessibility tree + OCR for screens (fast); VLM on demand; tiny on-phone detector for live labels |
| Voice-driven arbitrary terminal commands | One misheard word or injected instruction could do damage | Typed tools + allowlist; raw PowerShell always confirmed on screen |
| Replacing the real lock screen | Weakens security on an unpatched device | FROSTI ambient layer over the secure keyguard |
| A larger "smarter" model | Too slow to feel like JARVIS | 4B for conversation, 7–8B for background work |

---

## 16. Immediate next steps (when you approve)

1. Tell me the phone's **model number** (Settings → About phone) and **Android version**.
2. Approve installing to **D:**: llama.cpp binaries, Android platform-tools (`adb`), a Python 3.12 venv, and ~6 GB of models.
3. I run **Phase 0 benchmarks** and report the numbers before any architecture code is written.

---

### Sources
- [XDA: LineageOS 18.1 alpha for J7 Pro](https://forum.xda-developers.com/t/alpha-rom-r-lineageos-18-1-for-j7-pro.4496927/) — audio broken, kernel 3.18
- [TWRP device tree, SM-J730F](https://www.github.com/razkal1/twrp_device_samsung_j7y17lte) · [/e/OS-Q community build](https://community.e.foundation/t/custom-build-samsung-j7-2017-j7y17lte-e-os-q/55828) · [Exynos 7870 ROM collection](https://awesome.ecosyste.ms/projects/github.com%2Fbrunoliratm%2Frepository-roms-exynos7870)
- [postmarketOS: J7 Pro device page](https://wiki.postmarketos.org/wiki/Samsung_Galaxy_J7_Pro_(samsung-j7y17lte)) · [Exynos 7870 family page](https://wiki.postmarketos.org/wiki/Samsung_Exynos_7870_Octa) · [linux-samsung-j7y17lte package](https://pkgs.postmarketos.org/package/master/postmarketos/aarch64/linux-samsung-j7y17lte) · [Phoronix: Exynos 7870 in Linux 6.18](https://phoronix.com/news/Samsung-Exynos-7870-Linux-6.18)
- [Termux on F-Droid](https://f-droid.org/en/packages/com.termux/) (Android 7+)
- [On-device tool calling 2026: Qwen3 / Gemma 4 / Phi-4](https://www.ertas.ai/blog/on-device-tool-calling-2026-qwen3-gemma4-phi4) · [Best CPU-only local LLMs 2026](https://www.popularai.org/p/best-cpu-only-local-llm-2026)
- [sherpa-onnx overview](https://osrepos.com/repo/k2-fsa-sherpa-onnx) · [Offline TTS eval on Android](https://voiceping-landingpage-prod.pages.dev/en/blog/research-offline-tts-eval/) · [VoxSherpa TTS](https://github.com/CodeBySonu95/VoxSherpa-TTS)
