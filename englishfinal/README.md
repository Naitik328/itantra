# English TTS — fine-tune pipeline

Reproducible pipeline for fine-tuning Piper on English (Indian-accented),
for iTantra's offline TTS.

## Result

Trained 30 epochs on 12,000 examples (subsampled from `SPRINGLab/IndicTTS-English`),
batch_size=32, RTX 4090. Best checkpoint: epoch=27, **val_mos=4.15**.
Final files: `en_IN-female-medium.onnx` + `.onnx.json` + `.tokens.txt` —
tested against both the `piper` Python package and `sherpa_onnx.OfflineTts`
directly (the actual engine Android will use). `try_it_yourself.py` lets you
type your own text and hear it.

## Why no gender-filtering step (unlike Hindi)

`SPRINGLab/IndicTTS-English` has no `gender` column at all in its schema
(unlike Hindi/Bengali, which do) -- confirmed both via the dataset's
`features` list and by inspecting actual rows via HF's datasets-server API.
It's genuinely single-speaker. A related dataset,
`Anjan9320/IndicTTS-English-female`, confirms that speaker is **female** --
matching the "one consistent female voice per language" requirement without
any extra filtering.

## Pipeline (run in this order)

| Script | What it does |
|---|---|
| `1_download_dataset.py` | Streams `SPRINGLab/IndicTTS-English` (151,640 examples total -- **subsampled to the first 12,000** rather than the full ~78GB, to keep download/processing time reasonable). Writes `en_data/audio/*.wav` + `metadata.csv` (`filename\|text`) |
| `2_filter_duration.py` | Drops clips longer than 12s (prevents CUDA OOM). On this subsample: 0 clips removed -- all under 12s already |
| `3_train.sh` | Fine-tunes from `en_US-lessac-medium` warm-start, 30 epochs, batch_size=32 (see note below), `16-mixed` precision |
| `4_export_onnx.py` | Exports the trained checkpoint to ONNX (forces the legacy exporter -- newer PyTorch defaults to a torch.export-based one that fails on this model's dynamic control flow) |
| `5_add_sherpa_metadata.py` | Patches sherpa-onnx's required metadata into the ONNX file + generates `tokens.txt`, filtering out any multi-character phoneme symbols (sherpa-onnx's `ReadTokens` requires exactly one Unicode codepoint per symbol; Piper's shared vocabulary has a few multi-char diphthongs). **Mandatory** -- skipping this crashes sherpa-onnx's native init |

## batch_size=32, not the default 8

Piper's default `--data.batch_size` is small enough that the GPU sits mostly
idle waiting on CPU-side data loading (confirmed via `nvidia-smi` showing
~12W power draw / 0% util at batch_size=8, vs. 70-130W / GPU actually busy
at batch_size=32). Also pass `--data.num_workers 8` (default is 1) -- with
only 1 worker, a 48-core pod still bottlenecks on single-threaded data
loading.

## A recurring issue: RunPod network-volume disk quota

The network volume has a fixed provisioned size (originally 80GB). Training
cache (`en_cache`, `hi_cache`) plus every epoch's checkpoints across
multiple crash/resume cycles adds up fast and silently causes writes to
fail with `OSError: [Errno 122] Disk quota exceeded` -- this has crashed
training mid-run more than once. If it recurs:
1. `du -sh /workspace/piper_work/*` to see what's using space
2. Delete completed languages' old checkpoints/caches (already
   exported to ONNX -- e.g. `lightning_logs`, `hi_cache` once Hindi was done)
3. Resume the crashed run with `--ckpt_path <path to last.ckpt>` (full
   trainer-state resume, not `--model.warmstart_ckpt`, which only copies
   weights and restarts from epoch 0)

## Other unrelated container restarts

Separately from the disk-quota crashes, this RunPod host restarted the pod's
*container* (not the host -- `uptime` stayed continuous) at least twice
during training, silently killing the tmux session and all training
processes with no error in the log. If `pgrep -f 'piper.train fit'` comes
back empty and `tmux list-sessions` says "no server running", that's this --
just resume from the last checkpoint the same way as above.
