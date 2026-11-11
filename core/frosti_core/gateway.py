"""WebSocket gateway for the phone Shell.

Protocol (JSON text frames; binary frames = audio):
 phone -> core: {"type":"hello","token"} first; {"type":"text","text"}; {"type":"ptt","on":bool};
                {"type":"listen"} (tap orb); {"type":"played"} (finished playing our audio);
                {"type":"result","id","text"} (phone tool done); {"type":"confirm","id","ok"};
                binary: 16 kHz mono int16 PCM mic stream
 core -> phone: {"type":"state","state":idle|listening|thinking|speaking}; {"type":"heard","text"};
                {"type":"reply","text","detail","path","seconds"}; {"type":"tool","id","name","args"};
                {"type":"activity","name","args"} (a tool is starting: the character acts it out); reply also carries "tool";
                {"type":"confirm","id","summary","tier"}; {"type":"notify","text"};
                binary: WAV audio, one per sentence
 dashboards:    {"type":"stats","phone":{...}} -> {"type":"stats",...}; {"type":"memory"} -> {"type":"memory",...};
                {"type":"log"} -> {"type":"log","entries":[...]} (latest 300 audit rows);
                {"type":"forget","id"} -> deletes one fact, then {"type":"memory",...}
 laptop console: GET / serves console.html (loopback only); it says {"type":"hello","token","client":"desk"},
                sees the phone's state/heard/reply/notify mirrored with "src":"phone", and its phone tools go to the phone
"""
import asyncio, hmac, itertools, json, secrets, ssl, time
from http import HTTPStatus
from pathlib import Path
import psutil
import websockets
from . import CONFIG, DATA
from .brain import Brain, Session, sentences
from .tools import PROJECTS
from .voice import Listener

STARTED = time.time()
MIRROR = {"state", "heard", "reply", "notify", "activity"}

def token():
    f = DATA / "token.txt"
    if not f.exists():
        DATA.mkdir(exist_ok=True)
        f.write_text(secrets.token_urlsafe(24))
    return f.read_text().strip()

