"""SQLite: long-term memory (FTS5), the task planner and the append-only audit log."""
import difflib, json, re, sqlite3, threading, time
from . import DATA

class Store:
    def __init__(self, path=None):
        DATA.mkdir(exist_ok=True)
        self.db = sqlite3.connect(path or DATA / "frosti.db", check_same_thread=False)
        self.lock = threading.Lock()
        self.db.executescript("""
            CREATE VIRTUAL TABLE IF NOT EXISTS facts USING fts5(fact, ts UNINDEXED);
            CREATE TABLE IF NOT EXISTS tasks(text TEXT, ts REAL);
            CREATE TABLE IF NOT EXISTS audit(ts REAL, source TEXT, tool TEXT, args TEXT, tier INT, approved INT, result TEXT);
            CREATE TRIGGER IF NOT EXISTS audit_no_update BEFORE UPDATE ON audit BEGIN SELECT RAISE(ABORT, 'audit is append-only'); END;
            CREATE TRIGGER IF NOT EXISTS audit_no_delete BEFORE DELETE ON audit BEGIN SELECT RAISE(ABORT, 'audit is append-only'); END;
        """)

    def remember(self, fact):
        with self.lock, self.db:
            self.db.execute("INSERT INTO facts VALUES (?, ?)", (fact, time.time()))

    def recall(self, query, k=3):
        words = [w for w in re.findall(r"\w+", query.lower()) if len(w) > 2]
        if not words:
            return []
        # prefix match so "theme" finds "themes"
        q = " OR ".join(f'"{w.rstrip("s")}"*' for w in words)
        with self.lock:
            return [r[0] for r in self.db.execute("SELECT fact FROM facts WHERE facts MATCH ? ORDER BY rank LIMIT ?", (q, k))]

    def facts(self, k=100):
        with self.lock:
            return [dict(id=r[0], fact=r[1], ts=r[2]) for r in
                    self.db.execute("SELECT rowid, fact, ts FROM facts ORDER BY ts DESC LIMIT ?", (k,))]

    def forget(self, rowid):
        with self.lock, self.db:
            return self.db.execute("DELETE FROM facts WHERE rowid = ?", (int(rowid),)).rowcount

    def add_task(self, text):
        with self.lock, self.db:
            self.db.execute("INSERT INTO tasks VALUES (?, ?)", (text, time.time()))

    def tasks(self):
        with self.lock:
            return [dict(id=r[0], text=r[1], ts=r[2]) for r in self.db.execute("SELECT rowid, text, ts FROM tasks ORDER BY ts")]

    def finish_task(self, text):
        """Deletes the open task that best matches text (finished tasks aren't kept). Returns its text, or None."""
        tasks = {t["text"]: t["id"] for t in self.tasks()}
        t = text.lower()
        hit = next((k for k in tasks if t == k.lower()), None) or next((k for k in tasks if t in k.lower()), None)             or next(iter(difflib.get_close_matches(text, list(tasks), 1, 0.6)), None)
        if hit:
            with self.lock, self.db:
                self.db.execute("DELETE FROM tasks WHERE rowid = ?", (tasks[hit],))
        return hit

    def recent(self, k=20):
        with self.lock:
            return [dict(ts=r[0], source=r[1], tool=r[2], args=r[6], tier=r[3], approved=bool(r[4]), result=r[5][:200]) for r in
                    self.db.execute("SELECT ts, source, tool, tier, approved, result, args FROM audit ORDER BY ts DESC, rowid DESC LIMIT ?", (k,))]

    def audit(self, source, tool, args, tier, approved, result):
        with self.lock, self.db:
            self.db.execute("INSERT INTO audit VALUES (?,?,?,?,?,?,?)",
                            (time.time(), source, tool, json.dumps(args), tier, int(approved), str(result)[:2000]))
