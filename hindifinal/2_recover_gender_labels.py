import csv
from datasets import load_dataset, Audio

METADATA_PATH = "/workspace/hi_data/metadata.csv"

with open(METADATA_PATH, "r", encoding="utf-8") as f:
    lines = [l.rstrip("\n") for l in f if l.strip()]

surviving = {}
for line in lines:
    filename, text = line.split("|", 1)
    idx = int(filename.split(".")[0])
    surviving[idx] = text

print(f"Surviving entries: {len(surviving)}", flush=True)

print("Streaming original dataset to recover gender per index...", flush=True)
ds = load_dataset("SPRINGLab/IndicTTS-Hindi", split="train", streaming=True)
ds = ds.cast_column("audio", Audio(decode=False))

genders = {}
for i, example in enumerate(ds):
    if i in surviving:
        genders[i] = example["gender"]
    if len(genders) == len(surviving):
        break
    if i % 2000 == 0:
        print(f"  scanned {i}, found {len(genders)}/{len(surviving)}", flush=True)

print(f"Genders recovered: {len(genders)}", flush=True)
unique_genders = set(genders.values())
print(f"Unique speaker labels: {unique_genders}", flush=True)

with open(METADATA_PATH, "w", encoding="utf-8") as f:
    for idx in sorted(surviving.keys()):
        filename = f"{idx:06d}.wav"
        speaker = genders.get(idx, "unknown")
        text = surviving[idx]
        f.write(f"{filename}|{speaker}|{text}\n")

print("Done. metadata.csv rewritten with speaker column.", flush=True)
