"""python -m frosti_core [--no-voice]

Starts the phone gateway, loads speech, and gives you a text console on stdin. Tier 2+ actions typed here are confirmed here.
"""
import argparse, asyncio, sys, time
from .brain import Brain, Session
from .gateway import serve

class Console(Session):
    name = "cli"
    def __init__(self, brain):
        self.brain = brain
    async def confirm(self, summary, tier):
        ans = await asyncio.to_thread(input, f"  CONFIRM (tier {tier}): {summary}  [y/N] ")
        return ans.strip().lower() in ("y", "yes")
    async def phone(self, tool, args):  # route phone tools to the connected Shell, if any
        phones = [s for s in self.brain.sessions if s.name == "phone"]
        return await phones[0].phone(tool, args) if phones else "The phone isn't connected."

async def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--no-voice", action="store_true")
    a = ap.parse_args()

    brain = Brain()
    voice = None
    if not a.no_voice:
        from .voice import Voice
        t = time.time(); voice = Voice(); print(f"[voice] loaded in {time.time() - t:.1f}s", flush=True)
    server = await serve(brain, voice)
    print(f"[gateway] listening on port {server.sockets[0].getsockname()[1]} (token in data/token.txt)", flush=True)
    print(f"[console] open http://127.0.0.1:{server.sockets[0].getsockname()[1]}/ on this laptop", flush=True)

    con = Console(brain); brain.sessions.add(con)
    print("Type a command (Ctrl+C to quit).", flush=True)
    while True:
        line = await asyncio.to_thread(sys.stdin.readline)
        if not line:
            await asyncio.Event().wait()  # stdin closed (running as a service): keep serving
        if line.strip():
            r = await brain.handle(line.strip(), con)
            print(f"FROSTI [{r['path']} {r['seconds']:.1f}s]: {r['reply']}")
            if r["detail"]:
                print("  " + r["detail"].replace("\n", "\n  "))

if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        pass
