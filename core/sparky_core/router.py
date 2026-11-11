"""Fast path: deterministic rules for common commands, so they never wait ~5 s for the LLM.

match(text) -> (tool_name, args) or None. Anything ambiguous returns None and goes to the LLM.
"""
import re
from .tools import PROJECTS

UNITS = "zero one two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen sixteen seventeen eighteen nineteen".split()
TENS = {"twenty": 20, "thirty": 30, "forty": 40, "fifty": 50, "sixty": 60, "seventy": 70, "eighty": 80, "ninety": 90}

def words_to_numbers(t):
    t = re.sub(r"\b(" + "|".join(TENS) + r")[ -](" + "|".join(UNITS[1:10]) + r")\b",
               lambda m: str(TENS[m[1]] + UNITS.index(m[2])), t)
    t = re.sub(r"\b(" + "|".join(TENS) + r")\b", lambda m: str(TENS[m[1]]), t)
    t = re.sub(r"\bone hundred\b|\ba hundred\b", "100", t)
    t = re.sub(r"\bhalf an hour\b", "30 minutes", t)
    t = re.sub(r"\ban hour\b", "60 minutes", t)
    return re.sub(r"\b(" + "|".join(UNITS) + r")\b", lambda m: str(UNITS.index(m[1])), t)

def normalize(text):
    t = text.lower().strip()
    t = re.sub(r"^(hey,? )?sparky[,.!]?\s*", "", t)
    t = re.sub(r"[?!.,]+$|[,]", "", t)
    t = re.sub(r"\b(please|can you|could you|would you|for me)\b", "", t)
    return re.sub(r"\s+", " ", words_to_numbers(t)).strip()

def device_of(t, default):
    return "phone" if "phone" in t else "laptop" if re.search(r"\b(laptop|computer|pc)\b", t) else default

def project_in(t):
    return next((p for p in PROJECTS if re.search(rf"\b{re.escape(p)}\b", t)), None)

METRICS = [("battery", r"\bbattery\b"), ("ram", r"\b(ram|memory)\b(?! about)"), ("cpu", r"\b(cpu|processor)\b"),
           ("storage", r"\b(storage|disk|space)\b"), ("temperature", r"\b(temperature|hot|temp)\b")]

def match(text):
    t = normalize(text)
    proj = project_in(t)

    if m := re.fullmatch(r"open (.+?) in (vs ?code|visual studio code|code)", t):
        return "open_vscode", {"path": m[1].strip()}
    if proj and (m := re.search(r"\b(run|start)? ?(?:the )?(tests?|build|dev server)\b", t)) and not re.search(r"\bgit\b", t):
        task = {"test": "test", "tests": "test", "build": "build", "dev server": "dev"}[m[2]]
        return "run_project", {"project": proj, "task": task}
    if proj and re.fullmatch(rf"build {proj}", t):
        return "run_project", {"project": proj, "task": "build"}
    if proj:
        if re.search(r"\bgit status\b|\bstatus (of|for)\b", t):
            return "git", {"project": proj, "action": "status"}
        if re.search(r"\b(recent )?commits\b|\bgit log\b", t) and "commit " + proj not in t:
            return "git", {"project": proj, "action": "log"}
        if re.search(r"\bgit diff\b|\bwhat changed\b", t):
            return "git", {"project": proj, "action": "diff"}
        if re.search(r"\bpull\b", t):
            return "git", {"project": proj, "action": "pull"}
        if re.search(r"^push\b|\bgit push\b", t):
            return "git", {"project": proj, "action": "push"}
    if m := re.fullmatch(r"(?:open|launch|start) (?:the )?(.+?)(?: app)?(?: on (?:my |the )?(phone|laptop|computer))?", t):
        if not re.search(r"\b(server|timer|tests?|build)\b", m[1]):
            return "open_app", {"name": m[1], "device": device_of(m[2] or "", "phone")}
    if m := re.search(r"\bbrightness\b.*?(\d{1,3})|(\d{1,3}) ?%? ?(?:percent )?brightness", t):
        return "set_brightness", {"level": min(100, int(m[1] or m[2]))}
    if re.search(r"\b(max|full|maximum)\b.*\bbright|\bas bright as possible\b", t):
        return "set_brightness", {"level": 100}
    if m := re.search(r"\bvolume\b.*?(\d{1,3})", t):
        return "set_volume", {"level": min(100, int(m[1]))}
    if re.fullmatch(r"mute(?: the)?(?: phone)?|silence(?: the)? phone", t):
        return "set_volume", {"level": 0}
    if m := re.search(r"\btimer for (\d+) (minute|hour|second)s?(?: for (.+))?", t) or \
            re.search(r"\bremind me in (\d+) (minute|hour|second)s?(?: to (.+))?", t):
        n = int(m[1]); minutes = n * 60 if m[2] == "hour" else max(1, round(n / 60)) if m[2] == "second" else n
        return "set_timer", {"minutes": minutes, "label": m[3] or ""}
    if m := re.fullmatch(r"remember(?: that)? (.+)", t):
        return "remember", {"fact": m[1]}
    if m := re.search(r"what (?:did i tell you|do you (?:know|remember)) about (.+)", t):
        return "recall", {"query": m[1]}
    if m := re.fullmatch(r"(?:kill|force close|force quit) (.+?)(?: on the laptop)?", t):
        return "kill_process", {"name": m[1]}
    if len(t.split()) <= 9:  # short status questions only; longer sentences go to the LLM
        for metric, pat in METRICS:
            if re.search(pat, t):
                default = "phone" if metric in ("battery", "storage") else "laptop"
                return "get_status", {"metric": metric, "device": device_of(t, default)}
    return None
