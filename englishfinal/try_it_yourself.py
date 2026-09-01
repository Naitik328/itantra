"""
Interactive test — type English text yourself, hear it spoken.

Run this from inside the hindifinal folder:
    python try_it_yourself.py

Type any Hindi sentence and press Enter. It saves each one as a numbered
.wav file in this folder and tells you the path. Type 'exit' or press
Ctrl+C to quit.
"""
import sys
import io

# Force UTF-8 stdin/stdout so English text typed in cmd.exe doesn't get mangled.
sys.stdin = io.TextIOWrapper(sys.stdin.buffer, encoding="utf-8")
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8")

import sherpa_onnx
import soundfile as sf

ESPEAK_DATA_DIR = r"C:\Users\samsung\AppData\Local\Programs\Python\Python313\Lib\site-packages\piper\espeak-ng-data"

config = sherpa_onnx.OfflineTtsConfig(
    model=sherpa_onnx.OfflineTtsModelConfig(
        vits=sherpa_onnx.OfflineTtsVitsModelConfig(
            model="en_IN-female-medium.onnx",
            tokens="en_IN-female-medium.tokens.txt",
            data_dir=ESPEAK_DATA_DIR,
        ),
        num_threads=1,
    ),
)

if not config.validate():
    raise ValueError("Config invalid — make sure you're running this from inside the hindifinal folder")

print("Loading model...")
tts = sherpa_onnx.OfflineTts(config)
print("Ready. Type English text and press Enter (type 'exit' to quit).\n")

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

    audio = tts.generate(text=text, sid=0, speed=1.0)
    count += 1
    out_path = f"my_test_{count}.wav"
    sf.write(out_path, audio.samples, samplerate=audio.sample_rate)
    duration = len(audio.samples) / audio.sample_rate
    print(f"  -> saved {out_path} ({duration:.2f} sec)\n")
