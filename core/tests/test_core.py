import asyncio, sqlite3, sys, tempfile, unittest
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from sparky_core import router
from sparky_core.brain import Brain, Session
from sparky_core.store import Store

FAST = [  # utterance -> (tool, args) the fast path must produce
    ("Open Spotify on my phone", ("open_app", {"name": "spotify", "device": "phone"})),
    ("launch chrome on the laptop", ("open_app", {"name": "chrome", "device": "laptop"})),
    ("How much battery does the phone have left?", ("get_status", {"metric": "battery", "device": "phone"})),
    ("what's my laptop RAM usage", ("get_status", {"metric": "ram", "device": "laptop"})),
    ("is the laptop running hot?", ("get_status", {"metric": "temperature", "device": "laptop"})),
    ("Set brightness to forty percent.", ("set_brightness", {"level": 40})),
    ("make the screen as bright as possible", ("set_brightness", {"level": 100})),
    ("turn the volume down to 20", ("set_volume", {"level": 20})),
    ("mute the phone", ("set_volume", {"level": 0})),
    (r"open E:\sparky in vs code", ("open_vscode", {"path": r"e:\sparky"})),
    ("git status for sparky", ("git", {"project": "sparky", "action": "status"})),
    ("Show me the recent commits in sparky.", ("git", {"project": "sparky", "action": "log"})),
    ("push sparky to github", ("git", {"project": "sparky", "action": "push"})),
    ("Run the tests for sparky.", ("run_project", {"project": "sparky", "task": "test"})),
    ("start the sparky dev server", ("run_project", {"project": "sparky", "task": "dev"})),
    ("build sparky", ("run_project", {"project": "sparky", "task": "build"})),
    ("Set a timer for ten minutes for tea.", ("set_timer", {"minutes": 10, "label": "tea"})),
    ("remind me in half an hour", ("set_timer", {"minutes": 30, "label": ""})),
    ("Remember that I prefer dark themes.", ("remember", {"fact": "i prefer dark themes"})),
    ("what did I tell you about my themes?", ("recall", {"query": "my themes"})),
    ("Kill chrome on the laptop.", ("kill_process", {"name": "chrome"})),
    ("Hey Sparky, open Spotify.", ("open_app", {"name": "spotify", "device": "phone"})),
]
TO_LLM = ["hey sparky, how are you?", "what is the capital of France?", "list the files in my downloads folder using powershell",
          "commit sparky with message 'add bench'", "Good evening, Sparky. How are the systems looking?"]

class Router(unittest.TestCase):
    def test_fast_path(self):
        for utt, want in FAST:
            with self.subTest(utt):
                self.assertEqual(router.match(utt), want)

    def test_falls_through_to_llm(self):
        for utt in TO_LLM:
            with self.subTest(utt):
                self.assertIsNone(router.match(utt))

class FakeLLM:
    ready = True
    def __init__(self, msg): self.msg = msg
    def chat(self, messages, max_tokens=160): return self.msg

class BrainPolicy(unittest.TestCase):
    def setUp(self):
        self.store = Store(Path(tempfile.mkdtemp()) / "t.db")

    def run_(self, text, llm_msg=None, approve=False):
        class S(Session):
            asked = []
            async def confirm(s, summary, tier): S.asked.append((summary, tier)); return approve
        b = Brain(self.store, FakeLLM(llm_msg or {"content": "Hi."}))
        return asyncio.run(b.handle(text, S())), S.asked

    def test_powershell_needs_confirmation_and_is_audited(self):
        msg = {"tool_calls": [{"function": {"name": "run_powershell", "arguments": '{"command": "Remove-Item x"}'}}]}
        r, asked = self.run_("delete x with powershell", msg, approve=False)
        self.assertEqual(asked, [("Run PowerShell: Remove-Item x", 3)])
        self.assertEqual(r["reply"], "Okay, cancelled.")
        self.assertEqual(self.store.db.execute("SELECT tool, approved FROM audit").fetchall(), [("run_powershell", 0)])

    def test_audit_is_append_only(self):
        self.store.audit("t", "x", {}, 0, True, "ok")
        with self.assertRaises(sqlite3.DatabaseError):
            self.store.db.execute("DELETE FROM audit")

    def test_memory_roundtrip(self):
        self.run_("remember that I prefer dark themes")
        r, _ = self.run_("what did I tell you about my themes?")
        self.assertIn("dark themes", r["reply"])

    def test_read_tool_runs_without_confirmation(self):
        r, asked = self.run_("what's my laptop RAM usage")
        self.assertEqual(asked, [])
        self.assertIn("percent used", r["reply"])

    def test_phone_tool_without_phone(self):
        r, _ = self.run_("set brightness to 40")
        self.assertEqual(r["reply"], "The phone isn't connected.")

if __name__ == "__main__":
    unittest.main()
