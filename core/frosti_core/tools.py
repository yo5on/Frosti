"""Tool registry: risk tiers enforced in code, laptop implementations.

Phone tools have no laptop implementation (run=None): the gateway forwards them to the Shell app.
Every run() returns (spoken_text, detail_text), optionally plus a media preview dict (title, artist, album, art, app).
"""
import base64, ctypes, difflib, json, os, re, shutil, subprocess, threading, time, urllib.parse, urllib.request
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Callable
import psutil
from . import CONFIG, DATA, ROOT

PROJECTS = {k: dict(v) for k, v in CONFIG.get("projects", {}).items()}
NO_WINDOW = subprocess.CREATE_NO_WINDOW

@dataclass
class Tool:
    name: str
    desc: str
    props: dict
    required: list
    tier: int | Callable          # 0 read, 1 reversible, 2 consequential (confirm), 3 destructive (confirm + exact action)
    run: Callable | None = None   # None -> executed on the phone
    phone_if: Callable | None = None  # args -> True when this call must run on the phone

    def tier_for(self, args):
        return self.tier(args) if callable(self.tier) else self.tier

    def on_phone(self, args):
        return self.run is None or bool(self.phone_if and self.phone_if(args))

    def schema(self):
        return {"type": "function", "function": {"name": self.name, "description": self.desc,
                "parameters": {"type": "object", "properties": self.props, "required": self.required}}}

def sh(cmd, cwd=None, timeout=60):
    p = subprocess.run(cmd, cwd=cwd, capture_output=True, text=True, timeout=timeout, creationflags=NO_WINDOW,
                       encoding="utf-8", errors="replace")
    return p.returncode, (p.stdout + p.stderr).strip()

def project(name):
    n = (name or "").lower().strip()
    for k, v in PROJECTS.items():
        if k in n or n in k:
            return k, Path(v["path"])
    raise ValueError(f"'{name}' isn't in my projects list. Known: {', '.join(PROJECTS) or 'none'}.")

# ---------- laptop implementations ----------
SAFE_NAME = re.compile(r"^[\w .+-]{1,40}$")
APP_ALIASES = {"vs code": "code", "vscode": "code", "visual studio code": "code", "file explorer": "explorer",
               "files": "explorer", "terminal": "wt", "browser": "msedge", "edge": "msedge", "calculator": "calc"}

START_APPS_PS = r"""$s = New-Object -ComObject WScript.Shell
@{apps = @(Get-StartApps | Select-Object Name, AppID)
  lnks = @(Get-ChildItem "$env:ProgramData\Microsoft\Windows\Start Menu", "$env:APPDATA\Microsoft\Windows\Start Menu" -Recurse -Filter *.lnk |
           ForEach-Object { @{n = $_.BaseName; p = $_.FullName; t = $s.CreateShortcut($_.FullName).TargetPath} })} | ConvertTo-Json -Compress -Depth 3"""
_apps = {"t": 0, "list": []}

def start_apps():
    """Start-menu apps as [{name, id (shell:AppsFolder AppID), lnk, exe}], cached for 10 minutes."""
    if time.time() - _apps["t"] > 600:
        rc, out = sh(["powershell", "-NoProfile", "-Command", START_APPS_PS], timeout=30)
        try:
            data = json.loads(out) if rc == 0 else {}
        except json.JSONDecodeError:
            data = {}
        lnks = {l["n"].lower(): l for l in data.get("lnks") or []}
        apps = [{"name": a["Name"], "id": a["AppID"]} for a in data.get("apps") or []]
        known = {a["name"].lower() for a in apps}
        apps += [{"name": l["n"], "id": None} for k, l in lnks.items() if k not in known]
        for a in apps:
            l = lnks.get(a["name"].lower(), {})
            exe = l.get("t") or (a["id"] if (a["id"] or "").lower().endswith(".exe") else "")
            a.update(lnk=l.get("p"), exe=exe if exe.lower().endswith(".exe") else "")
        _apps.update(t=time.time(), list=[a for a in apps if "uninstall" not in a["name"].lower()])
    return _apps["list"]

