"""Phase 0 benchmark: SPARKY tool-calling accuracy + latency for a local GGUF model.

Starts llama-server, sends each test case through the OpenAI-compatible chat API with
SPARKY's tool set, and checks the chosen tool and arguments. Stdlib only.

usage: python bench/llm_tools.py --model D:/sparky-models/x.gguf [--llama D:/sparky-tools/llama-cpu]
"""
import argparse, json, statistics, subprocess, sys, time, urllib.request
from pathlib import Path

PORT = 8091
SYSTEM = (
    "You are SPARKY, a concise local assistant that controls the user's Android phone and Windows laptop. "
    "When a request maps to a tool, call exactly one tool with correct arguments. "
    "If no tool fits (small talk, general knowledge), answer in one short sentence without calling a tool."
)

def tool(name, desc, props, required):
    return {"type": "function", "function": {"name": name, "description": desc,
            "parameters": {"type": "object", "properties": props, "required": required}}}

S, I = {"type": "string"}, {"type": "integer"}
TOOLS = [
    tool("open_app", "Open an app on the phone or laptop", {"name": S, "device": {"type": "string", "enum": ["phone", "laptop"]}}, ["name", "device"]),
    tool("get_status", "Read a system metric", {"metric": {"type": "string", "enum": ["battery", "ram", "cpu", "storage", "temperature"]}, "device": {"type": "string", "enum": ["phone", "laptop"]}}, ["metric", "device"]),
    tool("set_brightness", "Set phone screen brightness percent", {"level": I}, ["level"]),
    tool("set_volume", "Set phone media volume percent", {"level": I}, ["level"]),
    tool("open_vscode", "Open a folder in VS Code on the laptop", {"path": S}, ["path"]),
    tool("git", "Run a read-only or commit git action in a project", {"project": S, "action": {"type": "string", "enum": ["status", "log", "diff", "pull", "commit", "push"]}, "message": S}, ["project", "action"]),
    tool("run_project", "Run a project's configured task", {"project": S, "task": {"type": "string", "enum": ["dev", "test", "build"]}}, ["project", "task"]),
    tool("run_powershell", "Run an arbitrary PowerShell command on the laptop (requires user confirmation)", {"command": S}, ["command"]),
    tool("set_timer", "Start a countdown timer", {"minutes": I, "label": S}, ["minutes"]),
    tool("remember", "Store a fact about the user for later", {"fact": S}, ["fact"]),
    tool("recall", "Search SPARKY's memory", {"query": S}, ["query"]),
    tool("kill_process", "Terminate a laptop process by name", {"name": S}, ["name"]),
    tool("describe_screen", "Look at the current screen and describe it", {"device": {"type": "string", "enum": ["phone", "laptop"]}}, ["device"]),
]

def has(sub):  # case-insensitive substring check on a string arg
    return lambda v: isinstance(v, str) and sub.lower() in v.lower()

def eq(x):
    return lambda v: v == x or (isinstance(v, str) and v.strip().lower() == str(x).lower())

# (utterance, expected tool or None, {arg: predicate})
CASES = [
    ("Open Spotify on my phone", "open_app", {"name": has("spotify"), "device": eq("phone")}),
    ("launch chrome on the laptop", "open_app", {"name": has("chrome"), "device": eq("laptop")}),
    ("How much battery does the phone have left?", "get_status", {"metric": eq("battery"), "device": eq("phone")}),
    ("what's my laptop RAM usage", "get_status", {"metric": eq("ram"), "device": eq("laptop")}),
    ("is the laptop running hot?", "get_status", {"metric": eq("temperature"), "device": eq("laptop")}),
    ("how full is the phone storage", "get_status", {"metric": eq("storage"), "device": eq("phone")}),
    ("set brightness to 40 percent", "set_brightness", {"level": eq(40)}),
    ("make the screen as bright as possible", "set_brightness", {"level": eq(100)}),
    ("turn the volume down to 20", "set_volume", {"level": eq(20)}),
    ("mute the phone", "set_volume", {"level": eq(0)}),
    (r"open E:\sparky in vs code", "open_vscode", {"path": has("sparky")}),
    ("git status for sparky", "git", {"project": has("sparky"), "action": eq("status")}),
    ("show me the recent commits in sparky", "git", {"project": has("sparky"), "action": eq("log")}),
    ("commit sparky with message 'add bench'", "git", {"project": has("sparky"), "action": eq("commit"), "message": has("add bench")}),
    ("push sparky to github", "git", {"project": has("sparky"), "action": eq("push")}),
    ("run the tests for sparky", "run_project", {"project": has("sparky"), "task": eq("test")}),
    ("start the sparky dev server", "run_project", {"project": has("sparky"), "task": eq("dev")}),
    ("build sparky", "run_project", {"project": has("sparky"), "task": eq("build")}),
    ("list the files in my downloads folder using powershell", "run_powershell", {"command": has("download")}),
    ("what's my IP address on the laptop", "run_powershell", {"command": lambda v: isinstance(v, str) and ("ip" in v.lower())}),
    ("set a timer for 10 minutes for tea", "set_timer", {"minutes": eq(10), "label": has("tea")}),
    ("remind me in half an hour", "set_timer", {"minutes": eq(30)}),
    ("remember that I prefer dark themes", "remember", {"fact": has("dark")}),
    ("remember my main project is SPARKY", "remember", {"fact": has("sparky")}),
    ("what did I tell you about my themes?", "recall", {"query": has("theme")}),
    ("kill chrome on the laptop", "kill_process", {"name": has("chrome")}),
    ("node is frozen, force close it", "kill_process", {"name": has("node")}),
    ("what's on my laptop screen right now?", "describe_screen", {"device": eq("laptop")}),
    ("read what's on the phone screen", "describe_screen", {"device": eq("phone")}),
    ("hey sparky, how are you?", None, {}),
    ("what is the capital of France?", None, {}),
    ("thanks, that's all", None, {}),
]

