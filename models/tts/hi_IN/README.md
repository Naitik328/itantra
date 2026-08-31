# Hindi TTS voice — `hi_IN-finetune-medium`

Fine-tuned Piper (VITS) voice for Hindi, built for iTantra's offline TTS step
(the last hop: `text → speech` on the receiving phone).

## Files

| File | Purpose |
|---|---|
| `hi_IN-finetune-medium.onnx` | The voice model (graph-optimized, portable — safe on any CPU/architecture, including Android/ARM). **Patched with sherpa-onnx metadata** (see below) — use this one, not a re-export straight from `piper.train.export_onnx`. |
| `hi_IN-finetune-medium.onnx.json` | Voice config (phoneme map, sample rate, speaker info) — must sit next to the `.onnx` with a matching basename |
| `tokens.txt` | Phoneme→id map in sherpa-onnx's plain-text format, generated from the config above. Pass this as sherpa-onnx's `tokens` path. |

## How it was made

- Base checkpoint: `en_US-lessac-medium` (Piper's official English checkpoint), used as a warm-start
- Fine-tuning dataset: [`SPRINGLab/IndicTTS-Hindi`](https://huggingface.co/datasets/SPRINGLab/IndicTTS-Hindi) — 11,825 clips, filtered to 10,118 after dropping clips >12s (prevents CUDA OOM during training)
- Trained with [`piper1-gpl`](https://github.com/OHF-Voice/piper1-gpl) for 30 epochs on an RTX 4090 (batch size 8, `16-mixed` precision)
- Best validation MOS: 3.29 (epoch 18); shipped checkpoint is the final epoch
- Exported to ONNX via `piper.train.export_onnx`, then graph-optimized with ONNX Runtime (`ORT_ENABLE_EXTENDED` — portable, no hardware-specific fusions)

## Benchmark (desktop CPU reference — re-benchmark on target Android device before shipping)

| Variant | Size | Load time | RTF |
|---|---|---|---|
| Original fp32 | 60.6 MB | ~3.5s | ~0.08–0.14 |
| **This (portable-optimized)** | 60.1 MB | ~3.1–3.6s | ~0.07–0.16 (best) |
| int8 dynamic-quantized | 18.4 MB | ~5.5–6.2s | ~0.43–0.52 (slower on this x86 CPU — untested on ARM/mobile, may behave differently) |

RTF = generation time ÷ output audio duration. RTF < 1 means faster than real-time.
Model load happens once at app start, not per utterance.

## How to load it

This repo's plan is **sherpa-onnx** for on-device inference (see main `README.md`,
"STT / TTS / VAD" row). sherpa-onnx loads Piper-format voices directly; point its
VITS config at this `.onnx` + `.onnx.json` pair (it also needs an `espeak-ng-data`
directory for phonemization, bundled by sherpa-onnx's Android AAR).

For quick desktop testing (Python, not what ships on-device):

```python
import wave
from piper import PiperVoice

voice = PiperVoice.load("hi_IN-finetune-medium.onnx")
with wave.open("out.wav", "wb") as wav_file:
    voice.synthesize_wav("नमस्ते, यह मेरी नई हिंदी आवाज़ है।", wav_file)
```

## sherpa-onnx compatibility fix (2026-08-31)

A raw `piper.train.export_onnx` output crashes sherpa-onnx's native init:

```
sherpa-onnx: offline-tts-vits-model.cc:Init:169
'sample_rate' does not exist in the metadata
```

sherpa-onnx expects `model_type`, `comment`, `language`, `voice`, `n_speakers`,
`sample_rate` (and a few others) embedded as ONNX `metadata_props` — these
aren't written by Piper's own exporter. This model has already been patched
(script: `add_sherpa_metadata.py`, based on sherpa-onnx's official
`scripts/piper/add_meta_data.py`) and the matching `tokens.txt` generated. If
you re-export this voice from a checkpoint for any reason, re-run the patch
step before shipping it — an unpatched `.onnx` will crash on init exactly like
above.

## Known limitations / next steps

- Only tested with hardcoded text so far — no STT integration yet (AI Member 1's
  model isn't wired up), so this hasn't been run on text coming from real speech.
- Benchmarks above are desktop-CPU only; RTF on an actual Android phone will
  differ and should be re-measured once sherpa-onnx is wired in.
- English and Bengali fine-tunes are in progress (same pipeline), to follow.
