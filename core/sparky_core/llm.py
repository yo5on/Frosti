"""llama-server lifecycle + chat. The system prompt and tool list are frozen so the prefix cache holds (Phase 0)."""
import json, subprocess, time, urllib.request
from . import CONFIG, DATA

SYSTEM = (
    "You are SPARKY, a concise local assistant that controls the user's Android phone and Windows laptop. "
    "When a request maps to a tool, call exactly one tool with correct arguments. "
    "If no tool fits (small talk, general knowledge), answer in one short sentence without calling a tool."
)

class LLM:
    def __init__(self, tools):
        c = CONFIG["llm"]
        self.url = f"http://127.0.0.1:{c['port']}"
        self.tools = tools  # list of OpenAI tool schemas, never mutated after start
        self.proc = None
        self.ready = False

    def _get(self, path, timeout=2):
        with urllib.request.urlopen(self.url + path, timeout=timeout) as r:
            return r.status

    def start(self):
        """Start llama-server (unless one is already up) and warm the prefix. Blocking: ~15 s load + ~70 s warm-up."""
        c = CONFIG["llm"]
        try:
            self._get("/health")
        except OSError:
            DATA.mkdir(exist_ok=True)
            log = open(DATA / "llama-server.log", "w")
            self.proc = subprocess.Popen([c["server"], "-m", c["model"], "--port", str(c["port"]), "--jinja",
                                          "-t", str(c["threads"]), "-c", "4096", "--no-webui", "--host", "127.0.0.1"],
                                         stdout=log, stderr=subprocess.STDOUT, creationflags=subprocess.CREATE_NO_WINDOW)
            for _ in range(300):
                if self.proc.poll() is not None:
                    raise RuntimeError(f"llama-server exited ({self.proc.returncode}); see data/llama-server.log")
                try:
                    if self._get("/health") == 200:
                        break
                except OSError:
                    time.sleep(1)
        t = time.time()
        self.chat([{"role": "user", "content": "hello"}], max_tokens=1)  # fills the KV cache with system + tools
        self.ready = True
        return time.time() - t

    def chat(self, messages, max_tokens=160):
        body = {"messages": [{"role": "system", "content": SYSTEM}, *messages], "tools": self.tools,
                "temperature": 0, "max_tokens": max_tokens, "chat_template_kwargs": {"enable_thinking": False}}
        req = urllib.request.Request(self.url + "/v1/chat/completions", json.dumps(body).encode(),
                                     {"Content-Type": "application/json"})
        with urllib.request.urlopen(req, timeout=300) as r:
            return json.load(r)["choices"][0]["message"]

    def stop(self):
        if self.proc:
            self.proc.terminate()
