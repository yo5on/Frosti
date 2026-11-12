"""Fast path: deterministic rules for common commands, the only way FROSTI understands a command.

match(text) -> (tool_name, args) or None. Anything else returns None and gets a "didn't catch that".
"""
import re
from datetime import datetime, timedelta
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
    t = re.sub(r"^(hey,? )?frosti[,.!]?\s*", "", t)
    t = re.sub(r"[?!.,]+$|[,]", "", t)
    t = re.sub(r"\b(please|can you|could you|would you|for me)\b", "", t)
    return re.sub(r"\s+", " ", words_to_numbers(t)).strip()

def device_of(t, default):
    return "phone" if "phone" in t else "laptop" if re.search(r"\b(laptop|computer|pc)\b", t) else default

def project_in(t):
    return next((p for p in PROJECTS if re.search(rf"\b{re.escape(p)}\b", t)), None)

METRICS = [("battery", r"\bbattery\b"), ("ram", r"\b(ram|memory)\b(?! about)"), ("cpu", r"\b(cpu|processor)\b"),
           ("storage", r"\b(storage|disk|space)\b"), ("temperature", r"\b(temperature|hot|temp)\b")]

def minutes_until(h, mi, now=None):
    """Whole minutes from now to the next h:mi (rounded up, at least 1)."""
    now = now or datetime.now()
    at = now.replace(hour=h, minute=mi, second=0, microsecond=0)
    if at <= now:
        at += timedelta(days=1)
    return max(1, -(-int((at - now).total_seconds()) // 60))

def alarm(h, mi, ampm, now=None):
    if ampm.startswith("p") or ampm in ("in the evening", "tonight"):
        hours = [h % 12 + 12]
    elif ampm.startswith("a") or ampm == "in the morning":
        hours = [h % 12]
    else:  # "alarm at 7": the sooner of 7:00 and 19:00
        hours = [h % 12, h % 12 + 12] if 1 <= h <= 12 else [h]
    h = min(hours, key=lambda x: minutes_until(x, mi, now))
    return "set_timer", {"minutes": minutes_until(h, mi, now), "label": f"Alarm {h:02d}:{mi:02d}"}

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
    if m := re.fullmatch(r"(?:open|launch|start) (?:the )?(.+?)(?: app)?(?: (?:on|in) (?:my |the )?(phone|laptop|computer|pc))?", t):
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
    ampm = r"(?: o'?clock)? ?(am|pm|a\.?m\.?|p\.?m\.?|in the morning|in the evening|tonight)?"
    if m := re.fullmatch(rf"(?:set (?:an |the |my )?)?alarm (?:at|for) (\d{{1,2}})(?:[:. ](\d{{2}}))?{ampm}|wake me(?: up)? at (\d{{1,2}})(?:[:. ](\d{{2}}))?{ampm}", t):
        h, mi = int(m[1] or m[4]), int(m[2] or m[5] or 0)
        if h <= 23 and mi <= 59:
            return alarm(h, mi, (m[3] or m[6] or "").replace(".", ""))
    if m := re.search(r"\btimer for (\d+) (minute|hour|second)s?(?: for (.+))?", t) or \
            re.search(r"\bremind me in (\d+) (minute|hour|second)s?(?: to (.+))?", t):
        n = int(m[1]); minutes = n * 60 if m[2] == "hour" else max(1, round(n / 60)) if m[2] == "second" else n
        return "set_timer", {"minutes": minutes, "label": m[3] or ""}
    if "phone" not in t:
        pc = r"(?:(?:the |my )?(?:laptop|computer|pc|system|it) ?)?"
        if re.search(rf"\b(cancel|abort|stop|don't|do not)\b.*\bshut ?down\b", t):
            return "power", {"action": "cancel_shutdown"}
        m = re.fullmatch(rf"(?:shut ?down|turn off|switch off|power off) ?{pc}(?:(?:in|after) (\d+) ?(minute|min|hour|hr|second|sec)s?)?(?: from now)?", t)
        # a bare "turn off" is too vague to mean the laptop: needs "shut down", a device, or a time
        if m and (t.startswith("shut") or m[1] or re.search(r"\b(laptop|computer|pc|system)\b", t)):
            n = int(m[1] or 0); unit = m[2] or "minute"
            return "power", {"action": "shutdown", "minutes": n * 60 if unit in ("hour", "hr") else max(1, round(n / 60)) if unit in ("second", "sec") else n}
        if re.search(rf"^(?:keep|leave) {pc}(?:on|awake|running)\b|\bdon't let {pc}(?:go to )?sleep\b|\b(?:stop|prevent) {pc}(?:from )?sleeping\b", t):
            if m := re.search(r"\b(?:until|till) (?:the )?(?:process )?(.+?)(?: process)? (?:finishes|completes|exits|ends|is done)$", t):
                if m[1] not in ("work", "the work", "it", "a process", "process"):
                    return "power", {"action": "keep_awake", "process": m[1]}
            return "power", {"action": "keep_awake"}
        if re.search(rf"^(?:let|allow) {pc}(?:to )?sleep\b|\ballow sleep\b", t):
            return "power", {"action": "allow_sleep"}
        if re.fullmatch(rf"put {pc}to sleep(?: now)?|(?:go to )?sleep(?: now)?|sleep {pc}(?:now)?", t):
            return "power", {"action": "sleep"}
    if m := re.fullmatch(r"(?:add|new|create)(?: a)? task (.+)|add (.+?) to (?:my |the )?(?:tasks|task list|to ?do(?: list)?|planner)", t):
        return "tasks", {"action": "add", "text": m[1] or m[2]}
    if re.fullmatch(r"(?:what are|show|list|read)(?: me)? (?:my |the )?(?:tasks|task list|to ?dos|to ?do list|planner)|my tasks|what's on my (?:list|to ?do list|planner)", t):
        return "tasks", {"action": "list"}
    if m := re.fullmatch(r"(?:mark|tick off|check off) (?:the )?(?:task )?(.+?)(?: as)?(?: done| complete| completed| finished)?|(?:complete|finish|done with)(?: the)? task (.+)", t):
        return "tasks", {"action": "done", "text": m[1] or m[2]}
    if re.fullmatch(r"what(?:'s| is) running(?: on (?:the |my )?(?:laptop|computer|pc))?|(?:show|list)(?: me)?(?: the)? (?:running|top) (?:processes|programs|apps)|top processes|what's using (?:the )?(?:cpu|ram|memory)", t):
        return "get_status", {"metric": "processes", "device": "laptop"}
    if m := re.fullmatch(r"remember(?: that)? (.+)", t):
        return "remember", {"fact": m[1]}
    if m := re.search(r"what (?:did i tell you|do you (?:know|remember)) about (.+)", t):
        return "recall", {"query": m[1]}
    if m := re.fullmatch(r"(?:kill|force close|force quit|close|quit|exit) (?:the )?(.+?)(?: app)?(?: on (?:my |the )?(?:laptop|computer|pc))?", t):
        if "phone" not in m[1]:
            return "kill_process", {"name": m[1]}
    on = r"(?: on (?:my |the )?(phone|laptop|computer|pc))?"
    if m := re.fullmatch(rf"(pause|stop|resume|unpause|play)(?: the)?(?: some)? (?:music|song){on}|(pause|resume){on}", t):
        action = "pause" if (m[1] or m[3]) in ("pause", "stop") else "play"
        return "media_control", {"action": action, "device": device_of(m[2] or m[4] or "", "phone")}
    if m := re.fullmatch(rf"(?:play )?(?:the )?(next|previous|last) (?:song|track){on}|skip(?: this)?(?: song| track)?{on}", t):
        action = "next" if not m[1] or m[1] == "next" else "previous"
        return "media_control", {"action": action, "device": device_of(m[2] or m[3] or "", "phone")}
    if m := re.fullmatch(r"(?:search|find|look up)(?: for)? (.+?) (?:on|in) spotify", t):
        return "search_music", {"query": m[1]}
    if m := re.fullmatch(rf"play (.+?) (?:on|in|from|using|with) spotify{on}", t):  # Spotify is installed on the laptop
        return "play_music", {"query": m[1], "device": device_of(m[2] or "", "laptop")}
    if m := re.fullmatch(rf"play (.+?){on}", t):
        return "play_music", {"query": m[1], "device": device_of(m[2] or "", "phone")}
    if len(t.split()) <= 9:  # short status questions only; longer sentences aren't status questions
        for metric, pat in METRICS:
            if re.search(pat, t):
                default = "phone" if metric in ("battery", "storage") else "laptop"
                return "get_status", {"metric": metric, "device": device_of(t, default)}
    return None
