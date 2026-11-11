"""WebSocket gateway for the phone Shell.

Protocol (JSON text frames; binary frames = audio):
 phone -> core: {"type":"hello","token"} first; {"type":"text","text"}; {"type":"ptt","on":bool};
                {"type":"listen"} (tap orb); {"type":"played"} (finished playing our audio);
                {"type":"result","id","text"} (phone tool done); {"type":"confirm","id","ok"};
                binary: 16 kHz mono int16 PCM mic stream
 core -> phone: {"type":"state","state":idle|listening|thinking|speaking}; {"type":"heard","text"};
                {"type":"reply","text","detail","path","seconds"}; {"type":"tool","id","name","args"};
                {"type":"confirm","id","summary","tier"}; {"type":"notify","text"};
                binary: WAV audio, one per sentence
"""
import asyncio, hmac, itertools, json, secrets
import websockets
from . import CONFIG, DATA
from .brain import Brain, Session, sentences
from .voice import Listener

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

    async def send(self, **msg):
        await self.ws.send(json.dumps(msg))

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

    async def notify(self, text):
        await self.send(type="notify", text=text)
        await self.say(text)

    async def say(self, text):
        if not self.voice:
            return
        await self.send(type="state", state="speaking")
        for s in sentences(text):
            await self.ws.send(await asyncio.to_thread(self.voice.speak, s))

    async def handle_text(self, text):
        await self.send(type="state", state="thinking")
        r = await self.brain.handle(text, self)
        await self.send(type="reply", text=r["reply"], detail=r["detail"], path=r["path"], seconds=round(r["seconds"], 2))
        if self.voice:
            await self.say(r["reply"])  # phone sends "played" when done -> follow-up window
        else:
            await self.send(type="state", state="idle")

    async def on_audio_event(self, ev):
        kind, audio = ev
        if kind == "wake":
            await self.send(type="state", state="listening")
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
            elif t == "text":
                spawn(self.handle_text(m["text"]))
            elif t == "ptt" and self.listener:
                ev = self.listener.ptt(m.get("on"))
                await self.send(type="state", state="listening" if m.get("on") else "thinking")
                if ev:
                    spawn(self.on_audio_event(ev))
            elif t == "listen" and self.listener:
                self.listener.listen()
                await self.send(type="state", state="listening")
            elif t == "played" and self.listener:
                self.listener.listen(6.0)  # continuous conversation: follow-up without the wake word
                await self.send(type="state", state="listening")

async def serve(brain, voice):
    tok = token()

    async def handler(ws):
        try:
            hello = json.loads(await asyncio.wait_for(ws.recv(), 10))
        except (asyncio.TimeoutError, ValueError, websockets.ConnectionClosed):
            return
        if hello.get("type") != "hello" or not hmac.compare_digest(str(hello.get("token", "")), tok):
            await ws.close(4001, "bad token")
            return
        s = PhoneSession(ws, brain, voice)
        brain.sessions.add(s)
        print(f"[gateway] phone connected ({ws.remote_address[0]})", flush=True)
        try:
            await s.send(type="state", state="idle")
            await s.run()
        except websockets.ConnectionClosed:
            pass
        finally:
            brain.sessions.discard(s)
            print("[gateway] phone disconnected", flush=True)

    g = CONFIG["gateway"]
    return await websockets.serve(handler, g["host"], g["port"], max_size=8 * 2**20)