def find_app(name, apps):
    """Fuzzy match a spoken name to a Start-menu entry: exact, then whole words (shortest label), then close spelling."""
    n = name.lower().strip()
    by = {a["name"].lower(): a for a in apps}
    if n in by:
        return by[n]
    words = sorted((k for k in by if re.search(rf"\b{re.escape(n)}\b", k)), key=len)
    if words:
        return by[words[0]]
    close = difflib.get_close_matches(n, by, n=1, cutoff=0.8)
    return by[close[0]] if close else None

def open_app(name, device="laptop"):
    raw = name.strip()
    name = APP_ALIASES.get(raw.lower(), raw)
    if not SAFE_NAME.match(raw) or not SAFE_NAME.match(name):  # trust boundary: the name ends up in a shell 'start'
        return "That app name looks unsafe, so I didn't run it.", name
    app = find_app(raw, start_apps()) or find_app(name, start_apps())
    if app:
        if app["lnk"]:
            os.startfile(app["lnk"])
        else:
            subprocess.Popen(["explorer.exe", f"shell:AppsFolder\\{app['id']}"])
        return f"Opening {app['name']}.", app["lnk"] or app["id"]
    # PATH and App Paths (notepad, wt, msedge, code); no captured pipes: the app would inherit them and we'd wait until it closes
    rc = subprocess.run(["cmd", "/c", "start", "", name], stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                        stderr=subprocess.DEVNULL, creationflags=NO_WINDOW, timeout=30).returncode
    return (f"Opening {name}." if rc == 0 else f"I couldn't find {name} on the laptop."), ""

def get_status(metric, device="laptop"):
    if metric == "battery":
        b = psutil.sensors_battery()
        if not b:
            return "The laptop has no battery reading.", ""
        return f"Laptop battery is at {b.percent:.0f} percent{', charging' if b.power_plugged else ''}.", ""
    if metric == "ram":
        m = psutil.virtual_memory()
        return f"Laptop memory is {m.percent:.0f} percent used, {m.available / 2**30:.1f} gigabytes free.", ""
    if metric == "cpu":
        return f"Laptop CPU is at {psutil.cpu_percent(0.5):.0f} percent.", ""
    if metric == "storage":
        parts = [(p.device[:2], psutil.disk_usage(p.mountpoint)) for p in psutil.disk_partitions() if "fixed" in p.opts]
        detail = "\n".join(f"{d} {u.free / 2**30:.0f} GB free of {u.total / 2**30:.0f} GB" for d, u in parts)
        low = min(parts, key=lambda x: x[1].free)
        return f"Lowest free space is drive {low[0][0]} with {low[1].free / 2**30:.0f} gigabytes.", detail
    if metric == "processes":
        top = top_processes()
        spoken = ", ".join(f"{p['name'].removesuffix('.exe')} {p['cpu']:.0f} percent" for p in top[:3])
        return f"Busiest on the laptop: {spoken}.", "\n".join(f"{p['name']}  CPU {p['cpu']}%  RAM {p['ram'] / 2**20:.0f} MB" for p in top)
    if metric == "temperature":
        # shortcut: Windows exposes CPU temperature only to admin (WMI thermal zone); report load instead until a sensor helper exists
        return f"I can't read the laptop's temperature without admin rights. CPU load is {psutil.cpu_percent(0.5):.0f} percent.", ""
    return f"I don't know the metric {metric}.", ""

