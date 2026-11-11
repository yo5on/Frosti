"""FROSTI Core: the laptop brain (LLM, fast path, tools, memory, voice, phone gateway)."""
import tomllib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]          # E:\frosti
DATA = ROOT / "data"                                  # git-ignored runtime state
CONFIG = tomllib.loads((ROOT / "core" / "config" / "frosti.toml").read_text(encoding="utf-8"))
