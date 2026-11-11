import asyncio, sqlite3, sys, tempfile, unittest
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from frosti_core import router
from frosti_core.brain import Brain, Session
from frosti_core.store import Store

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
    (r"open E:\frosti in vs code", ("open_vscode", {"path": r"e:\frosti"})),
    ("git status for frosti", ("git", {"project": "frosti", "action": "status"})),
    ("Show me the recent commits in frosti.", ("git", {"project": "frosti", "action": "log"})),
    ("push frosti to github", ("git", {"project": "frosti", "action": "push"})),
    ("Run the tests for frosti.", ("run_project", {"project": "frosti", "task": "test"})),
    ("start the frosti dev server", ("run_project", {"project": "frosti", "task": "dev"})),
    ("build frosti", ("run_project", {"project": "frosti", "task": "build"})),
    ("Set a timer for ten minutes for tea.", ("set_timer", {"minutes": 10, "label": "tea"})),
    ("remind me in half an hour", ("set_timer", {"minutes": 30, "label": ""})),
    ("Remember that I prefer dark themes.", ("remember", {"fact": "i prefer dark themes"})),
    ("what did I tell you about my themes?", ("recall", {"query": "my themes"})),
    ("Kill chrome on the laptop.", ("kill_process", {"name": "chrome"})),
    ("Hey Frosti, open Spotify.", ("open_app", {"name": "spotify", "device": "phone"})),
    ("open calculator in laptop", ("open_app", {"name": "calculator", "device": "laptop"})),
    ("open paint on my pc", ("open_app", {"name": "paint", "device": "laptop"})),
    ("Close Word.", ("kill_process", {"name": "word"})),
    ("quit the calculator app on the laptop", ("kill_process", {"name": "calculator"})),
    ("Play Bohemian Rhapsody by Queen.", ("play_music", {"query": "bohemian rhapsody by queen", "device": "phone"})),
    ("play some jazz on my laptop", ("play_music", {"query": "some jazz", "device": "laptop"})),
    ("pause the music", ("media_control", {"action": "pause", "device": "phone"})),
    ("Resume", ("media_control", {"action": "play", "device": "phone"})),
    ("next song", ("media_control", {"action": "next", "device": "phone"})),
    ("skip this track on the laptop", ("media_control", {"action": "next", "device": "laptop"})),
    ("play the previous song", ("media_control", {"action": "previous", "device": "phone"})),
]
TO_LLM = ["hey frosti, how are you?", "what is the capital of France?", "list the files in my downloads folder using powershell",
          "commit frosti with message 'add bench'", "Good evening, Frosti. How are the systems looking?"]

class Router(unittest.TestCase):
    def test_fast_path(self):
        for utt, want in FAST:
            with self.subTest(utt):
                self.assertEqual(router.match(utt), want)

    def test_falls_through_to_llm(self):
        for utt in TO_LLM:
            with self.subTest(utt):
                self.assertIsNone(router.match(utt))