def top_processes(n=5):
    """Busiest programs, same-name processes summed (Chrome's many tabs = one row). CPU is % of the whole machine."""
    cores, by = psutil.cpu_count() or 1, {}
    for p in psutil.process_iter(["name", "cpu_percent", "memory_info"]):  # psutil caches Process objects, so cpu_percent is since the last call
        name, mem = p.info["name"], p.info["memory_info"]
        if not name or not mem or name == "System Idle Process":
            continue
        c, r = by.get(name, (0.0, 0))
        by[name] = (c + (p.info["cpu_percent"] or 0) / cores, r + mem.rss)
    top = sorted(by.items(), key=lambda kv: kv[1], reverse=True)[:n]
    return [dict(name=k, cpu=round(c, 1), ram=r) for k, (c, r) in top]

_top = dict(t=0.0, list=[], busy=False)
def top_processes_cached():
    """For the dashboards: a full scan costs ~0.3 s, so it runs off the event loop, at most every 5 s."""
    if not _top["busy"] and time.time() - _top["t"] > 5:
        _top["busy"] = True
        def scan():
            try:
                _top["list"] = top_processes(12)  # the Processes screen lists more than get_status speaks
            finally:
                _top.update(t=time.time(), busy=False)
        threading.Thread(target=scan, daemon=True).start()
    return _top["list"]

def open_vscode(path):
    try:
        path = str(project(path)[1])
    except ValueError:
        pass
    if not Path(path).exists():
        return f"I can't find {path}.", ""
    code = shutil.which("code") or shutil.which("code.cmd")
    if not code:
        return "VS Code's code command isn't on the PATH.", ""
    subprocess.Popen([code, path], creationflags=NO_WINDOW)
    return f"Opening {Path(path).name} in VS Code.", path

def git(project_name, action, message=None):
    name, path = project(project_name)
    cmds = {"status": ["git", "status", "-sb"], "log": ["git", "log", "--oneline", "-5"], "diff": ["git", "diff", "--stat"],
            "pull": ["git", "pull", "--ff-only"], "push": ["git", "push"]}
    if action == "commit":
        if not message:
            return "What should the commit message be?", ""
        sh(["git", "add", "-A"], path)
        rc, out = sh(["git", "commit", "-m", message], path)
    elif action in cmds:
        rc, out = sh(cmds[action], path, timeout=120)
    else:
        return f"I can't do git {action}.", ""
    if rc != 0:
        return f"git {action} failed for {name}.", out
    lines = out.splitlines()
    if action == "status":
        changed = len(lines) - 1
        spoken = f"{name} is on {lines[0][3:].split('...')[0]}, " + (f"{changed} changed files." if changed else "clean.")
    elif action == "log":
        spoken = f"Latest in {name}: " + "; ".join(l.split(" ", 1)[1] for l in lines[:3]) + "."
    elif action == "diff":
        spoken = lines[-1] if lines else "No uncommitted changes."
    else:
        spoken = f"git {action} done for {name}."
    return spoken, out

def run_project(project_name, task):
    name, path = project(project_name)
    cmd = PROJECTS[name].get(task)
    if not cmd:
        return f"{name} has no {task} command configured.", ""
    if task == "dev":  # long-running: own console window
        subprocess.Popen(cmd, cwd=path, shell=True, creationflags=subprocess.CREATE_NEW_CONSOLE)
        return f"Started the {name} dev server.", cmd
    t = time.time()
    rc, out = sh(cmd, path, timeout=900)
    tail = "\n".join(out.splitlines()[-15:])
    return (f"{name} {task} {'passed' if rc == 0 else 'failed'} in {time.time() - t:.0f} seconds."), tail

def run_powershell(command):
    rc, out = sh(["powershell", "-NoProfile", "-Command", command], timeout=120)
    first = out.splitlines()[0][:120] if out else ""
    return (f"Done. {first}" if rc == 0 else f"That failed. {first}"), out

PROTECTED = {"system", "csrss.exe", "wininit.exe", "winlogon.exe", "lsass.exe", "services.exe", "smss.exe",
             "svchost.exe", "dwm.exe", "explorer.exe", "msmpeng.exe", "python.exe"}

