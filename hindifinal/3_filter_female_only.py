"""
Keeps only the female-speaker rows of hi_data/metadata.csv.

SPRINGLab/IndicTTS-Hindi is organized in two contiguous speaker blocks (not
interleaved): female first (roughly index 0-4449), then male (roughly index
4550 onward). The exact boundary was found by binary-searching the dataset's
"gender" field via HF's datasets-server API (see conversation/README) --
index 4400 is confirmed female, 4550 confirmed male. We use a safe cutoff of
4450 to avoid the ambiguous zone.

Must be run AFTER 3_filter_duration.py (or before -- order doesn't matter,
this just does another row-level filter on the same file), and BEFORE
uploading metadata.csv / running training.
"""
FEMALE_INDEX_CUTOFF = 4450

METADATA_PATH = "/workspace/hi_data/metadata.csv"  # run on the pod; adjust path if run elsewhere

with open(METADATA_PATH, "r", encoding="utf-8") as f:
    lines = [l.rstrip("\n") for l in f if l.strip()]

kept = []
removed = 0
for line in lines:
    filename, text = line.split("|", 1)
    idx = int(filename.split(".")[0])
    if idx < FEMALE_INDEX_CUTOFF:
        kept.append(line)
    else:
        removed += 1

with open(METADATA_PATH, "w", encoding="utf-8") as f:
    for line in kept:
        f.write(line + "\n")

print(f"Kept (female): {len(kept)}")
print(f"Removed (male): {removed}")
