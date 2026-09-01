"""
Keeps only the female-speaker rows of en_data/metadata.csv.

SPRINGLab/IndicTTS-English is organized in two contiguous speaker blocks, the
same way IndicTTS-Hindi is: female first, then male. This was NOT obvious --
the dataset has no `gender` column, and a derived repo
(Anjan9320/IndicTTS-English-female) made it look single-speaker. The first
English model was trained on the unfiltered first 12,000 clips (~8.5k female
+ ~3.5k male) and randomly switched between male- and female-sounding output,
exactly the bug that had already been fixed for Hindi.

The boundary was measured from the audio itself with
2_detect_gender_by_pitch.py (median F0), not from metadata:

    index 8500 -> 239 Hz   female
    index 8510 -> 202 Hz   female   <- last confirmed female
    index 8520 -> 156 Hz   ambiguous
    index 8530 -> 139 Hz   male

Cutoff 8510 sits just inside the female block, mirroring Hindi's 4450.

Run on the pod BEFORE 4_filter_duration.py and before training.
"""
FEMALE_INDEX_CUTOFF = 8510

METADATA_PATH = "/workspace/en_data/metadata.csv"  # run on the pod; adjust path if run elsewhere

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