def process_for(name, running, apps):
    """Spoken app name -> exe names to close: the exe itself, the exe behind its Start-menu shortcut,
    or running exes whose name contains it ("word" -> winword.exe). Returns a list: several means ambiguous."""
    n = APP_ALIASES.get(name.lower().strip(), name.lower().strip()).removesuffix(".exe")
    if n + ".exe" in running:
        return [n + ".exe"]
    app = find_app(n, apps)
    exe = Path(app["exe"]).name.lower() if app and app["exe"] else ""
    if exe in running:
        return [exe]
    if len(n) < 3:
        return []
    return sorted({p for p in running if n.replace(" ", "") in p.removesuffix(".exe")})

def kill_process(name):
    running = {(p.info["name"] or "").lower() for p in psutil.process_iter(["name"])}
    found = process_for(name, running, start_apps())
    if len(found) > 1:
        return f"{name} could be {', '.join(found)}. Which one?", ""
    n = found[0] if found else name.lower().strip().removesuffix(".exe") + ".exe"
    if n == "explorer.exe":  # the same process draws the taskbar and desktop, so close only its folder windows
        sh(["powershell", "-NoProfile", "-Command",
            "(New-Object -ComObject Shell.Application).Windows() | ForEach-Object { $_.Quit() }"], timeout=20)
        return "Closed the File Explorer windows.", ""
    if n in PROTECTED:
        return f"{n} is protected, so I won't kill it.", ""
    procs = [p for p in psutil.process_iter(["name"]) if (p.info["name"] or "").lower() == n]
    for p in procs:
        try:
            p.kill()
        except psutil.Error:
            pass
    return (f"Closed {len(procs)} {n} process{'es' if len(procs) != 1 else ''}." if procs else f"No {n} is running."), ""

MEDIA_KEYS = {"play": 0xB3, "pause": 0xB3, "next": 0xB0, "previous": 0xB1}  # play and pause share the toggle key

def media_control(action, device="laptop"):
    vk = MEDIA_KEYS.get(action)
    if vk is None:
        return f"I can't do {action} on the music.", ""
    for flags in (0, 2):  # key down, key up
        ctypes.windll.user32.keybd_event(vk, 0, flags, 0)
    return {"next": "Next track.", "previous": "Previous track."}.get(action, "Okay."), ""

class _GUID(ctypes.Structure):
    _fields_ = [("a", ctypes.c_ulong), ("b", ctypes.c_ushort), ("c", ctypes.c_ushort), ("d", ctypes.c_ubyte * 8)]

def _guid(s):
    g = _GUID(); ctypes.windll.ole32.CLSIDFromString(s, ctypes.byref(g)); return g

def _call(obj, slot, *args):
    """Call COM method number `slot` of interface pointer `obj` (raises OSError on a failed HRESULT)."""
    vtbl = ctypes.cast(obj, ctypes.POINTER(ctypes.POINTER(ctypes.c_void_p)))[0]
    types = (type(a) if hasattr(type(a), "from_param") else ctypes.c_void_p for a in args)  # byref() args are pointers
    return ctypes.WINFUNCTYPE(ctypes.HRESULT, ctypes.c_void_p, *types)(vtbl[slot])(obj, *args)

