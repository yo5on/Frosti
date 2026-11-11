"""Phase 0 benchmark: offline TTS, ASR and wake-word speed/accuracy with sherpa-onnx.

TTS speaks a set of SPARKY commands; those clips (resampled to 16 kHz) are the ASR test set.
shortcut: synthetic speech flatters ASR accuracy; re-run with real recordings from the J7 Pro mic in Phase 2.

usage: .venv/Scripts/python.exe bench/speech.py [--models D:/sparky-models/speech] [--threads 4]
"""
import argparse, json, re, time
from pathlib import Path
import numpy as np
import sherpa_onnx
import soundfile as sf

COMMANDS = [
    "Open Spotify on my phone.",
    "What is my laptop RAM usage?",
    "Set brightness to forty percent.",
    "Run the tests for sparky.",
    "Show me the recent commits in sparky.",
    "Set a timer for ten minutes for tea.",
    "Remember that I prefer dark themes.",
    "Kill chrome on the laptop.",
    "What's on my laptop screen right now?",
    "Good evening, Sparky. How are the systems looking?",
]
REPLY = ("Good evening. All systems are nominal. The laptop is at forty two percent memory, "
         "and your last build passed. Shall I open the project?")

def one(root, pattern):
    hits = sorted(root.glob(pattern))
    return str(hits[0]) if hits else None

def norm(s):
    s = s.lower().replace("'", "")
    nums = {"forty": "40", "ten": "10", "forty two": "42"}
    for w, n in nums.items():
        s = re.sub(rf"\b{w}\b", n, s)
    return re.sub(r"[^a-z0-9 ]", " ", s).split()

def wer(ref, hyp):
    r, h = norm(ref), norm(hyp)
    d = list(range(len(h) + 1))
    for i in range(1, len(r) + 1):
        prev, d[0] = d[0], i
        for j in range(1, len(h) + 1):
            cur = min(d[j] + 1, d[j - 1] + 1, prev + (r[i - 1] != h[j - 1]))
            prev, d[j] = d[j], cur
    return d[len(h)] / max(1, len(r))

def resample16k(x, sr):
    if sr == 16000:
        return x.astype(np.float32)
    n = int(len(x) * 16000 / sr)
    return np.interp(np.linspace(0, len(x) - 1, n), np.arange(len(x)), x).astype(np.float32)

# ---------- TTS ----------
def tts_engines(m, threads):
    eng = {}
    for d in sorted(m.glob("vits-piper-*")):
        cfg = sherpa_onnx.OfflineTtsConfig(model=sherpa_onnx.OfflineTtsModelConfig(
            vits=sherpa_onnx.OfflineTtsVitsModelConfig(model=one(d, "*.onnx"), tokens=str(d / "tokens.txt"),
                                                      data_dir=str(d / "espeak-ng-data")),
            num_threads=threads))
        eng[d.name] = sherpa_onnx.OfflineTts(cfg)
    k = next(iter(sorted(m.glob("kokoro-en*"))), None)
    if k:
        cfg = sherpa_onnx.OfflineTtsConfig(model=sherpa_onnx.OfflineTtsModelConfig(
            kokoro=sherpa_onnx.OfflineTtsKokoroModelConfig(model=str(k / "model.onnx"), voices=str(k / "voices.bin"),
                                                          tokens=str(k / "tokens.txt"), data_dir=str(k / "espeak-ng-data")),
            num_threads=threads))
        eng[k.name] = sherpa_onnx.OfflineTts(cfg)
    return eng

def bench_tts(eng, out):
    res = {}
    for name, tts in eng.items():
        tts.generate("Warm up.", sid=0, speed=1.0)
        t = time.time(); a = tts.generate(REPLY.split(".")[0] + ".", sid=0, speed=1.0); first = time.time() - t
        t = time.time(); a = tts.generate(REPLY, sid=0, speed=1.0); full = time.time() - t
        dur = len(a.samples) / a.sample_rate
        sf.write(out / f"tts_{name}.wav", a.samples, a.sample_rate)
        res[name] = dict(first_sentence_s=round(first, 2), full_s=round(full, 2), audio_s=round(dur, 2),
                         rtf=round(full / dur, 3))
        print(f"TTS {name:55} first={first:.2f}s rtf={full / dur:.3f}", flush=True)
    return res

# ---------- ASR ----------
def asr_engines(m, threads):
    eng = {}
    p = next(iter(sorted(m.glob("*parakeet*"))), None)
    if p:
        eng[p.name] = sherpa_onnx.OfflineRecognizer.from_transducer(
            encoder=one(p, "encoder*.onnx"), decoder=one(p, "decoder*.onnx"), joiner=one(p, "joiner*.onnx"),
            tokens=str(p / "tokens.txt"), model_type="nemo_transducer", num_threads=threads)
    mo = next(iter(sorted(m.glob("*moonshine*"))), None)
    if mo:
        try:
            eng[mo.name] = sherpa_onnx.OfflineRecognizer.from_moonshine(
                preprocessor=one(mo, "preprocess*.onnx"), encoder=one(mo, "encode*.onnx"),
                uncached_decoder=one(mo, "uncached_decode*.onnx"), cached_decoder=one(mo, "cached_decode*.onnx"),
                tokens=str(mo / "tokens.txt"), num_threads=threads)
        except Exception as e:  # newer moonshine packaging may need a different loader
            print(f"skip {mo.name}: {e}")
    return eng

