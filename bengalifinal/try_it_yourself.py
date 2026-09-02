"""
Interactive test — type Bengali text yourself, hear it spoken.

Run this from inside the bengalifinal folder:
    python try_it_yourself.py

Type any Bengali sentence and press Enter. It saves each one as a numbered
.wav file in this folder and reports generation time. Type 'exit' or press
Ctrl+C to quit.

TUNING NOTES (these matter for the Android app too -- see INTEGRATION.md)
-------------------------------------------------------------------------
NUM_THREADS  sherpa-onnx defaults to 1 thread, which is ~3x slower than 4 on
             the same model (1055 ms vs 343 ms for a typical sentence). There
             is no downside to raising it on a modern phone.
LENGTH_SCALE controls speaking rate; LOWER = FASTER. It CANNOT be baked into
             the .onnx file -- sherpa-onnx ignores a `length_scale` entry in
             the ONNX metadata (verified by test), so it must be set here in
             the config, or per call via generate(speed=...).
SPEAKER_ID   CRITICAL for this voice specifically: bn_BD-google-medium is a
             16-speaker model (n_speakers=16 in its metadata), unlike every
             other language here which is single-speaker. sid=12 is the one
             verified female speaker (median F0 257 Hz; see README.md for
             how all 16 were checked). Passing sid=0 (the usual default)
             gets a MALE voice. Always pass sid=12 explicitly -- there is no
             way to bake a default speaker into the model file.
"""
import io
import os
import sys
import time

# Force UTF-8 stdin/stdout so text typed in cmd.exe doesn't get mangled.
sys.stdin = io.TextIOWrapper(sys.stdin.buffer, encoding="utf-8")
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8")

import sherpa_onnx
import soundfile as sf

NUM_THREADS = 4
LENGTH_SCALE = 0.85
SPEAKER_ID = 12  # the verified female speaker -- see SPEAKER_ID note above


def find_espeak_data():
    """Locate the espeak-ng-data bundled with the piper-tts wheel."""
    try:
        import piper
        path = os.path.join(os.path.dirname(piper.__file__), "espeak-ng-data")
        if os.path.isdir(path):
            return path
    except ImportError:
        pass
    raise SystemExit(
        "Could not find espeak-ng-data. Install the prebuilt wheel with:\n"
        "    pip install piper-tts"
    )


config = sherpa_onnx.OfflineTtsConfig(
    model=sherpa_onnx.OfflineTtsModelConfig(
        vits=sherpa_onnx.OfflineTtsVitsModelConfig(
            model="bn_BD-google-medium.onnx",
            tokens="bn_BD-google-medium.tokens.txt",
            data_dir=find_espeak_data(),
            length_scale=LENGTH_SCALE,
        ),
        num_threads=NUM_THREADS,
    ),
)

if not config.validate():
    raise ValueError("Config invalid — make sure you're running this from inside the bengalifinal folder")

print("Loading model...")
t0 = time.perf_counter()
tts = sherpa_onnx.OfflineTts(config)
print(f"Loaded in {time.perf_counter() - t0:.2f} sec "
      f"(one-time cost — keep this object alive, don't reload per utterance)")
print("Ready. Type Bengali text and press Enter (type 'exit' to quit).\n")

count = 0
while True:
    try:
        text = input("Text> ").strip()
    except (EOFError, KeyboardInterrupt):
        print("\nBye.")
        break

    if not text:
        continue
    if text.lower() in ("exit", "quit"):
        print("Bye.")
        break

    t0 = time.perf_counter()
    audio = tts.generate(text=text, sid=SPEAKER_ID, speed=1.0)
    gen = time.perf_counter() - t0

    count += 1
    out_path = f"my_test_{count}.wav"
    sf.write(out_path, audio.samples, samplerate=audio.sample_rate)
    duration = len(audio.samples) / audio.sample_rate
    print(f"  -> saved {out_path} ({duration:.2f} sec audio, "
          f"generated in {gen * 1000:.0f} ms, RTF {gen / duration:.3f})\n")