def laptop_volume(step):
    """Move the laptop's master volume by `step` x 5 % (0 just reads it), unmuting; returns the level 0-100.
    Plain ctypes over Windows Core Audio (IMMDeviceEnumerator -> IMMDevice -> IAudioEndpointVolume): no extra dependency."""
    ole, P = ctypes.windll.ole32, ctypes.c_void_p
    ole.CoInitialize(None)  # per thread; "already initialised" is fine
    enum, dev, vol = P(), P(), P()
    try:
        hr = ole.CoCreateInstance(ctypes.byref(_guid("{BCDE0395-E52F-467C-8E3D-C4579291692E}")), None, 1,
                                  ctypes.byref(_guid("{A95664D2-9614-4F35-A746-DE8DB63617E6}")), ctypes.byref(enum))
        if hr < 0:
            raise OSError(hr, "MMDeviceEnumerator")
        _call(enum, 4, ctypes.c_int(0), ctypes.c_int(1), ctypes.byref(dev))  # default render endpoint, multimedia role
        _call(dev, 3, ctypes.byref(_guid("{5CDF2C82-841E-4546-9722-0CF74078229A}")), ctypes.c_ulong(23), P(), ctypes.byref(vol))
        cur = ctypes.c_float(); _call(vol, 9, ctypes.byref(cur))  # GetMasterVolumeLevelScalar
        if not step:
            return round(cur.value * 100)
        new = min(20, max(0, round(cur.value * 20) + step)) / 20
        _call(vol, 7, ctypes.c_float(new), P())  # SetMasterVolumeLevelScalar
        _call(vol, 14, ctypes.c_int(0), P())     # SetMute(False)
        return round(new * 100)
    finally:
        for p in (vol, dev, enum):
            if p:
                ctypes.WINFUNCTYPE(ctypes.c_ulong, P)(ctypes.cast(p, ctypes.POINTER(ctypes.POINTER(P)))[0][2])(p)  # Release

_spotify = {"token": "", "until": 0.0}

def _fetch(url, data=None, headers=None):
    with urllib.request.urlopen(urllib.request.Request(url, data=data, headers=headers or {}), timeout=8) as r:
        return r.read()

def spotify_track(query):
    """Top Spotify track for a query, or None if no developer keys are set up (data/spotify.json)."""
    found = spotify_search(query, 1)
    return found[0] if found else None

def spotify_search(query, limit):
    """Spotify tracks for a query, or None if no developer keys are set up (data/spotify.json)."""
    keys = DATA / "spotify.json"
    if not keys.exists():
        return None
    if _spotify["until"] < time.time():
        k = json.loads(keys.read_text(encoding="utf-8-sig"))
        basic = base64.b64encode(f"{k['client_id']}:{k['client_secret']}".encode()).decode()
        t = json.loads(_fetch("https://accounts.spotify.com/api/token", b"grant_type=client_credentials",
                              {"Authorization": "Basic " + basic, "Content-Type": "application/x-www-form-urlencoded"}))
        _spotify.update(token=t["access_token"], until=time.time() + t["expires_in"] - 60)
    found = json.loads(_fetch(f"https://api.spotify.com/v1/search?type=track&limit={limit}&q=" + urllib.parse.quote(query),
                              headers={"Authorization": "Bearer " + _spotify["token"]}))
    return found["tracks"]["items"]

def _art(images, i):
    """Cover as a data: URI (the phone on the offline hotspot can't reach Spotify's image server); images go largest first."""
    try:
        return "data:image/jpeg;base64," + base64.b64encode(_fetch(images[min(i, len(images) - 1)]["url"])).decode() if images else None
    except OSError:
        return None

def search_music(query):
    """Up to 8 Spotify tracks, each with a play button in the chat (the button sends {"type":"play_track","uri"})."""
    try:
        found = spotify_search(re.sub(r"\s+by\s+", " ", query.strip()), 8)
    except (OSError, ValueError, KeyError) as e:
        found, why = None, f"Spotify lookup failed ({e!r}); opened Spotify's search instead."
    else:
        why = "Add Spotify keys to data/spotify.json (scripts/setup-spotify.ps1) to list songs here."
    if found is None:
        os.startfile(f"spotify:search:{urllib.parse.quote(query.strip())}")
        return f"Searching Spotify for {query}.", why
    if not found:
        return f"I found no songs for {query} on Spotify.", ""
    with ThreadPoolExecutor(8) as ex:  # smallest cover (~64 px) for each row, fetched side by side
        arts = list(ex.map(lambda t: _art(t["album"]["images"], 9), found))
    tracks = [dict(title=t["name"], artist=", ".join(a["name"] for a in t["artists"]), album=t["album"]["name"], art=art, uri=t["uri"])
              for t, art in zip(found, arts)]
    return f"Here's what I found for {query}.", "", {"tracks": tracks}