class Apps(unittest.TestCase):
    APPS = [{"name": "Word", "id": None, "lnk": "Word.lnk", "exe": r"C:\Office\WINWORD.EXE"},
            {"name": "Google Chrome", "id": "Chrome", "lnk": "Google Chrome.lnk", "exe": r"C:\x\chrome.exe"},
            {"name": "Chrome Remote Desktop", "id": "crd", "lnk": None, "exe": ""},
            {"name": "Calculator", "id": "Microsoft.WindowsCalculator_8wekyb3d8bbwe!App", "lnk": None, "exe": ""},
            {"name": "Spotify", "id": "SpotifyAB.Spotify!Spotify", "lnk": None, "exe": ""}]

    def test_find_app_fuzzy(self):
        from frosti_core.tools import find_app
        name = lambda q: (find_app(q, self.APPS) or {}).get("name")
        self.assertEqual(name("word"), "Word")
        self.assertEqual(name("chrome"), "Google Chrome")  # shortest whole-word match
        self.assertEqual(name("spotfy"), "Spotify")         # speech-to-text typo
        self.assertIsNone(name("notepad"))

    def test_process_for_friendly_names(self):
        from frosti_core.tools import process_for
        running = {"winword.exe", "chrome.exe", "calculatorapp.exe", "code.exe", "codehelper.exe"}
        self.assertEqual(process_for("word", running, self.APPS), ["winword.exe"])     # via the shortcut's exe
        self.assertEqual(process_for("chrome.exe", running, self.APPS), ["chrome.exe"])
        self.assertEqual(process_for("calculator", running, self.APPS), ["calculatorapp.exe"])
        self.assertEqual(process_for("vs code", running, self.APPS), ["code.exe"])  # alias
        self.assertEqual(process_for("ode", running, self.APPS), ["code.exe", "codehelper.exe"])  # ambiguous: ask
        self.assertEqual(process_for("ab", running, self.APPS), [])

    def test_open_app_keeps_trust_boundary(self):
        from frosti_core.tools import open_app
        self.assertIn("unsafe", open_app("calc & del x")[0])

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

    def test_open_app_falls_back_to_laptop_unless_phone_named(self):
        from unittest import mock
        from frosti_core.tools import TOOLS
        with mock.patch.object(TOOLS["open_app"], "run", lambda name, device: (f"Opening {name}.", "")):
            r, _ = self.run_("open notepad")  # no phone connected in this test
            self.assertEqual((r["reply"], r["args"]["device"]), ("Opening notepad.", "laptop"))
            r, _ = self.run_("open notepad on my phone")
            self.assertEqual(r["reply"], "The phone isn't connected.")

    def test_phone_tool_without_phone(self):
        r, _ = self.run_("set brightness to 40")
        self.assertEqual(r["reply"], "The phone isn't connected.")

class Dashboards(unittest.TestCase):
    def setUp(self):
        self.store = Store(Path(tempfile.mkdtemp()) / "t.db")
        self.brain = Brain(self.store, FakeLLM({"content": "Hi."}))

    def test_memory_lists_and_forgets_facts(self):
        from frosti_core.gateway import memory
        self.store.remember("I like tea"); self.store.remember("my cat is Io")
        facts = memory(self.brain)["facts"]
        self.assertEqual({f["fact"] for f in facts}, {"I like tea", "my cat is Io"})
        self.assertEqual(self.store.forget(facts[0]["id"]), 1)
        self.assertEqual(len(memory(self.brain)["facts"]), 1)

    def test_timer_shows_in_tasks_until_due(self):
        asyncio.run(self.brain.handle("set a timer for 5 minutes for tea", Session()))
        self.assertEqual([t["label"] for t in self.brain.active_timers()], ["tea"])
        self.brain.timers[0]["due"] = 0
        self.assertEqual(self.brain.active_timers(), [])

    def test_activity_log_has_args_newest_first(self):
        self.store.audit("phone", "set_brightness", {"level": 40}, 1, True, "Brightness 40%")
        self.store.audit("desk", "kill", {"name": "notepad"}, 2, False, "declined")
        log = self.store.recent(300)
        self.assertEqual([e["tool"] for e in log[:2]], ["kill", "set_brightness"])
        self.assertEqual(log[0]["args"], '{"name": "notepad"}')
        self.assertFalse(log[0]["approved"])

    def test_stats_shape(self):
        from frosti_core.gateway import stats
        s = stats(self.brain)
        for k in ("cpu", "ram", "model", "llm", "voice", "phone", "uptime"):
            self.assertIn(k, s)

    def test_console_page_is_loopback_only(self):
        from frosti_core.gateway import console_page
        class R:
            def __init__(self, host, path="/"): self.headers, self.path = {"Host": host}, path
        class C:
            def __init__(self, ip): self.remote_address = (ip, 1)
            def respond(self, status, text):
                class Resp: pass
                r = Resp(); r.status, r.body = status, text
                r.headers = {"Content-Type": "text/plain"}
                return r
        self.assertEqual(console_page(C("192.168.1.5"), R("127.0.0.1:8765")).status, 403)
        self.assertEqual(console_page(C("127.0.0.1"), R("evil.example:8765")).status, 403)  # DNS rebinding
        ok = console_page(C("127.0.0.1"), R("127.0.0.1:8765"))
        self.assertEqual(ok.status, 200)
        self.assertNotIn("__FROSTI_TOKEN__", ok.body)
        self.assertIn("text/html", ok.headers["Content-Type"])

if __name__ == "__main__":
    unittest.main()
