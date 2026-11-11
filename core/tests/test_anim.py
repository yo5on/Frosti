"""FROSTI's animation spec: seams, sizes, references and mappings. Also runs the controller tests in Node if present."""
import json, shutil, subprocess, sys, unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
sys.path.insert(0, str(ROOT / "frosti_core" / "anim"))
import author  # noqa: E402
from frosti_core.tools import TOOLS, GIT_TIER  # noqa: E402

SPEC = json.loads(author.OUT.read_text(encoding="utf-8"))
REST = SPEC["rest"]
FX = {"bars": 1, "rise": 8, "orbit": 8, "burst": 5, "ping": 4, "glitch": 1}  # kind -> list length

def full(a, f):
    return {k: v for k, v in {**a["pose"], **f}.items() if k != "n"}

class AnimSpec(unittest.TestCase):
    def test_json_is_generated_from_author(self):
        self.assertEqual(author.OUT.read_text(encoding="utf-8"), author.dump(), "run anim/author.py")

    def test_every_animation_starts_and_ends_on_rest(self):
        for id, a in SPEC["anims"].items():
            p = a["parts"]
            with self.subTest(id):
                self.assertTrue(p["loop"], "needs a loop")
                if p["enter"] and not a["offstage"]:
                    self.assertEqual(full(a, p["enter"][0]), REST, "enter must start at rest")
                if p["enter"]:
                    self.assertEqual(full(a, p["enter"][-1]), a["pose"], "enter must land on the pose")
                self.assertEqual(full(a, p["loop"][0]), a["pose"], "loop must start on the pose")
                self.assertEqual(full(a, p["loop"][-1]), a["pose"], "loop must end on the pose")
                exits = [k for k in p if k.startswith("exit")]
                for k in exits:
                    if p[k]:
                        self.assertEqual(full(a, p[k][0]), a["pose"], f"{k} must start on the pose")
                        self.assertEqual(full(a, p[k][-1]), REST, f"{k} must end at rest")
                    else:
                        self.assertEqual(a["pose"], REST, f"{k} is empty, so the pose must be rest")

    def test_frames_reference_real_things(self):
        props, eyes = SPEC["props"], set(SPEC["eyes"])
        for id, a in SPEC["anims"].items():
            for part, frames in a["parts"].items():
                for f in frames:
                    g = full(a, f)
                    with self.subTest(id=id, part=part):
                        self.assertIsInstance(f["n"], int); self.assertGreater(f["n"], 0)
                        self.assertIn(g["eyes"], eyes)
                        for name, x, y, *flags in g["props"]:
                            self.assertIn(name, props)
                            self.assertTrue(set("".join(flags)) <= {"h", "b"})
                        for fx in g["fx"]:
                            self.assertIn(fx[0], FX); self.assertEqual(len(fx), FX[fx[0]], fx)
                            if fx[0] in ("rise", "orbit"):
                                self.assertIn(fx[1], props)

    def test_props_use_the_palette_and_stay_on_the_canvas(self):
        pal = set(SPEC["palette"]) | {"."}
        for name, rows in SPEC["props"].items():
            with self.subTest(name):
                self.assertEqual(len({len(r) for r in rows}), 1, "rows must be the same width")
                self.assertTrue(set("".join(rows)) <= pal)
        cw, ch = SPEC["canvas"]; ox, oy = SPEC["origin"]
        for id, a in SPEC["anims"].items():
            for frames in a["parts"].values():
                for f in frames:
                    g = full(a, f)
                    for name, x, y, *flags in g["props"]:
                        rows = SPEC["props"][name]
                        dx, dy = (g["dx"], g["dy"]) if "h" in "".join(flags) else (0, 0)
                        with self.subTest(id=id, prop=name):
                            self.assertGreaterEqual(x + dx + ox, 0); self.assertLessEqual(x + dx + ox + len(rows[0]), cw)
                            self.assertGreaterEqual(y + dy + oy, 0); self.assertLessEqual(y + dy + oy + len(rows), ch)

    def test_looping_effects_line_up_with_the_loop(self):
        """An effect that runs through a whole loop must repeat a whole number of times per pass, or it jumps."""
        for id, a in SPEC["anims"].items():
            length = sum(f["n"] for f in a["parts"]["loop"])
            for fx in a["pose"]["fx"]:
                period = {"rise": 6, "orbit": 6, "burst": 3, "ping": 3}.get(fx[0])
                if period:
                    with self.subTest(id=id, fx=fx[0]):
                        self.assertEqual(length % fx[period], 0, f"loop {length} ticks, period {fx[period]}")

    def test_every_tool_and_state_has_an_animation(self):
        m, anims = SPEC["map"], SPEC["anims"]
        for name in TOOLS:
            self.assertIn(name, m["tools"], f"tool {name} has no animation")
        for action in ("commit", "push", "pull"):
            self.assertIn(f"git:{action}", m["tools"])
        self.assertTrue(set(GIT_TIER) >= {"status", "log", "diff", "pull", "commit", "push"})
        for s in ("idle", "listening", "thinking", "speaking", "offline", "error", "sleep"):
            self.assertIn(s, m["states"])
        targets = [*m["states"].values(), *m["tools"].values(), *m["reactions"].values(), *m["pastimes"],
                   m["permission"], m["permission_wait"], m["think_hard"], *m["variety"],
                   *[v for vs in m["variety"].values() for v in vs]]
        for t in targets:
            self.assertIn(t, anims)
        unmapped = {id for id, a in anims.items() if not a["mapped"]}
        self.assertEqual(unmapped & set(targets), set(), "a gallery-only animation must not be wired to an event")
        self.assertEqual(unmapped, {"thinking"})

    def test_gallery_is_served_on_loopback_only(self):
        from frosti_core.gateway import console_page
        class R:
            def __init__(self, host, path): self.headers, self.path = {"Host": host}, path
        class C:
            def __init__(self, ip): self.remote_address = (ip, 1)
            def respond(self, status, text):
                class Resp: pass
                r = Resp(); r.status, r.body, r.headers = status, text, {"Content-Type": "text/plain"}
                return r
        for path, kind in (("/gallery", "text/html"), ("/frosti.js", "javascript"), ("/frosti_anims.json", "json")):
            self.assertEqual(console_page(C("192.168.1.5"), R("127.0.0.1:8765", path)).status, 403)
            ok = console_page(C("127.0.0.1"), R("127.0.0.1:8765", path))
            self.assertEqual(ok.status, 200); self.assertIn(kind, ok.headers["Content-Type"])
        self.assertEqual(console_page(C("127.0.0.1"), R("127.0.0.1:8765", "/author.py")).status, 404)

    @unittest.skipUnless(shutil.which("node"), "Node.js not installed")
    def test_controller_behaviour_in_node(self):
        r = subprocess.run(["node", "--test", str(Path(__file__).with_name("frosti_controller.test.js"))],
                           capture_output=True, text=True, timeout=120)
        self.assertEqual(r.returncode, 0, r.stdout[-4000:] + r.stderr[-2000:])

if __name__ == "__main__":
    unittest.main()