def play_music(query, device="laptop"):
    q = urllib.parse.quote(query.strip())
    if any(a["name"].lower() == "spotify" for a in start_apps()):
        try:
            track = spotify_track(re.sub(r"\s+by\s+", " ", query.strip()))
            why = "Add Spotify keys to data/spotify.json to play songs directly."
        except (OSError, ValueError, KeyError) as e:  # offline, Spotify down, or a broken keys file: search instead
            track, why = None, f"Spotify lookup failed ({e!r}); searched instead."
        if not track:
            os.startfile(f"spotify:search:{q}")
            return f"Searching Spotify for {query}.", why
        os.startfile(track["uri"])  # the desktop app starts playing a track link it is given
        artist = ", ".join(a["name"] for a in track["artists"])
        art = _art(track["album"]["images"], 1)  # ~300 px is enough for the card
        media = dict(title=track["name"], artist=artist, album=track["album"]["name"], art=art, app="Spotify")
        return f"Playing {track['name']} by {artist}.", "", media
    os.startfile(f"https://music.youtube.com/search?q={q}")
    return f"Opening YouTube Music for {query}.", ""

_np = {"key": None, "art": None}

async def now_playing():
    """What the laptop is playing right now (any app, via Windows media sessions), as a media dict plus "playing";
    None when nothing is. Needs the winrt-Windows.Media.Control packages."""
    try:
        from winrt.windows.media.control import GlobalSystemMediaTransportControlsSessionManager as Manager
        from winrt.windows.storage.streams import Buffer, InputStreamOptions
    except ImportError:
        return None
    s = (await Manager.request_async()).get_current_session()
    if not s:
        return None
    p = await s.try_get_media_properties_async()
    if not p.title:
        return None
    app = s.source_app_user_model_id.rsplit("\\", 1)[-1].split("!")[0].removesuffix(".exe")
    app = "Spotify" if "spotify" in app.lower() else app
    key = (p.title, p.artist, app)
    if key != _np["key"]:  # cover art only when the track changes
        art = None
        if p.thumbnail:
            try:
                stream = await p.thumbnail.open_read_async()
                buf = Buffer(stream.size)
                await stream.read_async(buf, buf.capacity, InputStreamOptions.READ_AHEAD)
                art = f"data:{stream.content_type or 'image/png'};base64," + base64.b64encode(bytes(buf)).decode()
            except OSError:
                pass
        _np.update(key=key, art=art)
    info = s.get_playback_info()
    playing = info.playback_status == 4  # GlobalSystemMediaTransportControlsSessionPlaybackStatus.PLAYING
    tl = s.get_timeline_properties()
    length = (tl.end_time - tl.start_time).total_seconds()
    position = tl.position.total_seconds()
    if playing:  # apps report the position only now and then; add the time since that report
        seen = tl.last_updated_time
        position += (datetime.now(timezone.utc) - (seen if seen.tzinfo else seen.replace(tzinfo=timezone.utc))).total_seconds()
    shuffle = info.is_shuffle_active if info.controls.is_shuffle_enabled else None  # None: the app can't shuffle
    return dict(title=p.title, artist=p.artist, album=p.album_title, art=_np["art"], app=app, playing=playing,
                position=round(min(max(position, 0), length), 1) if length > 0 else None,
                length=round(length, 1) if length > 0 else None, shuffle=shuffle)

async def media_session(action):
    """Desk panel buttons: play/pause, next, previous or shuffle on the laptop's current media session (no reply)."""
    from winrt.windows.media.control import GlobalSystemMediaTransportControlsSessionManager as Manager
    s = (await Manager.request_async()).get_current_session()
    if not s:
        return
    if action == "shuffle":
        await s.try_change_shuffle_active_async(not s.get_playback_info().is_shuffle_active)
    else:
        await {"play": s.try_play_async, "pause": s.try_pause_async, "next": s.try_skip_next_async,
               "previous": s.try_skip_previous_async}[action]()