def streaming_engine(m, threads):
    z = next(iter(sorted(m.glob("*streaming-zipformer-en-20M*"))), None)
    if not z:
        return None, None
    return z.name, sherpa_onnx.OnlineRecognizer.from_transducer(
        tokens=str(z / "tokens.txt"), encoder=one(z, "encoder*int8.onnx") or one(z, "encoder*.onnx"),
        decoder=one(z, "decoder*.onnx"), joiner=one(z, "joiner*int8.onnx") or one(z, "joiner*.onnx"),
        num_threads=threads, decoding_method="greedy_search")

def bench_asr(eng, clips):
    res = {}
    for name, rec in eng.items():
        tot_audio = tot_t = 0; errs = []; worst = []
        for text, x in clips:
            s = rec.create_stream(); s.accept_waveform(16000, x)
            t = time.time(); rec.decode_stream(s); dt = time.time() - t
            hyp = s.result.text; e = wer(text, hyp); errs.append(e)
            tot_audio += len(x) / 16000; tot_t += dt
            if e > 0: worst.append(f"{text} -> {hyp}")
        res[name] = dict(wer=round(float(np.mean(errs)), 3), rtf=round(tot_t / tot_audio, 3),
                         avg_latency_s=round(tot_t / len(clips), 2), errors=worst[:5])
        print(f"ASR {name:55} wer={res[name]['wer']:.3f} rtf={res[name]['rtf']:.3f}", flush=True)
    return res

def bench_streaming(name, rec, clips):
    tot_audio = tot_t = 0; errs = []; worst = []
    for text, x in clips:
        s = rec.create_stream(); t = time.time()
        for i in range(0, len(x), 1600):  # 100 ms chunks like a live mic
            s.accept_waveform(16000, x[i:i + 1600])
            while rec.is_ready(s): rec.decode_stream(s)
        s.accept_waveform(16000, np.zeros(8000, np.float32)); s.input_finished()
        while rec.is_ready(s): rec.decode_stream(s)
        tot_t += time.time() - t; tot_audio += len(x) / 16000
        hyp = rec.get_result(s); e = wer(text, hyp); errs.append(e)
        if e > 0: worst.append(f"{text} -> {hyp}")
    r = dict(wer=round(float(np.mean(errs)), 3), rtf=round(tot_t / tot_audio, 3), errors=worst[:5])
    print(f"ASR-stream {name:48} wer={r['wer']:.3f} rtf={r['rtf']:.3f}", flush=True)
    return {name: r}

# ---------- KWS ----------
def bench_kws(m, threads, tts, out):
    k = next(iter(sorted(m.glob("*kws-zipformer-gigaspeech*"))), None)
    if not k:
        return {}
    kw_file = out / "keywords.txt"
    toks = sherpa_onnx.text2token(["HEY SPARKY"], tokens=str(k / "tokens.txt"), tokens_type="bpe",
                                  bpe_model=str(k / "bpe.model"))
    kw_file.write_text(" ".join(toks[0]) + " @HEY_SPARKY\n")
    spot = sherpa_onnx.KeywordSpotter(
        tokens=str(k / "tokens.txt"), encoder=one(k, "encoder*int8.onnx"), decoder=one(k, "decoder*.onnx"),
        joiner=one(k, "joiner*int8.onnx"), keywords_file=str(kw_file), num_threads=1,
        keywords_threshold=0.25, keywords_score=1.0)

    def detect(text):
        a = tts.generate(text, sid=0, speed=1.0); x = resample16k(np.array(a.samples), a.sample_rate)
        x = np.concatenate([np.zeros(8000, np.float32), x, np.zeros(16000, np.float32)])
        s = spot.create_stream(); hit = False; t = time.time()
        for i in range(0, len(x), 1600):
            s.accept_waveform(16000, x[i:i + 1600])
            while spot.is_ready(s):
                spot.decode_stream(s)
                if spot.get_result(s): hit = True; spot.reset_stream(s)
        return hit, (time.time() - t) / (len(x) / 16000)

    pos = ["Hey Sparky.", "Hey Sparky, open Spotify.", "Hey, Sparky!"]
    neg = ["Hey Siri.", "The party was sparkly.", "Hey, how are you?", "Open Spotify on my phone."]
    p = [detect(t) for t in pos]; n = [detect(t) for t in neg]
    r = dict(detected=f"{sum(h for h, _ in p)}/{len(pos)}", false_alarms=f"{sum(h for h, _ in n)}/{len(neg)}",
             rtf_1thread=round(float(np.mean([x for _, x in p + n])), 4))
    print(f"KWS {k.name}: {r}", flush=True)
    return {k.name: r}

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--models", default="D:/sparky-models/speech")
    ap.add_argument("--threads", type=int, default=4)
    a = ap.parse_args()
    m, out = Path(a.models), Path(__file__).parent / "out"; out.mkdir(exist_ok=True)

    tts = tts_engines(m, a.threads)
    result = {"tts": bench_tts(tts, out)}

    voice = tts[next(n for n in tts if "piper" in n)]  # ASR test clips voiced by Piper
    clips = []
    for c in COMMANDS:
        g = voice.generate(c, sid=0, speed=1.0)
        clips.append((c, resample16k(np.array(g.samples), g.sample_rate)))

    result["asr"] = bench_asr(asr_engines(m, a.threads), clips)
    name, srec = streaming_engine(m, 2)  # 2 threads ~ what the phone can spare
    if srec:
        result["asr_streaming_2threads"] = bench_streaming(name, srec, clips)
    result["kws"] = bench_kws(m, a.threads, voice, out)
    (out / "speech.json").write_text(json.dumps(result, indent=2))
    print(json.dumps(result, indent=2))

if __name__ == "__main__":
    main()
