"""Speech on the laptop (sherpa-onnx 1.13.8): Parakeet ASR, Piper TTS, KWS wake word, Silero VAD.

The phone streams 16 kHz mono int16 PCM; Listener turns that stream into utterances.
"""
import io, wave
from pathlib import Path
import numpy as np
import sherpa_onnx
from . import CONFIG, DATA

SR = 16000

def _one(d, pattern):
    hits = sorted(Path(d).glob(pattern))
    if not hits:
        raise FileNotFoundError(f"{pattern} in {d}")
    return str(hits[0])

def _dir(root, pattern):
    hits = [d for d in sorted(Path(root).glob(pattern)) if d.is_dir()]
    if not hits:
        raise FileNotFoundError(f"model folder {pattern} in {root} (run scripts/fetch-models.ps1)")
    return hits[0]

class Voice:
    def __init__(self):
        c = CONFIG["speech"]; m = Path(c["models"]); th = c["threads"]
        p = _dir(m, "*parakeet*")
        self.asr = sherpa_onnx.OfflineRecognizer.from_transducer(
            encoder=_one(p, "encoder*.onnx"), decoder=_one(p, "decoder*.onnx"), joiner=_one(p, "joiner*.onnx"),
            tokens=str(p / "tokens.txt"), model_type="nemo_transducer", num_threads=th)
        v = m / c["voice"]
        self.tts = sherpa_onnx.OfflineTts(sherpa_onnx.OfflineTtsConfig(model=sherpa_onnx.OfflineTtsModelConfig(
            vits=sherpa_onnx.OfflineTtsVitsModelConfig(model=_one(v, "*.onnx"), tokens=str(v / "tokens.txt"),
                                                      data_dir=str(v / "espeak-ng-data")), num_threads=th)))
        k = _dir(m, "*kws-zipformer-gigaspeech*")
        kw = DATA / "keywords.txt"; DATA.mkdir(exist_ok=True)
        toks = sherpa_onnx.text2token(["HEY SPARKY"], tokens=str(k / "tokens.txt"), tokens_type="bpe",
                                      bpe_model=str(k / "bpe.model"))
        kw.write_text(" ".join(toks[0]) + " @HEY_SPARKY\n", encoding="utf-8")
        self.kws = sherpa_onnx.KeywordSpotter(
            tokens=str(k / "tokens.txt"), encoder=_one(k, "encoder*int8.onnx"), decoder=_one(k, "decoder*.onnx"),
            joiner=_one(k, "joiner*int8.onnx"), keywords_file=str(kw), num_threads=1,
            keywords_threshold=0.25, keywords_score=1.0)
        self.vad_cfg = sherpa_onnx.VadModelConfig()
        self.vad_cfg.silero_vad.model = str(m / "silero_vad.onnx")
        self.vad_cfg.silero_vad.min_silence_duration = 0.6
        self.vad_cfg.silero_vad.min_speech_duration = 0.25
        self.vad_cfg.silero_vad.max_speech_duration = 12
        self.vad_cfg.sample_rate = SR

    def transcribe(self, x):
        s = self.asr.create_stream(); s.accept_waveform(SR, x)
        self.asr.decode_stream(s)
        return s.result.text.strip()

    def speak(self, text):
        """-> WAV bytes (16-bit mono)."""
        a = self.tts.generate(text, sid=0, speed=1.0)
        pcm = (np.clip(np.array(a.samples), -1, 1) * 32767).astype("<i2").tobytes()
        buf = io.BytesIO()
        with wave.open(buf, "wb") as w:
            w.setnchannels(1); w.setsampwidth(2); w.setframerate(a.sample_rate); w.writeframes(pcm)
        return buf.getvalue()

class Listener:
    """Per-connection mic state machine.
    wake: waiting for "hey sparky" | capture: VAD collecting one utterance | ptt: push-to-talk buffer | busy: ignore (we're talking)
    feed() returns ("wake", None), ("utterance", float32 audio), ("timeout", None) or None.
    """
    def __init__(self, voice):
        self.v = voice
        self.kws_stream = voice.kws.create_stream()
        self.vad = sherpa_onnx.VoiceActivityDetector(voice.vad_cfg, buffer_size_in_seconds=30)
        self.mode, self.buf, self.waited = "wake", [], 0.0
        self.capture_limit = 8.0

    def listen(self, seconds=8.0):  # open the mic without the wake word (after a wake hit, or a follow-up window)
        self.vad.reset(); self.mode, self.waited, self.capture_limit = "capture", 0.0, seconds

    def ptt(self, on):
        if on:
            self.mode, self.buf = "ptt", []
            return None
        audio = np.concatenate(self.buf) if self.buf else np.zeros(0, np.float32)
        self.mode, self.buf = "busy", []
        return ("utterance", audio) if len(audio) > SR * 0.3 else ("timeout", None)

    def feed(self, pcm_bytes):
        x = np.frombuffer(pcm_bytes, "<i2").astype(np.float32) / 32768
        if self.mode == "ptt":
            self.buf.append(x)
        elif self.mode == "wake":
            self.kws_stream.accept_waveform(SR, x)
            while self.v.kws.is_ready(self.kws_stream):
                self.v.kws.decode_stream(self.kws_stream)
                if self.v.kws.get_result(self.kws_stream):
                    self.v.kws.reset_stream(self.kws_stream)
                    self.listen()
                    return ("wake", None)
        elif self.mode == "capture":
            self.vad.accept_waveform(x)
            if not self.vad.empty():
                seg = np.array(self.vad.front.samples, np.float32)
                self.vad.pop()
                self.mode = "busy"
                return ("utterance", seg)
            self.waited += len(x) / SR
            if self.waited > self.capture_limit and not self.vad.is_speech_detected():
                self.mode = "wake"
                return ("timeout", None)
        return None

    def idle(self):  # back to waiting for the wake word
        self.mode = "wake"
        self.kws_stream = self.v.kws.create_stream()