ES_CONTINUOUS, ES_SYSTEM_REQUIRED = 0x80000000, 0x00000001
_awake = {"stop": None}

def _hold_awake(stop, process=None, idle_cpu=10, idle_minutes=5):
    """Block automatic sleep until cancelled, the selected process exits, or generic work goes idle."""
    ctypes.windll.kernel32.SetThreadExecutionState(ES_CONTINUOUS | ES_SYSTEM_REQUIRED)
    quiet = 0
    try:
        while not stop.wait(3 if process else 30):
            if process:
                if not process.is_running():
                    break
            else:
                quiet = quiet + 1 if psutil.cpu_percent(1) < idle_cpu else 0
                if quiet >= idle_minutes * 2:
                    break
    finally:
        ctypes.windll.kernel32.SetThreadExecutionState(ES_CONTINUOUS)  # sleep allowed again
        if _awake["stop"] is stop:
            _awake["stop"] = None

def power(action, minutes=0, process=""):
    if action == "keep_awake":
        if _awake["stop"]:
            return "The laptop is already staying awake. Say 'let the laptop sleep' before choosing another process.", ""
        target = None
        if process:
            if not isinstance(process, str) or not SAFE_NAME.fullmatch(process):
                return "Give me a running process name or PID.", ""
            if process.isdecimal():
                try:
                    target = psutil.Process(int(process))
                    target.name()
                except (psutil.Error, ValueError, OverflowError):
                    return f"I can't monitor process {process}. Check that it is running and accessible.", ""
            else:
                procs = list(psutil.process_iter(["name"]))
                running = {(p.info["name"] or "").lower() for p in procs}
                found = process_for(process, running, []) or process_for(process, running, start_apps())
                if len(found) > 1:
                    return f"{process} could be {', '.join(found)}. Give me the exact process name or PID.", ""
                matches = [p for p in procs if (p.info["name"] or "").lower() in found]
                if not matches:
                    return f"No {process} process is running.", ""
                if len(matches) > 1:
                    return f"Several {found[0]} processes are running. Give me a PID: {', '.join(str(p.pid) for p in matches)}.", ""
                target = matches[0]
        _awake["stop"] = threading.Event()
        threading.Thread(target=_hold_awake, args=(_awake["stop"], target), daemon=True).start()
        if target:
            return f"The laptop will stay awake until process {target.pid} exits.", f"Watching {process}; sleep allowed again within 3 seconds of exit."
        # shortcut: "work finished" = CPU under 10% for 5 minutes; closing the lid still sleeps (Windows lid setting)
        return "Okay, the laptop stays awake until the CPU has been quiet for five minutes.", "Name a process to track its actual exit instead."
    if action == "sleep":
        # 2 s head start so the reply reaches the phone before the network drops
        threading.Timer(2, ctypes.windll.powrprof.SetSuspendState, (False, False, False)).start()
        return "Putting the laptop to sleep.", ""
    if action == "allow_sleep":
        if _awake["stop"]:
            _awake["stop"].set()
        return "Okay, the laptop can sleep again.", ""
    if action == "shutdown":
        minutes = max(0, int(minutes or 0))
        rc, out = sh(["shutdown", "/s", "/t", str(minutes * 60)])
        if rc != 0:
            return "I couldn't schedule the shutdown.", out
        return (f"Shutting down in {minutes} minute{'s' if minutes != 1 else ''}." if minutes else "Shutting down now."), out
    if action == "cancel_shutdown":
        rc, out = sh(["shutdown", "/a"])
        return ("Shutdown cancelled." if rc == 0 else "There was no shutdown scheduled."), out
    return f"I can't do {action}.", ""

