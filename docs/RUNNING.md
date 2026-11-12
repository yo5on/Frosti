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
The process must stay running to maintain the hotspot. It checks every 3 s that the
hotspot address is actually up (Windows can drop the access point while still reporting it
started) and restarts it within ~6 s, retrying every 15 s; at sign-in it keeps retrying
until Wi-Fi is ready. Its log is `data/hotspot.log`. The phone reconnects on Wi-Fi/address changes, binds to that
Wi-Fi network even without internet, and renews a stale hotspot connection on Android 9.
Failed connections time out after 3 s and retry after 3 s, so the phone notices the
laptop coming back without waiting through a long retry backoff.
The laptop's Intel AC 8265 hotspot drops the phone for a few seconds every couple of minutes
(not fixed by the iPhone link, power settings or driver 20.70.32.1). To keep that harmless, replies
are numbered: the phone's hello says which one it last received, and the Core resends a newer
reply under 60 s old, so an answer given during a drop is still shown and spoken.
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
Run these once. The toolchain is already on `D:\frosti-tools`, and the speech models are in `D:\frosti-models` (`scripts\fetch-models.ps1`).

FROSTI has no language model: every command is matched by the rules in `core/frosti_core/router.py`. Anything they don't cover gets "Sorry, I didn't catch that".

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

### Spotify (optional)
"Play *song* on Spotify" plays the top match in the laptop's Spotify app and shows a
now-playing card (cover, title, artist) on the phone and the console. Finding the track
uses Spotify's search API, so it needs the laptop online and a free developer app:
create one at developer.spotify.com (any redirect URI, Web API), then save
`data/spotify.json` as `{"client_id": "…", "client_secret": "…"}`. Keep it private; `data/`
is git-ignored. Without the file, FROSTI opens Spotify's search page instead.

**Music** on the phone is a desk now-playing panel for the laptop: cover, title, artist, a
live progress bar, and shuffle / previous / play-pause / next buttons. The buttons act on
the laptop's current media session directly, with no spoken reply. Shuffle shows only when the
app supports it. While Music is open, the idle clock doesn't take over the screen.

The latest now-playing card stays live: it follows whatever the laptop plays (Spotify, a
browser tab, any app that reports to Windows' media controls) and shows Paused when stopped.
It needs `uv pip install --python .venv/Scripts/python.exe winrt-Windows.Media.Control==3.2.1
winrt-Windows.Storage.Streams==3.2.1 winrt-Windows.Foundation==3.2.1`; without them the card stays as it was first shown.

## Every day
```
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\start-frosti.ps1
```
This loads speech (~7 s) and opens the gateway. Commands work straight away. You can also type commands into this console. This is the USB start: once wireless is set up (`data/wireless.json` exists) it refuses and points you to `scripts/start-hotspot.ps1`; add `-Usb` to run over USB anyway.

On the laptop, open **http://127.0.0.1:8765/** for the console. It shares the phone's screens (Home, Talk, System, Tools, Memory and Setup) and shows the phone's conversation live. It is served only to this laptop: the Core checks both the address and the Host header. The design system is in `docs/UI.md`.

On the phone:
- say **"Frosti"** (or **"Hey Frosti"**), wait for **"Yes?"**, then speak your request
- or hold **Hold to talk**
- or type a command

After FROSTI replies, the mic stays open for 6 s so you can follow up without the wake word.
The phone detects the wake word locally. Idle audio stays on the phone; only listening
and push-to-talk audio is sent to the laptop. The "Yes?" is generated once when
the Core loads and reused for every wake-up. An explicit playback end marker opens
listening as soon as the acknowledgement finishes, without a fixed extra delay.

The phone can also listen and reply with its screen off. Its foreground voice service
holds CPU and Wi-Fi locks until stopped; provisioning exempts FROSTI from Android
battery optimisation. This uses more battery than an idle phone. The laptop must
remain awake and the hotspot connected. The dim idle clock also continues listening.

Speech is set in `[speech]` of `core/config/frosti.toml`, picked for low RAM (about 400 MB for the whole Core):
**Moonshine** recognises speech (`asr = "moonshine"`) and **Piper alan** speaks (`vits-piper-en_GB-alan-medium`).
The previous pair, Parakeet + Kokoro Bella (`asr = "parakeet"`, `voice = "kokoro-en-v0_19"`, `speaker = 1`), needs
about 1.2 GB and takes ~3 s to speak a sentence; a Bella sample is at `data/voice-bella.wav`.

Wake-detector checks: run `.venv/Scripts/python.exe scripts/prepare-wake-tests.py`,
build `assembleDebugAndroidTest`, install its test APK, then run
`adb shell am instrument -w com.frosti.shell.test/com.frosti.shell.WakeWordCheck`.
This feeds synthetic speech directly to the phone's detector without recording or
playing audio. The test checks both wake phrases, two negative phrases, and real-time
processing speed. The initial J7 Pro check used 28–36% of the audio duration.

## What works (measured 2026-11-11 on the J7 Pro over USB)

To prevent automatic laptop sleep while a process runs, say **keep the laptop awake
until blender exits**, or **keep the laptop awake until process 12345 exits** for
a PID from Task Manager. FROSTI monitors that process instance and allows normal
sleep again within 3 seconds of its exit; it does not shut down or force sleep.
If several processes match, it asks for a PID. **Let the laptop sleep** cancels
monitoring. Without a named process, keep-awake still uses five minutes of low CPU
as its stop condition. Closing the lid or manually choosing Sleep still follows
Windows settings. A download or render inside an app that stays open needs its own
completion detection; process tracking only detects the selected process exiting.

Phone timers use the installed Android Clock app, so they keep counting when the
laptop disconnects or shuts down. Use **Timer** / **Focus session** in Actions,
or **Memory → Tasks → New phone timer**. **Phone timers** opens Clock to view,
pause, or cancel them. Phone voice requests also start a timer in Clock when linked.
Laptop console timers still run on the laptop and disappear if Core restarts.

Without the laptop, say **Frosti**, wait for the beep, then **five minute timer**
(or **set a timer for five minutes**). Supported offline durations are 1, 2, 3, 5,
10, 15, 20, 25, 30, 45, and 60 minutes. Say **show timers** to open Clock.

| Path | Example | Time |
|---|---|---|
| Fast path (rules) | "how much battery does the phone have" → phone tool | 0.07 s |
| Voice, wake word | "Hey Frosti, what's my laptop RAM usage?" spoken to the phone → answered aloud | verified by the user |

Tools:
- **Phone:** open an app, battery / RAM / storage / temperature, brightness, volume.
- **Laptop:** open an app, system status, open a folder in VS Code, git, run a project's tests / build / dev server, kill a process, what's running ("what's running", also live on the System screen), sleep now ("put the laptop to sleep"), keep awake / allow sleep, shut down.
- **Both:** timers, alarms ("set an alarm for 7:30 am", "wake me up at 6"), task planner ("add task buy milk", "what are my tasks", "mark milk as done"; also Memory → Tasks), remember / recall.
- **Spotify:** "play believer on spotify", or the Search Spotify button on the Music screen.

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
