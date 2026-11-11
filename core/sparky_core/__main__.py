"""python -m sparky_core [--no-voice] [--no-llm]

Starts the phone gateway, loads speech, starts llama-server (prefix warm-up in the background),
and gives you a text console on stdin. Tier 2+ actions typed here are confirmed here.
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
    ap.add_argument("--no-llm", action="store_true", help="fast path only (instant start)")
    a = ap.parse_args()

    brain = Brain()
    voice = None
    if not a.no_voice:
        from .voice import Voice
        t = time.time(); voice = Voice(); print(f"[voice] loaded in {time.time() - t:.1f}s", flush=True)
    server = await serve(brain, voice)
    print(f"[gateway] listening on port {server.sockets[0].getsockname()[1]} (token in data/token.txt)", flush=True)
    if not a.no_llm:
        async def warm():
            t = await asyncio.to_thread(brain.llm.start)
            print(f"[llm] ready, prefix warmed in {t:.0f}s", flush=True)
        asyncio.create_task(warm())

    con = Console(brain); brain.sessions.add(con)
    print("Type a command (Ctrl+C to quit).", flush=True)
    try:
        while True:
            line = await asyncio.to_thread(sys.stdin.readline)
            if not line:
                await asyncio.Event().wait()  # stdin closed (running as a service): keep serving
            if line.strip():
                r = await brain.handle(line.strip(), con)
                print(f"SPARKY [{r['path']} {r['seconds']:.1f}s]: {r['reply']}")
                if r["detail"]:
                    print("  " + r["detail"].replace("\n", "\n  "))
    finally:
        brain.llm.stop()

if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        pass