class PhoneSession(Session):
    name = "phone"
    ids = itertools.count(1)

    def __init__(self, ws, brain, voice):
        self.ws, self.brain, self.voice = ws, brain, voice
        self.pending = {}
        self.listener = Listener(voice) if voice else None
        self.followup = False
        self.local_wake = False

    async def send(self, **msg):
        await self.ws.send(json.dumps(msg))
        if self.name == "phone" and msg.get("type") in MIRROR:  # the laptop console shows the phone's conversation
            for d in [s for s in self.brain.sessions if s.name == "desk"]:
                try:
                    await d.ws.send(json.dumps({**msg, "src": "phone"}))
                except websockets.ConnectionClosed:
                    pass

    async def request(self, timeout, **msg):
        i = next(self.ids); fut = asyncio.get_running_loop().create_future()
        self.pending[i] = fut
        await self.send(id=i, **msg)
        try:
            return await asyncio.wait_for(fut, timeout)
        finally:
            self.pending.pop(i, None)

    async def confirm(self, summary, tier):
        try:
            return bool(await self.request(30, type="confirm", summary=summary, tier=tier))
        except asyncio.TimeoutError:
            return False

    async def phone(self, tool, args):
        try:
            return await self.request(10, type="tool", name=tool, args=args)
        except asyncio.TimeoutError:
            return "The phone didn't answer in time."

    async def activity(self, tool, args):
        await self.send(type="activity", name=tool, args=args)

    async def notify(self, text):
        await self.send(type="notify", text=text)
        await self.say(text)

    async def say(self, text):
        if not self.voice:
            return
        await self.send(type="state", state="speaking")
        for s in sentences(text):
            audio = self.voice.wake_audio if s == "Yes?" else await asyncio.to_thread(self.voice.speak, s)
            await self.ws.send(audio)
        await self.send(type="audio_end")

    async def handle_text(self, text):
        await self.send(type="state", state="thinking")
        r = await self.brain.handle(text, self)
        await self.send(type="reply", text=r["reply"], detail=r["detail"], tool=r["tool"], path=r["path"], seconds=round(r["seconds"], 2))
        if self.voice:
            await self.say(r["reply"])  # phone sends "played" when done -> follow-up window
        else:
            await self.send(type="state", state="idle")

    async def on_audio_event(self, ev):
        kind, audio = ev
        if kind == "wake":
            self.listener.mode = "busy"  # don't capture our own "Yes?"; the phone's "played" opens the mic
            await self.say("Yes?")
        elif kind == "timeout":
            self.listener.idle()
            await self.send(type="state", state="idle")
        elif kind == "utterance":
            await self.send(type="state", state="thinking")
            text = await asyncio.to_thread(self.voice.transcribe, audio)
            await self.send(type="heard", text=text)
            if text:
                await self.handle_text(text)
            else:
                self.listener.idle()
                await self.send(type="state", state="idle")

    async def run(self):
        tasks = set()
        def spawn(coro):  # long work runs beside the receive loop so audio/confirm frames keep flowing
            t = asyncio.create_task(coro); tasks.add(t); t.add_done_callback(tasks.discard)
        async for frame in self.ws:
            if isinstance(frame, bytes):
                if self.listener and (ev := self.listener.feed(frame)):
                    spawn(self.on_audio_event(ev))
                continue
            m = json.loads(frame)
            t = m.get("type")
            if t in ("result", "confirm") and (fut := self.pending.get(m.get("id"))) and not fut.done():
                fut.set_result(m.get("text") if t == "result" else m.get("ok"))
            elif t == "stats":
                if isinstance(m.get("phone"), dict):
                    self.brain.phone_stats = m["phone"]
                await self.send(type="stats", **stats(self.brain))
            elif t == "memory":
                await self.send(type="memory", **memory(self.brain))
            elif t == "log":  # console Activity screen: the audit log
                await self.send(type="log", entries=self.brain.store.recent(300))
            elif t == "forget":
                n = self.brain.store.forget(m.get("id", 0))
                self.brain.store.audit(self.name, "forget", {"id": m.get("id")}, 1, True, f"{n} fact deleted")
                await self.send(type="memory", **memory(self.brain))
            elif t == "text":
                spawn(self.handle_text(m["text"]))
            elif t == "new":  # console "New chat": drop the follow-up context
                self.brain.history = []
            elif t == "ptt" and self.listener:
                ev = self.listener.ptt(m.get("on"))
                await self.send(type="state", state="listening" if m.get("on") else "thinking")
                if ev:
                    spawn(self.on_audio_event(ev))
            elif t == "listen" and self.listener:
                self.listener.listen()
                await self.send(type="state", state="listening")
            elif t == "wake" and self.local_wake and self.listener:
                spawn(self.on_audio_event(("wake", None)))
            elif t == "played" and self.listener:
                self.listener.listen(6.0)  # continuous conversation: follow-up without the wake word
                await self.send(type="state", state="listening")

class DeskSession(PhoneSession):
    """The laptop console in a browser: typed commands, dashboards, confirmations. Speech stays on the phone."""
    name = "desk"

    def __init__(self, ws, brain, voice):
        super().__init__(ws, brain, None)

    async def phone(self, tool, args):
        phones = [s for s in self.brain.sessions if s.name == "phone"]
        return await phones[0].phone(tool, args) if phones else "The phone isn't connected."

def stats(brain):
    m, b, n = psutil.virtual_memory(), psutil.sensors_battery(), psutil.net_io_counters()
    return dict(cpu=psutil.cpu_percent(None), cores=psutil.cpu_count(), ram=m.percent, ram_used=m.used, ram_total=m.total,
                battery=round(b.percent) if b else None, plugged=bool(b and b.power_plugged),
                net_sent=n.bytes_sent, net_recv=n.bytes_recv,
                model=Path(CONFIG["llm"]["model"]).stem, llm=brain.llm.ready, voice=getattr(brain, "voice_loaded", False),
                voice_name=CONFIG["speech"]["voice"], phone=any(s.name == "phone" for s in brain.sessions),
                phone_stats=getattr(brain, "phone_stats", None), uptime=round(time.time() - STARTED),
                gateway=f"{CONFIG['gateway']['host']}:{CONFIG['gateway']['port']}")

