"""Generate synthetic audio for the on-phone KWS test. Does not record/play any audio."""
import sys
from pathlib import Path
import numpy as np

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'core'))
from frosti_core.voice import Voice, SR

voice = Voice()
directory = ROOT / 'data' / 'wake-tests'
directory.mkdir(exist_ok=True)
for name, text in [('frosti', 'Frosti.'), ('hey-frosti', 'Hey Frosti.'),
                   ('unrelated', 'Open Spotify on my phone.'), ('other-name', 'Hey Siri.'),
                   ('cmd-battery', "What's my battery?"), ('cmd-youtube', 'Open YouTube.'),
                   ('cmd-volume', 'Volume up.')]:
    audio = voice.tts.generate(text, sid=voice.speaker, speed=1.0)
    samples = np.array(audio.samples, np.float32)
    samples = np.interp(np.arange(round(len(samples) * SR / audio.sample_rate)) * audio.sample_rate / SR,
                        np.arange(len(samples)), samples).astype(np.float32)
    samples = np.concatenate([np.zeros(SR // 2, np.float32), samples, np.zeros(SR, np.float32)])
    (directory / f'{name}.pcm').write_bytes((np.clip(samples, -1, 1) * 32767).astype('<i2').tobytes())
print('On-phone test audio prepared.')
