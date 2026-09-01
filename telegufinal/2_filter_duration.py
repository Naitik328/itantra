import soundfile as sf

METADATA_PATH = "/workspace/te_data/metadata.csv"
AUDIO_DIR = "/workspace/te_data/audio"
MAX_DURATION = 12.0

with open(METADATA_PATH, "r", encoding="utf-8") as f:
    lines = [l.rstrip("\n") for l in f if l.strip()]

kept = []
removed = 0
for line in lines:
    filename, text = line.split("|", 1)
    info = sf.info(f"{AUDIO_DIR}/{filename}")
    duration = info.frames / info.samplerate
    if duration <= MAX_DURATION:
        kept.append(line)
    else:
        removed += 1

with open(METADATA_PATH, "w", encoding="utf-8") as f:
    for line in kept:
        f.write(line + "\n")

print(f"Kept: {len(kept)}")
print(f"Removed: {removed}")
print(f"Total: {len(lines)}")