def memory(brain):
    return dict(facts=brain.store.facts(), recent=brain.store.recent(), history=brain.history,
                projects=[dict(name=k, path=v["path"]) for k, v in PROJECTS.items()], timers=brain.active_timers())

LOOPBACK = {"127.0.0.1", "::1"}
# FROSTI's animation engine, its data and the preview gallery (same loopback rule as the console)
ANIM_FILES = {"/gallery": ("gallery.html", "text/html; charset=utf-8"),
              "/frosti.js": ("frosti.js", "text/javascript; charset=utf-8"),
              "/frosti_anims.json": ("frosti_anims.json", "application/json")}

def console_page(connection, request):
    """GET / -> the laptop console; /gallery and its files -> the animation gallery.
    Loopback only, and the Host must be loopback too (DNS rebinding)."""
    if request.headers.get("Upgrade", "").lower() == "websocket":
        return None
    host = request.headers.get("Host", "").rsplit(":", 1)[0].strip("[]")
    if connection.remote_address[0] not in LOOPBACK or host not in ("127.0.0.1", "localhost", "::1"):
        return connection.respond(HTTPStatus.FORBIDDEN, "FROSTI console is available on this laptop only.\n")
    path = request.path.split("?")[0]
    if path in ANIM_FILES:
        name, ctype = ANIM_FILES[path]
        body = (Path(__file__).parent / "anim" / name).read_text(encoding="utf-8")
    elif path == "/":
        ctype = "text/html; charset=utf-8"
        body = Path(__file__).with_name("console.html").read_text(encoding="utf-8").replace("__FROSTI_TOKEN__", token())
    else:
        return connection.respond(HTTPStatus.NOT_FOUND, "Not found\n")
    r = connection.respond(HTTPStatus.OK, body)
    del r.headers["Content-Type"]
    r.headers["Content-Type"] = ctype
    r.headers["Cache-Control"] = "no-store"
    return r

async def serve(brain, voice):
    tok = token()
    brain.voice_loaded = voice is not None

    async def handler(ws):
        try:
            hello = json.loads(await asyncio.wait_for(ws.recv(), 10))
        except (asyncio.TimeoutError, ValueError, websockets.ConnectionClosed):
            return
        if hello.get("type") != "hello" or not hmac.compare_digest(str(hello.get("token", "")), tok):
            await ws.close(4001, "bad token")
            return
        s = (DeskSession if hello.get("client") == "desk" else PhoneSession)(ws, brain, voice)
        s.local_wake = hello.get("local_wake") is True and s.name == "phone"
        brain.sessions.add(s)
        print(f"[gateway] {s.name} connected ({ws.remote_address[0]})", flush=True)
        try:
            await s.send(type="ready", local_wake=s.local_wake)
            await s.send(type="state", state="idle")
            await s.run()
        except websockets.ConnectionClosed:
            pass
        finally:
            brain.sessions.discard(s)
            print(f"[gateway] {s.name} disconnected", flush=True)

    g = CONFIG["gateway"]
    wireless = DATA / "wireless.json"
    if wireless.exists():
        settings = json.loads(wireless.read_text(encoding="utf-8-sig"))
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.minimum_version = ssl.TLSVersion.TLSv1_2
        context.load_cert_chain(DATA / "wireless.pem", DATA / "wireless.key")
        try:
            brain.wireless_server = await websockets.serve(handler, settings["host"], 8766, ssl=context,
                                                          max_size=8 * 2**20, process_request=console_page)
        except OSError as e:  # e.g. the hotspot is off, so its address is gone; USB still works
            print(f"[gateway] wireless link off: can't listen on {settings['host']}:8766 ({e})", flush=True)
    return await websockets.serve(handler, g["host"], g["port"], max_size=8 * 2**20, process_request=console_page)
