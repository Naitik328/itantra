# Telugu TTS — female-only fine-tune pipeline

Reproducible pipeline for fine-tuning Piper on Telugu, filtered to a
**single (female) speaker**, for iTantra's offline TTS.

## Status: done and shipped

Trained 30 epochs on the full 12,612-row female-filtered dataset. Best
checkpoint: **epoch=28, val_mos=3.0532**. Exported to ONNX, patched with
sherpa-onnx metadata, graph-optimized, and verified end-to-end with
`sherpa_onnx.OfflineTts` (the real Android code path) -- confirmed female by
median pitch (269.9 Hz). Final files:
`te_IN-female-medium.onnx` + `.onnx.json` + `.tokens.txt`.

## The quote-character bug (read this before touching batch_size on a future language)

Getting here took seven straight CUDA OOM crashes, each one chased as a
different symptom -- batch_size dropped 32 -> 16 -> 8, `num_workers` dropped
8 -> 1, a text-length trim, `PYTORCH_CUDA_ALLOC_CONF=expandable_segments`.
None of it worked, because none of it addressed the actual cause.

piper1-gpl reads `metadata.csv` with Python's `csv.reader(delimiter="|")`.
That reader's default dialect treats `"` as a quote character. 229 of our
rows contained a literal `"` (ordinary Telugu punctuation, not meant as a
CSV quote). An unbalanced quote makes `csv.reader` swallow every following
`|`-delimited line into a single field until a closing quote turns up --
silently merging dozens of unrelated rows into one "row" with a
multi-thousand-character "text". Reparsing our own file with the exact same
`csv.reader` call made this obvious: 12,612 real rows collapsed into 10,050
parsed rows, and the worst merged row was **17,870 characters** (originally
row 2216 -- which is exactly the row number that showed up mangled into
corrupted `te_cache/*.phonemes.pt` filenames on the very first crash. That
was the bug announcing itself immediately; it was misread as a worker-race
cache collision instead).

A ~18,000-character "utterance" phonemizes into thousands of tokens.
Self-attention memory is `O(batch x sequence_length^2)`, so that is where a
26.95 GiB allocation attempt came from on a 23.53 GiB GPU. It had nothing to
do with Telugu's real phoneme density: the actual longest genuine utterance
in this dataset, verified with piper's own `EspeakPhonemizer`, is 259
phonemes -- trivial. Shrinking `batch_size` or filtering by text length was
never the fix; the bug was upstream, in how the CSV got written.

**Fix**, applied in `1_download_dataset.py`: strip literal `"` characters
from `text` before writing each row (`"` isn't meaningful phonetic content,
so dropping it is safe). Re-parsing the corrected `metadata.csv` with
`csv.reader`: 12,612 rows in, 12,612 rows out, longest text 193 characters,
matching the real content. `3_train.sh` uses the full 12,612-row dataset and
Hindi/English's original fast config (`batch_size=32`, `num_workers=8`) --
there was never anything wrong with either of those, and that's exactly what
trained successfully once the data was clean.

**If Bengali hits an unexplained OOM, check this first:**
```python
import csv
with open("metadata.csv", encoding="utf-8") as f:
    n_lines = sum(1 for _ in f)
with open("metadata.csv", encoding="utf-8") as f:
    n_parsed = sum(1 for _ in csv.reader(f, delimiter="|"))
print(n_lines, "physical lines vs", n_parsed, "csv.reader rows")
```
If they don't match, the same quote bug is present -- strip `"` from `text`
in the download script and regenerate.

## Source dataset: ai4bharat/Rasa, config "Telugu"

Gated dataset -- accept terms at huggingface.co/datasets/ai4bharat/Rasa while
logged in, then use an HF read token. 27,367 rows in the train split (~15GB
of parquet).

## Why this dataset filters differently than Hindi/English

Hindi (`SPRINGLab/IndicTTS-Hindi`) and English (`SPRINGLab/IndicTTS-English`)
both needed indirect detection of the female/male boundary -- Hindi via a
`gender` field recovered from the original dataset (not present in our own
metadata.csv format), English via median-pitch analysis after the "no gender
column means single-speaker" assumption turned out to be wrong (see
`../englishfinal/README.md`).

Rasa's schema has a genuine, verified `gender` column ("Female"/"Male"),
corroborated by the filename prefix itself (`TEL_F_...` / `TEL_M_...`).
Checked directly across the split:

