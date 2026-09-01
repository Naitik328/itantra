"""
Finds the female/male speaker boundary in a downloaded dataset by measuring
median pitch (F0) per clip, directly from the audio.

WHY NOT USE THE DATASET'S METADATA
----------------------------------
For Hindi we found the boundary from SPRINGLab/IndicTTS-Hindi's `gender`
field via HF's datasets-server API. IndicTTS-English has no such field, and
the assumption that it was therefore single-speaker female turned out to be
wrong -- it has a female block followed by a male block, just like Hindi, and
training on the mix produced a voice that switched gender mid-sentence.

This script does not trust dataset metadata at all. It reads the actual wavs,
so it works on any dataset regardless of what is (or isn't) labelled -- use it
to verify every new language before training, including Bengali and Telugu.

METHOD
------
Median F0 over energetic (voiced) frames, via librosa's YIN. Adult speech
splits cleanly: female roughly 165-265 Hz, male roughly 85-155 Hz. Run a
coarse pass first to locate the transition, then a fine pass to pin it, then
take a cutoff a little inside the female block to avoid the ambiguous zone.

Values measured for en_data (coarse step 200, then fine step 10):
    0 .. 8400   193-265 Hz   female, no exceptions
    8500        239 Hz       female
    8510        202 Hz       female   <- last confirmed female
    8520        156 Hz       ambiguous
    8530 ..     97-189 Hz    male
-> cutoff 8510, used by 3_filter_female_only.py

Usage:
    python 2_detect_gender_by_pitch.py ../en_data/audio            # coarse
    python 2_detect_gender_by_pitch.py ../en_data/audio 8480 8580 10   # fine
"""
import glob
import os
import sys
import warnings

import librosa
import numpy as np

warnings.filterwarnings("ignore")

FEMALE_MIN_HZ = 165.0
MALE_MAX_HZ = 150.0


def median_f0(path):
    """Median F0 over voiced frames, or None if the clip has too little voicing."""
    y, sr = librosa.load(path, sr=16000, duration=6.0)
    f0 = librosa.yin(y, fmin=60, fmax=400, sr=sr, frame_length=1024)
    rms = librosa.feature.rms(y=y, frame_length=1024, hop_length=512)[0]
    n = min(len(f0), len(rms))
    f0, rms = f0[:n], rms[:n]
    voiced = f0[(rms > 0.5 * np.median(rms[rms > 0])) & (f0 > 60) & (f0 < 400)]
    return float(np.median(voiced)) if len(voiced) >= 5 else None


def label(hz):
    if hz >= FEMALE_MIN_HZ:
        return "FEMALE"
    if hz <= MALE_MAX_HZ:
        return "male"
    return "?????"


def main():
    audio_dir = sys.argv[1] if len(sys.argv) > 1 else "../en_data/audio"
    files = sorted(glob.glob(os.path.join(audio_dir, "*.wav")))
    start = int(sys.argv[2]) if len(sys.argv) > 2 else 0
    stop = int(sys.argv[3]) if len(sys.argv) > 3 else len(files)
    step = int(sys.argv[4]) if len(sys.argv) > 4 else 200

    print(f"{audio_dir}: {len(files)} clips | scanning {start}..{stop} step {step}\n")
    print(f"{'index':>7s} {'medF0':>7s}  guess")
    counts = {"FEMALE": 0, "male": 0, "?????": 0}
    for i in range(start, min(stop, len(files)), step):
        hz = median_f0(files[i])
        if hz is None:
            continue
        tag = label(hz)
        counts[tag] += 1
        idx = int(os.path.splitext(os.path.basename(files[i]))[0])
        print(f"{idx:7d} {hz:7.1f}  {tag:6s} {'#' * int(hz / 6)}")

    print(f"\nfemale: {counts['FEMALE']} | male: {counts['male']} | ambiguous: {counts['?????']}")
    print("Pick a cutoff a little inside the last run of FEMALE rows.")


if __name__ == "__main__":
    main()
