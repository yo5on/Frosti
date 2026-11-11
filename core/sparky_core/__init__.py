"""SPARKY Core: the laptop brain (LLM, fast path, tools, memory, voice, phone gateway)."""
import tomllib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]          # E:\sparky
DATA = ROOT / "data"                                  # git-ignored runtime state
CONFIG = tomllib.loads((ROOT / "core" / "config" / "sparky.toml").read_text(encoding="utf-8"))