| offset | gender | example filename |
|---|---|---|
| 0 | Female | TEL_F_WIKI_01212 |
| 5000 | Female | TEL_F_BOOK_00013 |
| 10000 | Female | TEL_F_CONV_02060 |
| 15000 | Male | TEL_M_WIKI_00958 |
| 20000 | Male | TEL_M_NAMES_01628 |

So `1_download_dataset.py` filters on `example["gender"] == "Female"`
directly while downloading, instead of guessing an index cutoff the way
Hindi/Bengali do -- no ambiguous boundary zone to land a cutoff just inside
of.

## Pipeline (run in this order)

| Script | What it does |
|---|---|
| `1_download_dataset.py` | Downloads `ai4bharat/Rasa` (config Telugu, gated -- needs `HF_TOKEN`), filters to `gender == "Female"` inline, strips literal `"` from text (see the quote-bug section above), writes `te_data/audio/*.wav` + `metadata.csv` (`filename\|text`) |
| `2_filter_duration.py` | Drops clips longer than 12s (prevents CUDA OOM). Kept 12,612 of 13,862 female clips |
| `3_train.sh` | Fine-tunes from `en_US-lessac-medium` warm-start, `--data.espeak_voice "te"`, 30 epochs, `--data.batch_size 32 --data.num_workers 8`, `16-mixed` precision |
| `4_export_onnx.py` | Exports the trained checkpoint to ONNX (forces the legacy exporter -- see script docstring) |
| `5_add_sherpa_metadata.py` | Patches sherpa-onnx's required metadata into the ONNX file + generates `tokens.txt` (drops multi-character phoneme symbols -- 5 dropped here, 161 kept). **Mandatory** -- an unpatched export crashes sherpa-onnx's native init |
| `6_optimize_onnx.py` | ONNX Runtime graph optimization (`ORT_ENABLE_EXTENDED`). Small win (~7%), safe and portable |
| `try_it_yourself.py` | Interactive test -- type Telugu text, hear it. Ships with `num_threads=4` and `length_scale=0.9` already set (see Latency below) |

## Training result

- Dataset: 12,612 female clips, duration-filtered, quote-bug fixed
- 30 epochs, batch_size=32, num_workers=8, ~2.2-2.4 it/s once warmed up
  (~3 min/epoch on an RTX 4090), full run ≈ 1.5 hours
- Best checkpoint by val_mos: **epoch=28, val_mos=3.0532**
  (Hindi: 3.96 on 3,856 clips; English: 4.15 on 8,510 clips -- Telugu's
  score is lower, consistent with a harder language for this base
  checkpoint/warmstart combination, not a data or pipeline problem)
- Verified with `sherpa_onnx.OfflineTts` (not just `piper.PiperVoice`):
  loads and generates correctly, median F0 269.9 Hz (female range is
  ~165-265 Hz to give a sense of scale, so this reads as clearly female,
  possibly on the higher/brighter end)

## Latency and speaking rate

Both are set on the *caller* side, not in the model file -- see
[`../INTEGRATION.md`](../INTEGRATION.md). `try_it_yourself.py` already ships
with `num_threads=4` and `length_scale=0.9` baked in. Telugu's default rate
(`length_scale=1.0`) was measured noticeably brisker than English/Bengali's
baseline (~152 vs ~112-135 "words"/min on a sample sentence), so it only
needs a light tightening rather than the more aggressive value used
elsewhere. sherpa-onnx's default `num_threads=1` measured ~2x slower than 4
on this model; `length_scale` **cannot** be baked into the ONNX metadata --
verified, sherpa-onnx ignores it there.
Do not int8-quantize: measured 3x *slower* on this project
(`benchmark_results.csv`).

## RunPod quirks hit while training this one

- **Pods terminated outright, repeatedly** (not just "container restarted"
  -- `list-pods` returned zero pods, meaning the compute was gone, not just
  stopped). Recovery each time: deploy a fresh pod with the same network
  volume attached, re-run `setup.sh` (packages don't persist across pods,
  only `/workspace` does), and resume from the latest checkpoint.
- **Disk quota exceeded**: the network volume is 80GB total; stale
  `en_cache` (15GB) and `lightning_logs_en` (13GB) from the abandoned
  English fine-tune, plus a wrong Bengali download (6.5GB), were cleared to
  make room before this training run.
- See `../hindifinal/README.md` and `../englishfinal/README.md` for more on
  disk quota and container-restart recovery in detail.
