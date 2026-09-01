import os
import io
import itertools
import soundfile as sf
from datasets import load_dataset, Audio

OUT_DIR = r"C:\itantra-tts\en_data"
AUDIO_DIR = os.path.join(OUT_DIR, "audio")
METADATA_PATH = os.path.join(OUT_DIR, "metadata.csv")
TARGET_EXAMPLES = 12000

os.makedirs(AUDIO_DIR, exist_ok=True)

print("Streaming SPRINGLab/IndicTTS-English (subsampled, no full download)...", flush=True)
ds = load_dataset("SPRINGLab/IndicTTS-English", split="train", streaming=True)
ds = ds.cast_column("audio", Audio(decode=False))

kept = 0
with open(METADATA_PATH, "w", encoding="utf-8") as f:
    for i, example in enumerate(itertools.islice(ds, TARGET_EXAMPLES)):
        audio = example["audio"]
        text = example["text"]
        data, samplerate = sf.read(io.BytesIO(audio["bytes"]))
        filename = f"{i:06d}.wav"
        out_path = os.path.join(AUDIO_DIR, filename)
        sf.write(out_path, data, samplerate)
        f.write(f"{filename}|{text}\n")
        kept += 1
        if i % 500 == 0:
            print(f"  wrote {i}/{TARGET_EXAMPLES}", flush=True)

print("Done.", flush=True)
print(f"Total written: {kept}", flush=True)
print(f"Audio dir: {AUDIO_DIR}", flush=True)
print(f"Metadata: {METADATA_PATH}", flush=True)
