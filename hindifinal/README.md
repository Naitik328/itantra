# Hindi TTS — female-only fine-tune pipeline

Complete, reproducible pipeline for fine-tuning Piper on Hindi, filtered to a
**single (female) speaker**, for iTantra's offline TTS.

## Why female-only

`SPRINGLab/IndicTTS-Hindi` (the source dataset) contains two speakers
back-to-back: a female block, then a male block -- not interleaved, and not
labeled in the raw CSV we originally built. Training on all of it without
telling the model which utterance belongs to which speaker produced a model
that randomly switched between male- and female-sounding output mid-text.
The app's requirement is one consistent voice per language (chosen once,
tied to the sender's registered gender) -- so we isolate and train on the
female block only.

## Pipeline (run in this order)

| Script | What it does |
|---|---|
| `1_download_dataset.py` | Streams `SPRINGLab/IndicTTS-Hindi` from HF, writes `hi_data/audio/*.wav` + `metadata.csv` (`filename\|text`) |
| `2_recover_gender_labels.py` | Re-streams the same dataset to pull the `gender` field per index (our original download script hadn't kept it), used only to *find* the female/male boundary |
| `3_filter_female_only.py` | Drops every row at/after the female→male boundary (index ≥ 4450) |
| `4_filter_duration.py` | Drops clips longer than 12s (prevents CUDA OOM during training regardless of batch size) |
| `5_train.sh` | Fine-tunes from `en_US-lessac-medium` warm-start, 30 epochs, RTX 4090, batch size 8, `16-mixed` precision |
| `6_export_onnx.py` | Exports the trained checkpoint to ONNX (forces the legacy exporter -- see script docstring for why) |
| `7_add_sherpa_metadata.py` | Patches sherpa-onnx's required metadata into the ONNX file + generates `tokens.txt`. **Mandatory** -- an unpatched export crashes sherpa-onnx's native init with `'sample_rate' does not exist in the metadata` |

## How the female/male boundary was found

`SPRINGLab/IndicTTS-Hindi` has no gender column in the metadata.csv format we
use for training, but the original HF dataset does (`gender`, a ClassLabel:
0=female, 1=male). We binary-searched it via HF's datasets-server REST API
(no need to download anything for this step):

```
https://datasets-server.huggingface.co/rows?dataset=SPRINGLab%2FIndicTTS-Hindi&config=default&split=train&offset=<N>&length=5
```

Results: index 4400 → female, index 4550 → male, 4700 → male. Cutoff used:
**4450** (safely inside the female block, before the transition).

## Dataset sizes at each step

- Raw female block: 4,450 clips
- After duration filter (≤12s): **3,856 clips** -- this is what was trained on

## Known issue this pipeline fixes

sherpa-onnx's native VITS loader crashes on a plain Piper export:
```
sherpa-onnx: offline-tts-vits-model.cc:Init:169
'sample_rate' does not exist in the metadata
```
`7_add_sherpa_metadata.py` (mirrors sherpa-onnx's official
`scripts/piper/add_meta_data.py`) fixes this by writing `model_type`,
`comment`, `language`, `voice`, `n_speakers`, `sample_rate` etc. as ONNX
`metadata_props`, and generates the `tokens.txt` sherpa-onnx expects
alongside the model. Re-run this step any time the voice is re-exported from
a checkpoint.

## Other languages

English (`SPRINGLab/IndicTTS-English`) is confirmed single-speaker (no
gender field at all in the source), so it doesn't need the
recover-gender/filter-female steps -- straight through 1 → (skip 2, 3) → 4 →
5 → 6 → 7.

Bengali's original source (`SPRINGLab/IndicTTS_Bengali`) turned out to be
single-speaker **male only** (checked across the full index range) --
useless for a female-only voice. `ai4bharat/Rasa` (gated dataset, requires
accepting terms on HF) has real male+female Bengali data instead: shards
0-27 of `Bengali/train-*-of-00055.parquet` are female, confirmed via the
same datasets-server binary-search approach. Telugu, similarly, is female in
shards 0-27 of `Telugu/train-*-of-00056.parquet` (male from shard ~29 on).
