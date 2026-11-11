import json
import sys
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from frosti_core.gateway import PhoneSession


class WakeReply(unittest.IsolatedAsyncioTestCase):
    async def test_wake_uses_cached_audio_and_waits_for_playback(self):
        frames = []
        class Socket:
            async def send(self, frame): frames.append(frame)
        session = PhoneSession(Socket(), SimpleNamespace(sessions=set()), None)
        session.voice = SimpleNamespace(wake_audio=b'cached Bella', speak=Mock(side_effect=AssertionError('Re-synthesized wake reply')))
        session.listener = SimpleNamespace(mode='wake')
        for _ in range(2):
            await session.on_audio_event(('wake', None))
        self.assertEqual(frames.count(b'cached Bella'), 2)
        self.assertEqual(frames.count(json.dumps(dict(type='audio_end'))), 2)
        self.assertEqual(session.listener.mode, 'busy')
        self.assertEqual(json.loads(frames[0]), dict(type='state', state='speaking'))
        session.voice.speak.assert_not_called()

    async def test_end_marker_follows_all_sentences(self):
        frames = []
        class Socket:
            async def send(self, frame): frames.append(frame)
        session = PhoneSession(Socket(), SimpleNamespace(sessions=set()), None)
        session.voice = SimpleNamespace(wake_audio=b'cached', speak=lambda text: text.encode())
        await session.say('First. Second.')
        self.assertEqual(frames, [json.dumps(dict(type='state', state='speaking')),
                                 b'First.', b'Second.', json.dumps(dict(type='audio_end'))])
