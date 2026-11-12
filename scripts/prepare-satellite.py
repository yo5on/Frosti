"""Tokenize Satellite-mode (laptop offline) phone commands for the on-phone keyword spotter.

Writes core/config/satellite-commands.txt; the Shell build copies it into the app's assets.
Each label is `tool=arg`, which the phone runs through Actions (or Satellite for `time`/`volume_step`).
Run: .venv/Scripts/python scripts/prepare-satellite.py
"""
from pathlib import Path
import sherpa_onnx

KWS = Path("D:/frosti-models/speech/sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01")
OUT = Path(__file__).resolve().parent.parent / "core/config/satellite-commands.txt"

COMMANDS = {
    "battery": "get_status=battery", "what's my battery": "get_status=battery",
    "storage": "get_status=storage", "temperature": "get_status=temperature",
    "what time is it": "time=",
    "volume up": "volume_step=1", "louder": "volume_step=1",
    "volume down": "volume_step=-1", "quieter": "volume_step=-1", "mute": "set_volume=0",
    "brightness up": "set_brightness=100", "brightness down": "set_brightness=20",
    "play music": "media_control=play", "play": "media_control=play",
    "pause": "media_control=pause", "stop": "media_control=pause",
    "next song": "media_control=next", "skip": "media_control=next",
    "previous song": "media_control=previous",
}
for app in ["youtube", "spotify", "camera", "settings", "chrome", "whatsapp", "gallery", "phone",
            "messages", "clock", "calculator", "calendar", "maps"]:
    COMMANDS[f"open {app}"] = f"open_app={app}"

for word, minutes in [("one", 1), ("two", 2), ("three", 3), ("five", 5), ("ten", 10),
                      ("fifteen", 15), ("twenty", 20), ("twenty five", 25), ("thirty", 30),
                      ("forty five", 45), ("sixty", 60)]:
    COMMANDS[f"{word} minute timer"] = f"set_timer={minutes}"
    COMMANDS[f"set a timer for {word} minutes"] = f"set_timer={minutes}"
COMMANDS["show timers"] = "show_timers="

KNOWN = {"get_status", "time", "volume_step", "set_volume", "set_brightness", "media_control", "open_app", "set_timer", "show_timers"}
assert all(v.split("=")[0] in KNOWN for v in COMMANDS.values())
assert COMMANDS["five minute timer"] == "set_timer=5"
assert COMMANDS["twenty five minute timer"] == "set_timer=25"

phrases = list(COMMANDS)
tokens = sherpa_onnx.text2token([p.upper() for p in phrases], tokens=str(KWS / "tokens.txt"),
                                tokens_type="bpe", bpe_model=str(KWS / "bpe.model"))
OUT.write_text("".join(f"{' '.join(t)} @{COMMANDS[p].replace(' ', '_')}\n" for p, t in zip(phrases, tokens)),
               encoding="utf-8")
print(f"{len(phrases)} commands -> {OUT}")
