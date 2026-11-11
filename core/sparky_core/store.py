"""SQLite: long-term memory (FTS5) and the append-only audit log."""
import json, re, sqlite3, threading, time
from . import DATA

class Store:
    def __init__(self, path=None):
        DATA.mkdir(exist_ok=True)
        self.db = sqlite3.connect(path or DATA / "sparky.db", check_same_thread=False)
        self.lock = threading.Lock()
        self.db.executescript("""
            CREATE VIRTUAL TABLE IF NOT EXISTS facts USING fts5(fact, ts UNINDEXED);
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

    def audit(self, source, tool, args, tier, approved, result):
        with self.lock, self.db:
            self.db.execute("INSERT INTO audit VALUES (?,?,?,?,?,?,?)",
                            (time.time(), source, tool, json.dumps(args), tier, int(approved), str(result)[:2000]))