def describe_screen(device="laptop"):
    # shortcut: screen understanding is Phase 7 (Windows OCR + UIA, phone accessibility tree); upgrade there
    return "I can't see screens yet. That arrives with the vision update.", ""

# set_timer / remember / recall / tasks need the running Brain, so it binds them (see brain.py).

S, I = {"type": "string"}, {"type": "integer"}
DEV = {"type": "string", "enum": ["phone", "laptop"]}
GIT_TIER = {"status": 0, "log": 0, "diff": 0, "pull": 1, "commit": 2, "push": 3}

TOOLS = {t.name: t for t in [
    Tool("open_app", "Open an app on the phone or laptop", {"name": S, "device": DEV}, ["name", "device"], 1,
         open_app, phone_if=lambda a: a.get("device") != "laptop"),
    Tool("get_status", "Read a system metric",
         {"metric": {"type": "string", "enum": ["battery", "ram", "cpu", "storage", "temperature", "processes"]}, "device": DEV},
         ["metric", "device"], 0, get_status, phone_if=lambda a: a.get("device") == "phone"),
    Tool("set_brightness", "Set phone screen brightness percent", {"level": I}, ["level"], 1),
    Tool("set_volume", "Set phone media volume percent", {"level": I}, ["level"], 1),
    Tool("open_vscode", "Open a folder in VS Code on the laptop", {"path": S}, ["path"], 1, open_vscode),
    Tool("git", "Run a read-only or commit git action in a project",
         {"project": S, "action": {"type": "string", "enum": list(GIT_TIER)}, "message": S}, ["project", "action"],
         lambda a: GIT_TIER.get(a.get("action"), 3), lambda project, action, message=None: git(project, action, message)),
    Tool("run_project", "Run a project's configured task",
         {"project": S, "task": {"type": "string", "enum": ["dev", "test", "build"]}}, ["project", "task"], 1,
         lambda project, task: run_project(project, task)),
    Tool("run_powershell", "Run an arbitrary PowerShell command on the laptop (requires user confirmation)",
         {"command": S}, ["command"], 3, run_powershell),
    Tool("set_timer", "Start a countdown timer", {"minutes": I, "label": S}, ["minutes"], 0),
    Tool("remember", "Store a fact about the user for later", {"fact": S}, ["fact"], 1),
    Tool("recall", "Search FROSTI's memory", {"query": S}, ["query"], 0),
    Tool("tasks", "Task planner: add, list or finish a task",
         {"action": {"type": "string", "enum": ["add", "list", "done"]}, "text": S}, ["action"], lambda a: 0 if a.get("action") == "list" else 1),
    Tool("kill_process", "Close or terminate a laptop program by app or process name", {"name": S}, ["name"], 2, kill_process),
    Tool("play_music", "Play a song, artist or playlist", {"query": S, "device": DEV}, ["query", "device"], 1,
         play_music, phone_if=lambda a: a.get("device") != "laptop"),
    Tool("search_music", "Search Spotify and list songs to pick from", {"query": S}, ["query"], 0, search_music),
    Tool("media_control", "Pause, resume or skip the music that is playing",
         {"action": {"type": "string", "enum": list(MEDIA_KEYS)}, "device": DEV}, ["action", "device"], 1,
         media_control, phone_if=lambda a: a.get("device") != "laptop"),
    Tool("power", "Laptop power: keep awake until a named process or PID exits (process), or until CPU idle if unspecified; allow sleep, sleep now, shut down after N minutes, or cancel shutdown",
         {"action": {"type": "string", "enum": ["keep_awake", "allow_sleep", "sleep", "shutdown", "cancel_shutdown"]}, "minutes": I, "process": S},
         ["action"], lambda a: 2 if a.get("action") == "shutdown" else 1, power),
    Tool("describe_screen","Look at the current screen and describe it", {"device": DEV}, ["device"], 0, describe_screen),
]}
