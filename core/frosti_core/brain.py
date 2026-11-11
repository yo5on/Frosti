"""Brain: text in -> fast path or LLM -> policy (risk tiers, confirmation) -> tool -> spoken reply."""
import asyncio, json, re, time
from . import router
from .llm import LLM
from .store import Store
from .tools import TOOLS

class Session:
    """What a front-end (phone, CLI) gives the brain. Override confirm/phone/notify."""
    name = "cli"
    async def confirm(self, summary, tier):   # tier 2+: needs an explicit yes (tap on the phone)
        return False
    async def phone(self, tool, args):        # execute a phone tool, return spoken text
        return "The phone isn't connected."
    async def notify(self, text):             # unprompted announcement (timers)
        print(f"FROSTI: {text}")
    async def activity(self, tool, args):     # a tool is starting (the character acts it out)
        pass

def describe(tool, args):
    if tool == "run_powershell":
        return f"Run PowerShell: {args.get('command')}"
    if tool == "git":
        return f"git {args.get('action')} in {args.get('project')}" + (f": \"{args['message']}\"" if args.get("message") else "")
    if tool == "kill_process":
        return f"Kill process {args.get('name')}"
    return f"{tool} {json.dumps(args)}"

class Brain:
    def __init__(self, store=None, llm=None):
        self.store = store or Store()
        self.llm = llm or LLM([t.schema() for t in TOOLS.values()])
        self.history = []        # last few turns, plain text, for follow-ups ("what should I remind you about?")
        self.sessions = set()    # connected front-ends, for notifications
        self.timers = []         # [{"due", "label"}] for the Memory screen; lost on restart like the timers themselves
        TOOLS["remember"].run = lambda fact: (self.store.remember(fact) or "Noted.", fact)
        TOOLS["recall"].run = self._recall
        TOOLS["set_timer"].run = self._timer

    def _recall(self, query):
        hits = self.store.recall(query)
        return ("You told me: " + "; ".join(hits) + ".") if hits else "I don't have anything about that.", "\n".join(hits)

    def _timer(self, minutes, label=""):
        loop = self.loop
        msg = f"Timer done{': ' + label if label else ''}."
        self.timers.append(dict(due=time.time() + minutes * 60, label=label))
        loop.call_soon_threadsafe(lambda: loop.call_later(minutes * 60, lambda: asyncio.ensure_future(self.broadcast(msg))))
        return f"Timer set for {minutes} minute{'s' if minutes != 1 else ''}{' for ' + label if label else ''}.", ""

    def active_timers(self):
        self.timers = [t for t in self.timers if t["due"] > time.time()]
        return self.timers

    async def broadcast(self, text):
        for s in list(self.sessions):
            await s.notify(text)

    async def handle(self, text, session):
        """Returns dict(reply, detail, tool, args, path, seconds)."""
        self.loop = asyncio.get_running_loop()
        t0 = time.time()
        hit = router.match(text)
        path = "fast"
        if not hit:
            path = "llm"
            if not self.llm.ready:
                return dict(reply="My language model is still warming up. Try a direct command.", detail="",
                            tool=None, args={}, path=path, seconds=time.time() - t0)
            msg = await asyncio.to_thread(self.llm.chat, [*self.history[-6:], {"role": "user", "content": text}])
            calls = msg.get("tool_calls") or []
            if calls:
                fn = calls[0]["function"]
                try:
                    args = json.loads(fn["arguments"]) if isinstance(fn["arguments"], str) else fn["arguments"]
                    hit = (fn["name"], args)
                except json.JSONDecodeError:
                    hit = None
            if not hit:
                reply = (msg.get("content") or "Sorry, I didn't get that.").strip()
                self._remember_turn(text, reply)
                return dict(reply=reply, detail="", tool=None, args={}, path=path, seconds=time.time() - t0)

        name, args = hit
        reply, detail = await self.run_tool(name, args, session)
        # no device named and the phone can't open it (missing app, offline, no answer): it's probably a laptop app
        if name == "open_app" and args.get("device") != "laptop" and "phone" not in text.lower() \
                and re.match(r"I couldn't find|The phone (isn't connected|didn't answer)", reply):
            args = {**args, "device": "laptop"}
            reply, detail = await self.run_tool(name, args, session)
        self._remember_turn(text, reply)
        return dict(reply=reply, detail=detail, tool=name, args=args, path=path, seconds=time.time() - t0)

    async def run_tool(self, name, args, session):
        tool = TOOLS.get(name)
        if not tool:
            return f"I don't have a tool called {name}.", ""
        missing = [k for k in tool.required if args.get(k) in (None, "")]
        if missing:
            return f"I need the {missing[0]} for that.", ""
        tier = tool.tier_for(args)
        approved = True
        if tier >= 2:  # enforced here, never left to the model
            approved = await session.confirm(describe(name, args), tier)
            if not approved:
                self.store.audit(session.name, name, args, tier, False, "declined")
                return "Okay, cancelled.", ""
        await session.activity(name, args)
        try:
            if tool.on_phone(args):
                reply, detail = await session.phone(name, args), ""
            else:
                reply, detail = await asyncio.to_thread(tool.run, **{k: v for k, v in args.items() if k in tool.props})
        except Exception as e:  # tool errors become a spoken answer, never a crash
            reply, detail = f"That didn't work: {e}", repr(e)
        self.store.audit(session.name, name, args, tier, approved, reply)
        return reply, detail

    def _remember_turn(self, user, reply):
        self.history += [{"role": "user", "content": user}, {"role": "assistant", "content": reply}]
        self.history = self.history[-6:]

def sentences(text):
    return [s for s in re.split(r"(?<=[.!?])\s+", text.strip()) if s]
