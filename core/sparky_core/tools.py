"""Tool registry: schemas the LLM sees, risk tiers enforced in code, laptop implementations.

Phone tools have no laptop implementation (run=None): the gateway forwards them to the Shell app.
Every run() returns (spoken_text, detail_text).
"""
import os, re, shutil, subprocess, time
from dataclasses import dataclass
from pathlib import Path
from typing import Callable
import psutil
from . import CONFIG, ROOT

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

def open_app(name, device="laptop"):
    name = APP_ALIASES.get(name.lower().strip(), name.strip())
    if not SAFE_NAME.match(name):  # trust boundary: the name ends up in a shell 'start'
        return "That app name looks unsafe, so I didn't run it.", name
    rc, out = sh(["cmd", "/c", "start", "", name])
    return (f"Opening {name}." if rc == 0 else f"I couldn't find {name} on the laptop."), out

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
    if metric == "temperature":
        # shortcut: Windows exposes CPU temperature only to admin (WMI thermal zone); report load instead until a sensor helper exists
        return f"I can't read the laptop's temperature without admin rights. CPU load is {psutil.cpu_percent(0.5):.0f} percent.", ""
    return f"I don't know the metric {metric}.", ""

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
             "svchost.exe", "dwm.exe", "explorer.exe", "msmpeng.exe", "python.exe", "llama-server.exe"}

def kill_process(name):
    n = name.lower().strip()
    n = n if n.endswith(".exe") else n + ".exe"
    if n in PROTECTED:
        return f"{n} is protected, so I won't kill it.", ""
    procs = [p for p in psutil.process_iter(["name"]) if (p.info["name"] or "").lower() == n]
    for p in procs:
        try:
            p.kill()
        except psutil.Error:
            pass
    return (f"Closed {len(procs)} {n} process{'es' if len(procs) != 1 else ''}." if procs else f"No {n} is running."), ""

def describe_screen(device="laptop"):
    # shortcut: screen understanding is Phase 7 (Windows OCR + UIA, phone accessibility tree); upgrade there
    return "I can't see screens yet. That arrives with the vision update.", ""

# set_timer / remember / recall need the running Brain, so it binds them (see brain.py).

S, I = {"type": "string"}, {"type": "integer"}
DEV = {"type": "string", "enum": ["phone", "laptop"]}
GIT_TIER = {"status": 0, "log": 0, "diff": 0, "pull": 1, "commit": 2, "push": 3}

TOOLS = {t.name: t for t in [
    Tool("open_app", "Open an app on the phone or laptop", {"name": S, "device": DEV}, ["name", "device"], 1,
         open_app, phone_if=lambda a: a.get("device") != "laptop"),
    Tool("get_status", "Read a system metric",
         {"metric": {"type": "string", "enum": ["battery", "ram", "cpu", "storage", "temperature"]}, "device": DEV},
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
    Tool("recall", "Search SPARKY's memory", {"query": S}, ["query"], 0),
    Tool("kill_process", "Terminate a laptop process by name", {"name": S}, ["name"], 2, kill_process),
    Tool("describe_screen", "Look at the current screen and describe it", {"device": DEV}, ["device"], 0, describe_screen),
]}
