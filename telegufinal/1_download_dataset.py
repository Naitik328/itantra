"""
Downloads the female-speaker rows of ai4bharat/Rasa (config "Telugu") and
writes them out in Piper's training format.

WHY THIS IS DIFFERENT FROM HINDI/BENGALI/ENGLISH
--------------------------------------------------
Hindi and English (IndicTTS) have no reliable per-row gender label, so their
speaker boundary had to be inferred (HF datasets-server gender field for
Hindi; median-pitch analysis for English, after the "no gender column means
single-speaker" assumption turned out to be wrong). Bengali's chosen source
(ai4bharat/Rasa, config "Bengali") does the same as this script.

Rasa's schema has a genuine `gender` column ("Female"/"Male"), corroborated
by the filename prefix itself (TEL_F_... / TEL_M_...) -- checked across the
whole split (offsets 0, 5000, 10000 -> Female; 15000, 20000 -> Male). This
script filters on that column directly instead of guessing an index cutoff.

Gated dataset: requires accepting terms at
https://huggingface.co/datasets/ai4bharat/Rasa while logged in, then an HF
token with read access. Load it before running:

    export HF_TOKEN=$(cat /path/to/.hf_token.txt | tr -d '\r\n ')
"""
import io
import os

import soundfile as sf
from datasets import Audio, load_dataset

OUT_DIR = r"C:\itantra-tts\te_data"  # run locally, like hi_data/en_data before it; adjust to /workspace/te_data for pod-side runs
AUDIO_DIR = os.path.join(OUT_DIR, "audio")
METADATA_PATH = os.path.join(OUT_DIR, "metadata.csv")

os.makedirs(AUDIO_DIR, exist_ok=True)

token = os.environ.get("HF_TOKEN")
if not token:
    raise SystemExit("Set HF_TOKEN first (see docstring) -- ai4bharat/Rasa is gated.")

print("Downloading ai4bharat/Rasa, config=Telugu, split=train ...", flush=True)
ds = load_dataset("ai4bharat/Rasa", "Telugu", split="train", token=token)
ds = ds.cast_column("audio", Audio(decode=False))
print(f"Loaded {len(ds)} examples (all genders)", flush=True)

kept = skipped_male = 0
with open(METADATA_PATH, "w", encoding="utf-8") as f:
    for i, example in enumerate(ds):
        if example["gender"] != "Female":
            skipped_male += 1
            continue
        audio = example["audio"]
        text = example["text"]
        data, samplerate = sf.read(io.BytesIO(audio["bytes"]))
        filename = f"{kept:06d}.wav"
        out_path = os.path.join(AUDIO_DIR, filename)
        sf.write(out_path, data, samplerate)
        # Strip literal double-quotes: piper1-gpl reads this file with
        # Python's csv.reader(delimiter="|"), whose default dialect treats
        # `"` as a quote character. An unbalanced quote anywhere in the file
        # makes csv.reader swallow every following "|"-delimited line into
        # one field until a closing quote turns up -- silently merging dozens
        # of unrelated rows into a single multi-thousand-character "text",
        # which is a real, deterministic OOM at training time (verified:
        # this exact bug produced a 17,870-character merged row here and
        # caused CUDA OOM trying to allocate ~27GiB for its attention mask).
        # `"` isn't meaningful phonetic content, so dropping it is safe.
        text = text.replace('"', "").replace("\r", "").strip()
        f.write(f"{filename}|{text}\n")
        kept += 1
        if i % 1000 == 0:
            print(f"  scanned {i}/{len(ds)}  (kept {kept} female so far)", flush=True)

print("Done.", flush=True)
print(f"Kept (female): {kept}", flush=True)
print(f"Skipped (male): {skipped_male}", flush=True)
print(f"Audio dir: {AUDIO_DIR}", flush=True)
print(f"Metadata: {METADATA_PATH}", flush=True)
