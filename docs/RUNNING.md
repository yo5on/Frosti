# Running SPARKY (MVP)

## One-time setup
Run these once. The toolchain is already on `D:\sparky-tools`, and the models are in `D:\sparky-models` (`scripts\fetch-models.ps1`).

```
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\build-shell.ps1          # build the phone APK
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\provision-phone.ps1 -SetHome   # phone on USB
```

`provision-phone.ps1` does the following:
- installs the Shell app
- grants the mic and modify-system-settings permissions
- runs `adb reverse tcp:8765`
- passes the pairing token from `data\token.txt` to the app
- with `-SetHome`, makes the Shell app the Home screen. To undo that, go to Settings > Apps > Default apps > Home screen.

It never provisions Device Owner.

## Every day
```
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\start-sparky.ps1
```
This loads speech (~7 s), opens the gateway, and starts llama-server with Gemma 4 E4B. Direct commands work straight away. Open-ended requests work once `[llm] ready` appears, about 1 minute later. You can also type commands into this console. Use `-NoLlm` for an instant start that only handles fast-path commands.

On the phone:
- say **"Hey Sparky"** or tap the orb, then speak
- or hold **Hold to talk**
- or type a command

After SPARKY replies, the mic stays open for 6 s so you can follow up without the wake word.

## What works (measured 2026-11-11 on the J7 Pro over USB)
| Path | Example | Time |
|---|---|---|
| Fast path (rules) | "how much battery does the phone have" → phone tool | 0.07 s |
| LLM (Gemma 4 E4B) | "what is the capital of France" | 6.9 s |

Tools:
- **Phone:** open an app, battery / RAM / storage / temperature, brightness, volume.
- **Laptop:** open an app, system status, open a folder in VS Code, git, run a project's tests / build / dev server, PowerShell, kill a process.
- **Both:** timers, remember / recall.

Risk tiers are enforced in code:
- **Tier 2** (git commit, kill process) needs a tap on the phone.
- **Tier 3** (git push, PowerShell) also needs a tap, and the dialog shows the exact action.

Every tool call goes into the append-only `audit` table in `data\sparky.db`.

## Known MVP shortcuts (upgrade path)
- **Wake word runs on the laptop.** The phone streams its mic continuously over USB. The proposal puts KWS on the phone (sherpa-onnx AAR, armeabi-v7a), which matters once the link is Wi-Fi.
- **The link is a token over USB `adb reverse`, bound to 127.0.0.1.** The mutual-TLS/QR pairing (proposal §8) is required before using the LAN.
- **Not built yet:**
  - screen understanding (`describe_screen`, Phase 7)
  - Device Owner lock-task, the Accessibility service, and the notification centre (Phases 3–4)
  - taint tracking
  - proactive watchers
  - Satellite (offline) mode
  - battery-optimisation exemption on the phone
- **Laptop temperature** isn't readable without admin rights, so SPARKY reports CPU load instead.
