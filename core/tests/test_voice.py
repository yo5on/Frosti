"""Wake-word check using installed speech models, without playing or recording audio."""
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from frosti_core import CONFIG


@unittest.skipUnless(Path(CONFIG['speech']['models']).exists(), 'Speech models not installed')
class WakeWord(unittest.TestCase):
    def test_name_and_greeting_wake_but_unrelated_speech_does_not(self):
        import numpy as np
        from frosti_core.voice import Voice, Listener, SR
        voice = Voice()
        for text, expected in [('Frosti.', True), ('Hey Frosti.', True),
                               ('Open Spotify on my phone.', False), ('Hey Siri.', False)]:
            with self.subTest(text=text):
                audio = voice.tts.generate(text, sid=voice.speaker, speed=1.0)
                samples = np.array(audio.samples, dtype=np.float32)
                samples = np.interp(np.arange(round(len(samples) * SR / audio.sample_rate)) *
                                    audio.sample_rate / SR, np.arange(len(samples)), samples).astype(np.float32)
                samples = np.concatenate([np.zeros(SR // 2, np.float32), samples, np.zeros(SR, np.float32)])
                listener, woke = Listener(voice), False
                for i in range(0, len(samples), 1600):
                    pcm = (np.clip(samples[i:i + 1600], -1, 1) * 32767).astype('<i2').tobytes()
                    event = listener.feed(pcm)
                    if event and event[0] == 'wake':
                        woke = True
                        break
                self.assertEqual(woke, expected)