def post(path, body, timeout=600):
    req = urllib.request.Request(f"http://127.0.0.1:{PORT}{path}", json.dumps(body).encode(),
                                 {"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.load(r)

def wait_ready(proc, timeout=300):
    t = time.time()
    while time.time() - t < timeout:
        if proc.poll() is not None:
            sys.exit(f"llama-server exited with {proc.returncode}")
        try:
            with urllib.request.urlopen(f"http://127.0.0.1:{PORT}/health", timeout=2) as r:
                if r.status == 200:
                    return time.time() - t
        except Exception:
            time.sleep(1)
    sys.exit("llama-server not ready")

def check(case, msg):
    _, want, preds = case
    calls = msg.get("tool_calls") or []
    if want is None:
        return (not calls, "no-tool" if not calls else f"called {calls[0]['function']['name']}")
    if not calls:
        return False, "no tool call: " + (msg.get("content") or "")[:60]
    fn = calls[0]["function"]
    if fn["name"] != want:
        return False, f"wrong tool {fn['name']}"
    try:
        args = json.loads(fn["arguments"]) if isinstance(fn["arguments"], str) else fn["arguments"]
    except json.JSONDecodeError:
        return False, "bad json args"
    bad = [k for k, p in preds.items() if not p(args.get(k))]
    return (not bad, "ok" if not bad else f"bad args {bad}: {json.dumps(args)[:80]}")

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", required=True)
    ap.add_argument("--llama", default="D:/sparky-tools/llama-cpu")
    ap.add_argument("--threads", type=int, default=4)
    ap.add_argument("--extra", default="", help="extra llama-server args")
    a = ap.parse_args()

    cmd = [str(Path(a.llama) / "llama-server.exe"), "-m", a.model, "--port", str(PORT), "--jinja",
           "-t", str(a.threads), "-c", "4096", "--no-webui", *a.extra.split()]
    log = open(Path(__file__).parent / "out" / (Path(a.model).stem + ".server.log"), "w")
    proc = subprocess.Popen(cmd, stdout=log, stderr=subprocess.STDOUT)
    try:
        load_s = wait_ready(proc)
        rows = []
        for case in CASES:
            body = {"messages": [{"role": "system", "content": SYSTEM}, {"role": "user", "content": case[0]}],
                    "tools": TOOLS, "temperature": 0, "max_tokens": 160,
                    "chat_template_kwargs": {"enable_thinking": False}}
            t = time.time()
            r = post("/v1/chat/completions", body)
            wall = time.time() - t
            ok, why = check(case, r["choices"][0]["message"])
            tm = r.get("timings", {})
            rows.append(dict(utt=case[0], ok=ok, why=why, wall=round(wall, 2),
                             prompt_n=tm.get("prompt_n"), pp_tps=round(tm.get("prompt_per_second", 0), 1),
                             gen_n=tm.get("predicted_n"), tg_tps=round(tm.get("predicted_per_second", 0), 1)))
            print(f"{'PASS' if ok else 'FAIL'} {wall:5.1f}s  {case[0][:45]:45}  {why}", flush=True)
    finally:
        proc.terminate()

    warm = rows[1:]  # first request pays the cold system-prompt + tools prefill
    summary = dict(model=Path(a.model).name, threads=a.threads, extra=a.extra, load_s=round(load_s, 1),
                   accuracy=f"{sum(r['ok'] for r in rows)}/{len(rows)}",
                   cold_first_s=rows[0]["wall"], cold_prompt_tokens=rows[0]["prompt_n"],
                   cold_pp_tps=rows[0]["pp_tps"],
                   warm_median_s=round(statistics.median(r["wall"] for r in warm), 2),
                   warm_p90_s=round(sorted(r["wall"] for r in warm)[int(len(warm) * 0.9)], 2),
                   tg_tps_median=statistics.median(r["tg_tps"] for r in rows if r["tg_tps"]))
    print(json.dumps(summary, indent=2))
    out = Path(__file__).parent / "out" / (Path(a.model).stem + ".json")
    out.write_text(json.dumps({"summary": summary, "rows": rows}, indent=2))

if __name__ == "__main__":
    (Path(__file__).parent / "out").mkdir(exist_ok=True)
    main()
