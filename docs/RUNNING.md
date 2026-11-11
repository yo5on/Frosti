# Running FROSTI (MVP)

## Offline wireless (laptop hotspot)

The laptop advertises **FROSTI** using Wi-Fi Direct legacy access-point mode. No router,
internet connection, or wireless ADB is needed. Leave Windows Mobile Hotspot off;
it competes for the same adapter. The phone remembers this network and reconnects.
If Android asks, choose to stay connected despite having no internet.

Wireless traffic uses TLS on `192.168.137.1:8766`. The Android app trusts only the
certificate transferred during USB provisioning and still authenticates with its
pairing token. The existing laptop console stays at `http://127.0.0.1:8765/`.

One-time setup (already performed on this laptop):

1. `python scripts/prepare-wireless.py --python E:/sparky/.venv/Scripts/python.exe`
   uses the setup Python's installed `cryptography` package. Identity files and the
   random hotspot password are stored under ignored `data/`; keep these private.
2. Run `scripts/fetch-wake-runtime.ps1` once (50 MB official Android runtime, SHA-256 checked).
   Build the APK, then run `scripts/provision-phone.ps1 -Wireless` over USB.
   Android 9 joins the hotspot automatically; newer Android needs manual Wi-Fi setup.
3. Run `scripts/install-wireless-startup.ps1` as administrator. It installs the
   **FROSTI wireless** task at user sign-in and a firewall rule restricted to the
   hotspot address/subnet, TLS port, and Core interpreter.

To start manually: `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/start-hotspot.ps1`.
The process must stay running to maintain the hotspot. It restarts the access point
after interruptions. The phone reconnects on Wi-Fi/address changes, binds to that
Wi-Fi network even without internet, and renews a stale hotspot connection on Android 9.
Startup logs are in
`data/core.log` and `data/core-error.log`. The scheduled task retries failures three times.

The hotspot currently uses Windows' observed `192.168.137.1` address. If Windows
changes it, startup fails explicitly; reconfigure and re-pair instead of disabling
certificate checks. Pairing expires with the laptop certificate after ten years.
The laptop must be awake and the phone app running; startup occurs after sign-in.

To disable automatic startup, disable **FROSTI wireless** in Task Scheduler.
Once `data/wireless.json` exists, `scripts/provision-phone.ps1` pairs wirelessly by default (so re-installing a build keeps the phone on Wi-Fi). To return the phone to USB, run it with `-Usb`.
Remove or rename `data/wireless.json` to disable the Core's wireless listener.

## One-time setup
Run these once. The toolchain is already on `D:\frosti-tools`, and the models are in `D:\frosti-models` (`scripts\fetch-models.ps1`).

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
- with `-OsMode`, makes FROSTI Device Owner so it becomes the phone's whole OS: pinned as Home, no status bar,
  notification shade or lock screen (Home and the power menu still work; other apps open inside it).
  The recents key flips the Shell between landscape and portrait.
  The phone must have no accounts. To undo: Setup › OS mode › Leave on the phone, or
  `adb shell dpm remove-active-admin com.frosti.shell/.FrostiAdmin`.

It never provisions Device Owner.

## Every day
```
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\start-frosti.ps1
```
This loads speech (~7 s), opens the gateway, and starts llama-server with Gemma 4 E4B. Direct commands work straight away. Open-ended requests work once `[llm] ready` appears, about 1 minute later. You can also type commands into this console. Use `-NoLlm` for an instant start that only handles fast-path commands. This is the USB start: once wireless is set up (`data/wireless.json` exists) it refuses and points you to `scripts/start-hotspot.ps1`; add `-Usb` to run over USB anyway.

On the laptop, open **http://127.0.0.1:8765/** for the console. It shares the phone's screens (Home, Talk, System, Tools, Memory and Setup) and shows the phone's conversation live. It is served only to this laptop: the Core checks both the address and the Host header. The design system is in `docs/UI.md`.

On the phone:
- say **"Frosti"** (or **"Hey Frosti"**), wait for **"Yes?"**, then speak your request
- or hold **Hold to talk**
- or type a command

After FROSTI replies, the mic stays open for 6 s so you can follow up without the wake word.
The phone detects the wake word locally. Idle audio stays on the phone; only listening
and push-to-talk audio is sent to the laptop. Bella's "Yes?" is generated once when
the Core loads and reused for every wake-up. An explicit playback end marker opens
listening as soon as the acknowledgement finishes, without a fixed extra delay.

The phone can also listen and reply with its screen off. Its foreground voice service
holds CPU and Wi-Fi locks until stopped; provisioning exempts FROSTI from Android
battery optimisation. This uses more battery than an idle phone. The laptop must
remain awake and the hotspot connected. The dim idle clock also continues listening.

The current offline voice is **Kokoro Bella** (`kokoro-en-v0_19`, speaker `1`), configured
in `core/config/frosti.toml`. A sample is saved at `data/voice-bella.wav` on this laptop.

Wake-detector checks: run `.venv/Scripts/python.exe scripts/prepare-wake-tests.py`,
build `assembleDebugAndroidTest`, install its test APK, then run
`adb shell am instrument -w com.frosti.shell.test/com.frosti.shell.WakeWordCheck`.
This feeds synthetic speech directly to the phone's detector without recording or
playing audio. The test checks both wake phrases, two negative phrases, and real-time
processing speed. The initial J7 Pro check used 28–36% of the audio duration.

## What works (measured 2026-11-11 on the J7 Pro over USB)
| Path | Example | Time |
|---|---|---|
| Fast path (rules) | "how much battery does the phone have" → phone tool | 0.07 s |
| LLM (Gemma 4 E4B) | "what is the capital of France" | 6.9 s |
| Voice, wake word | "Hey Frosti, what's my laptop RAM usage?" spoken to the phone → answered aloud | verified by the user |

Tools:
- **Phone:** open an app, battery / RAM / storage / temperature, brightness, volume.
- **Laptop:** open an app, system status, open a folder in VS Code, git, run a project's tests / build / dev server, PowerShell, kill a process.
- **Both:** timers, remember / recall.

Risk tiers are enforced in code:
- **Tier 2** (git commit, kill process) needs a tap on the phone.
- **Tier 3** (git push, PowerShell) also needs a tap, and the dialog shows the exact action.

Every tool call goes into the append-only `audit` table in `data\frosti.db`.

## Without the laptop (Satellite mode)
When the phone can't reach the Core, say "Frosti", wait for the beep, then say a command
within 5 seconds. The phone answers in its own system voice. There's no LLM, only a fixed
phrase list: battery / storage / temperature, "what time is it", volume up / down / mute,
brightness up / down, play / pause / next song / previous song, and "open" followed by
youtube, spotify, camera, settings, chrome, whatsapp, gallery, phone, messages, clock,
calculator, calendar or maps. Edit the list in `scripts/prepare-satellite.py`, run it, and
rebuild the Shell. The wake-detector check above also checks three of these commands.

## Known MVP shortcuts (upgrade path)
- **Wake-word detection runs on the phone CPU.** It avoids idle microphone uploads;
  actual battery savings need a longer measurement on this phone. The laptop detector
  remains for older clients. Both use `core/config/wake-words.txt`.
- **USB uses a token over loopback; offline wireless uses TLS with a paired server certificate and token.** QR pairing and mutual TLS from the proposal remain future work.
- **Not built yet:**
  - screen understanding (`describe_screen`, Phase 7)
  - Device Owner lock-task, the Accessibility service, and the notification centre (Phases 3–4)
  - taint tracking
  - proactive watchers
- **Laptop temperature** isn't readable without admin rights, so FROSTI reports CPU load instead.
