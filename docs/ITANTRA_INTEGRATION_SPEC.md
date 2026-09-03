# iTantra — Voice Pipeline Integration Specification

**Version:** 1.0
**Date:** 2026-09-03
**Status:** Draft for team review
**Scope:** On-device STT → Machine Translation → TTS pipeline for Android

**Source documents this consolidates:**
- `iTantra_TTS_Integration_Requirements.pdf` — Raj (rajaryan08161@gmail.com), 2026-09-02 — Hindi, English, Telugu, Bengali TTS
- `itantra_stt_integration_requirements_FILLED.pdf` — Shivanshu Vats (vatsshivanshu05@gmail.com), 2026-09-03 — Hindi, English, Telugu STT

---

## 0. Reader's guide

| If you are… | Read |
|---|---|
| Deciding packaging/release strategy | §1, §2 |
| Writing the orchestrator | §4, §5, §6 |
| Writing an adapter | §5, §7 |
| Building the model bundle | §8 |
| Looking for what's unresolved | §3, §10 |

**Terminology.** *STT* = speech-to-text. *TTS* = text-to-speech. *MT* = machine translation. *Pivot* = the intermediate language all translation routes through (English). *Adapter* = the thin per-model wrapper that gives every model the same interface. *Orchestrator* = the component that sequences STT → MT → TTS and owns all per-language configuration.

---

## 1. Executive summary

The pipeline is: **speech → STT → text → MT → text → TTS → speech**, entirely on-device, with AES-256-GCM encryption on the wire between devices.

Three findings from the collaborator handoffs drive this spec:

1. **The models are architecturally compatible.** Both STT and TTS load through **sherpa-onnx**, the same runtime. Script conventions from STT (native Devanagari/Telugu/lowercase Latin, never romanized) line up with what IndicTrans2 expects, so no transliteration glue is needed between stages.

2. **Total model size breaks the "bundle both defaults in the APK" plan.** Hindi + English alone are ~477 MB of models before translation. See §2 — this requires a packaging decision before anything else is built.

3. **Several per-language settings cannot be stored in the model files and fail silently when wrong.** English STT uses 8x subsampling where the others use 4x; Bengali TTS defaults to a *male* speaker unless `sid=12` is passed explicitly. Both produce plausible-looking wrong output rather than an error. These are centralized in §4 and must never be duplicated into per-language code.

**Highest-priority open items:** packaging decision (§2), VAD ownership (§3.1), on-device RAM measurement (§3.2), Telugu CC-BY attribution (§3.3).

---

## 2. Size budget and the packaging decision

### 2.1 Measured sizes

All figures measured by the collaborators, not estimated.

| Language | STT (int8) | TTS (fp32) | Per-language total |
|---|---|---|---|
| Hindi | 177 MB | 63.1 MB | **240.1 MB** |
| English | 174 MB | 63.2 MB | **237.2 MB** |
| Telugu | 188 MB | 60.1 MB | **248.1 MB** |
| Bengali | — (none) | 73.2 MB | **73.2 MB** |

| Shared component | Size | Notes |
|---|---|---|
| `espeak-ng-data/` | ~19 MB | One copy for all TTS languages |
| IndicTrans2 translation core | ~100–150 MB | Both directions, int8, estimated — not yet built |

### 2.2 The problem

**Hindi + English (the two "default" languages) = ~477 MB of models**, plus ~19 MB espeak data, plus ~100–150 MB translation core = **~600 MB base install**.

This exceeds Google Play's delivery limits for a single APK and is well beyond reasonable install size. Both collaborators independently reached this conclusion; the STT handoff states plainly that ~550 MB for three models is "well past comfortable APK territory."

The current SIH deck claims Hindi and English ship *inside* the app. **That claim is not currently deliverable as written** and needs either a technical change or a rewording.

### 2.3 Options

| Option | APK size | Offline on first run? | Notes |
|---|---|---|---|
| **A. Bundle everything** | ~600 MB | Yes | Not viable — exceeds Play limits |
| **B. First-launch download of defaults** | ~30 MB | No — needs one-time connection | Recommended. Download during onboarding into `filesDir` |
| **C. Onboarding language picker** | ~30 MB | No | Like B, but user picks 1 language instead of getting both — halves the download |
| **D. Play Asset Delivery** | ~30 MB | Yes, if install-time PAD | Cleanest for production; more build complexity |

**Recommendation: Option B or C for the hackathon**, with Option D noted as the production path. Frame it in the deck as "installed during first-run setup" rather than "bundled" — accurate, and still supports the fully-offline claim for all subsequent use.

**Both STT and TTS must load from a file path, not from APK assets**, under any of B/C/D. sherpa-onnx supports path-based construction for both recognizer and TTS.

### 2.4 Consequence for the deck

Slide 2 and Slide 4 of the SIH deck currently state Hindi and English are bundled and that optional languages are downloaded. Suggested rewording: *"Hindi and English install during first-run setup; 8 more languages download on demand."* This preserves the offline story while being technically accurate.

---

## 3. Blockers and gaps

### 3.1 VAD is unowned — blocks end-to-end demo

All three STT models are **offline/batch recognizers over complete utterances**. They do not stream and do not detect speech boundaries. Without a VAD, the app cannot know when a user has stopped speaking.

The STT handoff explicitly excludes it: *"that VAD is not yet part of this handoff."* Raj's role nominally covers VAD but his document is TTS-only.

**Action:** assign an owner. Silero VAD ships bundled with sherpa-onnx, so this is integration work rather than model work — but it is on the critical path for any live demo.

### 3.2 No on-device measurements anywhere

Every performance number in both handoffs is desktop CPU. RAM is unmeasured on **any** platform, by either collaborator. NNAPI is untested by both.

This directly blocks the memory-management decision in §6.2. Estimated phone RTF is 10–20x desktop, which still lands comfortably inside realtime — but RAM is the number that decides the architecture, and nobody has it.

**Action:** one Android device, measure peak RSS per stage. This is the single highest-value measurement outstanding.

### 3.3 Telugu attribution — shipping blocker

Telugu STT weights are CC-BY-4.0 (`trysem/indicconformer-120m-onnx`, derived from AI4Bharat's MIT-licensed model). CC-BY-4.0 **requires attribution**. Credit both AI4Bharat and the trysem export in the app's about/licenses screen.

The STT handoff flags this as "the one item in this document that is a shipping blocker rather than a technical note." Cheap to fix; do not let it slip.

### 3.4 Coverage asymmetry: Bengali has no STT

Bengali has a TTS voice but no STT model. A Bengali speaker can *receive* voice messages but cannot *speak* into the app.

This may be an acceptable "receive-only" tier, but it must be a deliberate product decision surfaced in the UI, not discovered at demo time. Either scope Bengali as receive-only explicitly, or source a Bengali STT model.

### 3.5 Other open items

| Item | Owner | Severity |
|---|---|---|
| `espeak-ng-data/` not yet in repo (sourced from local PyPI wheel) | Raj | Medium — nothing TTS ships without it |
| Bengali TTS voice license/provenance unverified | Raj | Medium — confirm before shipping |
| Telugu STT unvalidated on phone-mic audio (only clean FLEURS) | Shivanshu | Medium — affects demo expectations |
| Telugu STT export faithfulness unverified (no `.nemo` to cross-check) | Shivanshu | Low — loads and scores sensibly |
| IndicProcessor preprocessing not yet ported to Kotlin | Unassigned | High — MT quality depends on it |

---

## 4. The configuration contract

**This is the most important section of this document.**

Certain values cannot be stored inside the model files and must be supplied by the orchestrator on every call. When they are wrong, the models do not error — they produce confident, plausible, wrong output. Every one of these is a silent failure.

### 4.1 Master configuration table

| | Hindi | English | Telugu | Bengali |
|---|---|---|---|---|
| **STT** | | | | |
| Tag | `hi-IN` | `en-US` | `te-IN` | — |
| Architecture | Conformer CTC | FastConformer CTC | Conformer CTC | — |
| Subsampling factor | 4 | **8** | 4 | — |
| Vocab size | 129 | 1025 | 5633 | — |
| `normalize_type` | `per_feature` | `per_feature` | `per_feature` | — |
| Output script | Devanagari | Latin (lowercase) | Telugu | — |
| WER (FLEURS, clean) | 12.2% | 12.5% | 23.7% | — |
| **TTS** | | | | |
| espeak voice code | `hi` | `en-us` | `te` | `bn` |
| `length_scale` | 0.85 | 0.85 | 0.9 | 0.8 |
| Speaker ID (`sid`) | n/a (single) | n/a (single) | n/a (single) | **12** |
| Output sample rate | 22050 Hz | 22050 Hz | 22050 Hz | 22050 Hz |

### 4.2 The two silent-failure traps

**Trap 1 — English STT subsampling is 8, not 4.**
Timestamps are computed from the subsampling factor. Copying `4` from the Hindi integration silently misplaces every timestamp in English transcripts. No error is raised.

**Trap 2 — Bengali TTS `sid=0` is a male speaker.**
`bn_BD-google-medium` is a 16-speaker model; every other voice is single-speaker. The correct female voice is `sid=12` (verified by median pitch, 257.0 Hz). A `synthesize()` call copy-pasted from any other language will omit `sid` or pass `0`, producing a male voice with no error.

**Mitigation for both:** these values live in the config table (§4.3) and nowhere else. No adapter hardcodes them. Code review should reject any literal `4`, `8`, `0`, or `12` appearing in adapter logic.

### 4.3 Config as data

Ship this as a JSON asset, not as Kotlin constants — it must be editable without a rebuild, and it is the single source of truth.

```json
{
  "languages": {
    "hi": {
      "displayName": "हिन्दी",
      "stt": {
        "model": "indicconformer_hi.int8.onnx",
        "tokens": "tokens.txt",
        "modelType": "nemo_ctc"
      },
      "tts": {
        "model": "hi_IN-female-medium.onnx",
        "tokens": "hi_IN-female-medium.tokens.txt",
        "espeakVoice": "hi",
        "lengthScale": 0.85,
        "speakerId": null
      }
    },
    "en": {
      "displayName": "English",
      "stt": {
        "model": "stt_en_fastconformer_ctc_large.int8.onnx",
        "tokens": "tokens.txt",
        "modelType": "nemo_ctc",
        "lexicon": "lexicon.txt"
      },
      "tts": {
        "model": "en_US-hfc_female-medium.onnx",
        "tokens": "en_US-hfc_female-medium.tokens.txt",
        "espeakVoice": "en-us",
        "lengthScale": 0.85,
        "speakerId": null
      }
    },
    "te": {
      "displayName": "తెలుగు",
      "stt": {
        "model": "indicconformer_te.int8.onnx",
        "tokens": "tokens.txt",
        "modelType": "nemo_ctc"
      },
      "tts": {
        "model": "te_IN-female-medium.onnx",
        "tokens": "te_IN-female-medium.tokens.txt",
        "espeakVoice": "te",
        "lengthScale": 0.9,
        "speakerId": null
      }
    },
    "bn": {
      "displayName": "বাংলা",
      "stt": null,
      "tts": {
        "model": "bn_BD-google-medium.onnx",
        "tokens": "bn_BD-google-medium.tokens.txt",
        "espeakVoice": "bn",
        "lengthScale": 0.8,
        "speakerId": 12
      }
    }
  },
  "shared": {
    "espeakDataDir": "espeak-ng-data",
    "numThreads": 4,
    "sttSampleRate": 16000
  }
}
```

> **Note on architecture numbers.** `subsampling_factor`, `vocab_size`, `normalize_type`, and `model_type` are stamped into the STT ONNX metadata by the export pipeline, and sherpa-onnx reads them from the file. They are documented in §4.1 for human reference but **must not be hardcoded** in the app — read them from the model. The STT handoff is explicit on this point.

---

## 5. Architecture

### 5.1 Principles

1. **Adapters isolate model quirks.** Each model sits behind a uniform interface. Bengali's `sid`, English's lexicon repair, Telugu's stray-glyph tolerance — all live inside their respective adapters. The orchestrator knows none of it.
2. **Config is data, not code.** Everything in §4.3 is loaded from JSON.
3. **One stage at a time.** The pipeline is sequential by design (see §6.2 for the memory trade-off this creates).
4. **Fail loudly.** Where the models fail silently, the orchestrator adds explicit validation.

### 5.2 Component diagram

```
┌──────────────────────────────────────────────────────────────┐
│                        UI Layer                              │
│   record button · language picker · message list · SOS       │
└───────────────────────┬──────────────────────────────────────┘
                        │
┌───────────────────────▼──────────────────────────────────────┐
│                     Orchestrator                             │
│   owns config · sequences stages · manages model lifecycle   │
└───┬──────────┬───────────┬───────────┬──────────┬────────────┘
    │          │           │           │          │
┌───▼───┐ ┌────▼────┐ ┌────▼────┐ ┌────▼────┐ ┌───▼──────┐
│  VAD  │ │   STT   │ │   MT    │ │   TTS   │ │  Crypto  │
│Adapter│ │ Adapter │ │ Adapter │ │ Adapter │ │  Module  │
│(§3.1  │ │         │ │         │ │         │ │ AES-GCM  │
│unowned│ │         │ │         │ │         │ │          │
└───┬───┘ └────┬────┘ └────┬────┘ └────┬────┘ └───┬──────┘
    │          │           │           │          │
┌───▼──────────▼───────────▼───────────▼──────────▼────────────┐
│                  sherpa-onnx / ONNX Runtime                  │
└──────────────────────────────────────────────────────────────┘
                        │
┌───────────────────────▼──────────────────────────────────────┐
│              Model Store (filesDir, downloaded)              │
│   per-language STT · per-language TTS · MT core · espeak     │
└──────────────────────────────────────────────────────────────┘
```

### 5.3 Send path

```
User speaks (Telugu)
  │
  ▼ VAD segments utterance ─────────────── [OWNER NEEDED — §3.1]
  │
  ▼ STT  (te)  16kHz float32 mono → Telugu text, no punctuation
  │
  ▼ Orchestrator adds sentence-final punctuation ─── [see §5.5]
  │
  ▼ MT   (te → en)  pivot to English
  │
  ▼ Encrypt  AES-256-GCM
  │
  ▼ Transmit  WiFi Direct (100–200 m) or LoRA (10–15 km)
```

### 5.4 Receive path

```
Encrypted payload arrives
  │
  ▼ Decrypt
  │
  ▼ Is receiver's language == sender's original language?
  │     ├─ yes → use original text (avoids lossy double-translation)
  │     └─ no  → MT (en → receiver's language)
  │
  ▼ Display text
  │
  ▼ (if voice requested) TTS → 22050 Hz mono WAV → playback
```

**Payload design.** Transmit the pivot (English) text *and* the original-language text together. Text is tiny relative to any other payload, and carrying the original lets a receiver who shares the sender's language skip translation entirely — avoiding a lossy Telugu→English→Telugu round trip. It also enables a "show original" UI affordance.

### 5.5 Punctuation restoration — required, not optional

The STT models **physically cannot emit punctuation or casing** — those characters are not in the CTC vocabularies. Output arrives as a bare run of words.

IndicTrans2 was trained on punctuated text. Feeding it unpunctuated input degrades translation quality. The STT handoff is explicit that the orchestrator must handle this: *"If IndicTrans2 quality depends on sentence-final punctuation, the orchestrator must add it — we cannot."*

**Minimum viable approach:** append a full stop to each VAD-segmented utterance. Since VAD segments on speech boundaries, one segment ≈ one sentence. This is crude but correct in the common case and costs nothing.

**If quality is insufficient:** a small punctuation-restoration model is an option, but it is a fourth model in the memory budget — measure first (§3.2), and only add it if the simple approach demonstrably hurts translation quality.

---

## 6. Model lifecycle and memory

### 6.1 The tension

Two constraints pull in opposite directions:

- **Load times are significant.** STT ~1 s, TTS 1.0–3.5 s. Loading per-utterance means 3–6 s of pure loading per message. The STT handoff warns directly: *"load once at startup and reuse; per-utterance loading is the usual cause of a voice feature feeling broken."*
- **RAM is constrained.** Target devices are low-end Android phones. Desktop RSS deltas were ~180–310 MB for TTS alone; STT RAM is unmeasured on any platform.

Keeping all stages resident risks OOM-kills on low-end devices. Loading per-message makes the app feel broken. **Neither extreme is acceptable.**

### 6.2 Recommended strategy: tiered residency

```
STT   ──── load once at app start, keep resident
MT    ──── load on demand, unload after idle timeout (~30 s)
TTS   ──── load on demand, unload after idle timeout (~30 s)
```

Rationale: STT is the latency-critical, user-facing stage (the user is waiting after they stop speaking). MT and TTS run after the user has already committed the action, so a load cost there is less perceptible. The idle timeout means a rapid back-and-forth conversation keeps them warm and pays the load cost only once.

**This recommendation is provisional and must be validated against §3.2.** If measured RAM allows all three resident on a mid-range device, keep them all resident — it is strictly better UX. If RAM is tighter than expected, fall back to strict sequential loading and accept the latency.

**Non-negotiable regardless:** never hold two ONNX sessions for the *same* stage, and always release sessions explicitly rather than relying on GC.

### 6.3 Threading

`numThreads = 4` for all models. Both collaborators independently measured 4 threads as ~2–3x faster than the sherpa-onnx default of 1, with no measurable RAM increase. The cost is a brief CPU/battery burst during inference, not sustained load.

Re-benchmark on target hardware — big.LITTLE scheduling on low-end phones may favour 2.

### 6.4 Execution provider

**CPU only.** NNAPI is untested by both collaborators. VITS and Conformer graphs both contain ops that NNAPI partitions poorly, causing CPU fallback with added copy overhead. Treat NNAPI as an optional A/B experiment on real hardware, not a planned optimization.

---

## 7. Adapter specifications

### 7.1 Common interface

```kotlin
interface SttAdapter {
    /** @param audio 16 kHz mono float32 in [-1, 1]
     *  @return native-script text, no punctuation, no casing, numbers as words */
    fun transcribe(audio: FloatArray): String
    fun close()
}

interface MtAdapter {
    fun translate(text: String, sourceLang: String, targetLang: String): String
    fun close()
}

interface TtsAdapter {
    /** @return 22050 Hz mono 16-bit PCM */
    fun synthesize(text: String): ByteArray
    fun close()
}
```

### 7.2 STT adapter

**Audio capture.** Request 16 kHz directly from `AudioRecord`. Capture `PCM_16BIT` and divide by `32768f` to get float32 in [-1, 1]. Resampling from 44.1/48 kHz works but adds a step — avoid it.

**Do not pre-normalize features.** sherpa-onnx computes the 80-bin mel fbank with `per_feature` normalization internally. Pre-normalizing corrupts the input.

**Critical:** `normalize_type` must be `per_feature`. If it is ever overridden to anything else, the model **loads cleanly and outputs noise** — the STT handoff calls this "the single most confusing failure in this stack." It is stamped correctly in the shipped files; the risk is only if someone overrides it.

**Model and tokens must come from the same export.** Mixing another language's `tokens.txt` produces empty or wrong output with no error.

**Integrity check.** Verify against `SHA256SUMS.txt` after download. A truncated download loads successfully and then emits garbage, which looks exactly like a model bug.

**Language selection is explicit — never auto-detect.** Each model returns confident in-vocabulary nonsense for the wrong language rather than an error. Do not run multiple models and pick a winner; there is no signal to pick on.

#### 7.2.1 English lexicon repair

English STT's dominant weakness is proper nouns — it was trained on US/European English and spells unfamiliar Indian names phonetically ("Bengaluru" → *bangaloru*, "Lucknow" → *luknaow*, "Rajiv Chowk" → *raji chok*).

Shivanshu built a repair pass (`scripts/lexicon.py`, ~40 lines, straightforward to port to Kotlin):

- For each known phrase of *n* words, compare against every span of *n−1*, *n*, and *n+1* words in the transcript. Word boundaries rarely align, so a fixed width misses most matches.
- Score = `0.5 × string similarity + 0.5 × Soundex-key similarity`, on space-stripped lowercased strings. Soundex is what makes this work — it collapses exactly the vowel guesswork a phonetic speller gets wrong.
- Substitute at score **≥ 0.70**, highest-scoring first, never rewriting a word twice. Ignore phrases under 5 characters.
- Measured separation: correct targets score 0.85–1.00, unrelated text tops out near 0.27. The threshold sits in a wide gap.

`lexicon.txt` is a plain one-phrase-per-line file **meant to be edited** — add app place names, menu items, and domain terms. It has its own hash in `SHA256SUMS.txt` and can be updated without re-shipping the 174 MB model.

**Personal names are NOT repairable this way.** Test names scored 0.28–0.51 while ordinary phrases like "station is" scored 0.43 against the same target — any threshold low enough to catch the name starts rewriting normal words into it. Get personal names from typed entry, or match against a small closed list (the user's contacts) with a confirmation step. This is a product decision, not a model fix.

**Do not run lexicon repair on Telugu or Hindi.** Soundex is Latin-only; on non-Latin script it is a silent no-op. Harmless, but pointless.

#### 7.2.2 Telugu stray-glyph tolerance

Telugu STT uses a **shared 12-language vocabulary** (5632 tokens spanning Devanagari, Bengali, Kannada, Telugu, and Latin, plus blank) rather than a Telugu-specific one. The model can therefore emit a non-Telugu character when it mishears.

**The MT layer must tolerate this.** A stray Devanagari or Latin glyph in Telugu output is expected behaviour, not a script-detection failure, and must not trigger an error path.

Telugu's error profile is also favourable: 23.7% WER against 7.7% CER means errors are near-misses — one inflection or word boundary off — rather than the model losing the plot. Text is usually close enough for MT to recover in context.

### 7.3 TTS adapter

**Bengali requires `sid=12` on every call.** See §4.2, Trap 2. Read it from config; never hardcode.

**`length_scale` must be set per language via `OfflineTtsVitsModelConfig`.** It cannot be baked into the ONNX file — Raj verified that sherpa-onnx ignores a `length_scale` entry in model metadata. Values in §4.1.

**Metadata patching is a build-time requirement.** Raw Piper ONNX exports lack sherpa-onnx's required `metadata_props` and crash native init with `'sample_rate' does not exist in the metadata`. See §8.2.

**espeak voice code is set once in model config**, not per request.

**Native script required.** Romanized or code-mixed input is untested and not expected to work — espeak-ng's phonemizer is script-specific.

**Apply a length cap.** No hard cap is enforced by the models, but self-attention memory scales with sequence length squared. Chunk at sentence level. Longest tested inputs: Hindi 182 chars, Telugu 193 chars / 259 phonemes; English and Bengali unprofiled.

### 7.4 MT adapter

Not yet built. See the separate export/quantization scripts already delivered (`export_indictrans2_onnx.py`, `quantize_and_verify.py`).

**Outstanding work:**
- Run the export against the real IndicTrans2-Dist checkpoints (requires huggingface.co access — Kaggle/Colab/local, not the sandbox those scripts were written in)
- Port **IndicProcessor** preprocessing (sentence normalization, number/URL placeholder substitution) to Kotlin — rule-based, but real work, and MT quality degrades badly without it
- Cap `max_length` and use greedy or beam width 1–2 — chat messages are short and do not need translation-grade beam search

---

## 8. Build and packaging pipeline

### 8.1 STT export (already built)

Shivanshu's `scripts/export_onnx.py` runs five steps, each skipping itself if its output exists:

| Step | Requires | Output |
|---|---|---|
| 1. export | NeMo, torch | fp32 ONNX (~460–490 MB) from `.nemo` |
| 2. tokens | PyYAML | `tokens.txt` from `decoder.vocabulary` in `model_config.yaml` |
| 3. quantize | onnxruntime | int8 ONNX, dynamic, **MatMul only** |
| 4. meta | onnx | stamps `normalize_type`, `subsampling_factor`, `vocab_size`, `model_type` |
| 5. verify | sherpa-onnx, soundfile | decodes a real clip through sherpa-onnx; cross-checks vs `.nemo` |

Only step 1 needs NeMo/torch, so steps 1–2 can run in Colab/WSL and the rest anywhere.

**Three things that break an STT export:**
- `normalize_type` must be `per_feature` — otherwise the model loads cleanly and outputs noise
- `model.eval()` before export — disables preprocessor dither, sets Conformer BatchNorm to inference mode
- Quantize **MatMul only** — Conformer's depthwise Conv + BatchNorm loses more accuracy to int8 than it saves in size

**Adding a fourth language:** the same script works unchanged with `--nemo` and `--out` pointed at a new AI4Bharat IndicConformer checkpoint. A model exported elsewhere joins at step 3 with `--vocab-json`, `--normalize-type`, `--subsampling-factor`.

### 8.2 TTS metadata patching (needs scripting)

Raj currently patches sherpa-onnx metadata manually per model. **This should be a scripted build step**, not a per-language ritual someone has to remember.

Fields to inject: `model_type`, `comment`, `language`, `voice`, `n_speakers`, `sample_rate`.

Without it, sherpa-onnx native init crashes on `'sample_rate' does not exist in the metadata`.

### 8.3 Quantization policy differs by stage — this is correct

| Stage | Policy | Reason |
|---|---|---|
| **STT** | int8 dynamic, **MatMul only** | 459→177 MB. Measured cost: 0.2 points WER absolute on Telugu (23.5%→23.7%). Clear win. |
| **TTS** | **fp32, not quantized** | int8 measured **~3x SLOWER** (0.69 s → 2.18 s). HiFi-GAN decoder Conv layers have no efficient int8 CPU kernel. |
| **MT** | int8 dynamic | Standard for seq2seq; not yet validated on this stack |

TTS is instead graph-optimized with ONNX Runtime's `ORT_ENABLE_EXTENDED`. `ORT_ENABLE_ALL` was deliberately avoided — its hardware-specific fusions do not reliably transfer to a different device than the one that ran the optimization.

> **Caveat worth testing.** Raj's int8-is-slower result was measured on a **Windows desktop x86 CPU**. ARM and x86 have different int8 kernel support in ONNX Runtime — ARM builds can use NEON dot-product instructions that generic x86 kernels lack. The fp32 default is the correct, tested choice for now, but one on-device A/B is worth running before treating "never quantize TTS" as permanent policy. The stake is ~60 MB vs ~15 MB per voice.

### 8.4 Bundle layout

```
filesDir/
├── config/
│   └── languages.json          ← §4.3
├── shared/
│   └── espeak-ng-data/         ← ~19 MB, all TTS languages
├── mt/
│   ├── encoder.int8.onnx
│   ├── decoder.int8.onnx
│   └── tokenizer/
└── lang/
    ├── hi/
    │   ├── stt/  indicconformer_hi.int8.onnx, tokens.txt, SHA256SUMS.txt
    │   └── tts/  hi_IN-female-medium.onnx, .tokens.txt, .onnx.json
    ├── en/
    │   ├── stt/  ...int8.onnx, tokens.txt, lexicon.txt, SHA256SUMS.txt
    │   └── tts/  en_US-hfc_female-medium.onnx, ...
    ├── te/
    │   ├── stt/  indicconformer_te.int8.onnx, tokens.txt, SHA256SUMS.txt
    │   └── tts/  te_IN-female-medium.onnx, ...
    └── bn/
        └── tts/  bn_BD-google-medium.onnx, ...   (no STT — §3.4)
```

Verify every download against `SHA256SUMS.txt` before first use.

---

## 9. Implementation plan

Ordered by dependency, not by preference.

### Phase 0 — Unblock (do first)

| # | Task | Owner | Blocks |
|---|---|---|---|
| 0.1 | Decide packaging model (§2.3) | Team | Everything downstream |
| 0.2 | Assign VAD owner (§3.1) | Team | End-to-end demo |
| 0.3 | Measure on-device RAM for STT + TTS (§3.2) | Shivanshu + Raj | §6.2 residency decision |
| 0.4 | Add Telugu CC-BY attribution screen (§3.3) | App dev | Shipping |
| 0.5 | Decide Bengali receive-only vs. source STT (§3.4) | Team | Scope |

### Phase 1 — Foundations

| # | Task | Depends on |
|---|---|---|
| 1.1 | Model download + SHA256 verification into `filesDir` | 0.1 |
| 1.2 | `languages.json` config loader | — |
| 1.3 | Commit `espeak-ng-data/` to repo (§3.5) | — |
| 1.4 | Script the TTS metadata patcher (§8.2) | — |

### Phase 2 — Adapters

| # | Task | Depends on |
|---|---|---|
| 2.1 | STT adapter + `AudioRecord` capture at 16 kHz | 1.1, 1.2 |
| 2.2 | TTS adapter (incl. Bengali `sid`, per-language `length_scale`) | 1.1, 1.2, 1.4 |
| 2.3 | VAD adapter (Silero via sherpa-onnx) | 0.2 |
| 2.4 | Port English lexicon repair to Kotlin (§7.2.1) | 2.1 |

### Phase 3 — Translation

| # | Task | Depends on |
|---|---|---|
| 3.1 | Run IndicTrans2 export + quantize on real checkpoints | — |
| 3.2 | Port IndicProcessor preprocessing to Kotlin | 3.1 |
| 3.3 | MT adapter | 3.1, 3.2 |

### Phase 4 — Orchestration

| # | Task | Depends on |
|---|---|---|
| 4.1 | Orchestrator: stage sequencing + model lifecycle (§6.2) | 2.x, 3.3, 0.3 |
| 4.2 | Punctuation restoration (§5.5) | 4.1 |
| 4.3 | AES-256-GCM encrypt/decrypt around transmission | 4.1 |
| 4.4 | Payload format (pivot + original text) | 4.1 |

### Phase 5 — Integration testing

| # | Task |
|---|---|
| 5.1 | End-to-end: Telugu speech → Hindi speech on real devices |
| 5.2 | Validate Telugu STT on phone-mic audio (§3.5) |
| 5.3 | Verify Bengali TTS emits a female voice (guards Trap 2) |
| 5.4 | Verify English STT timestamps (guards Trap 1) |
| 5.5 | Memory profiling under sustained conversation |

---

## 10. Risk register

| Risk | Impact | Likelihood | Mitigation |
|---|---|---|---|
| App size exceeds delivery limits | High | **Confirmed** | §2.3 — packaging decision |
| OOM on low-end devices | High | Unknown (unmeasured) | §3.2 then §6.2 |
| No VAD → no working demo | High | High if unassigned | §3.1 — assign owner now |
| Bengali ships with male voice | Medium | Medium (silent failure) | §4.2 Trap 2 + test 5.3 |
| English timestamps misaligned | Medium | Medium (silent failure) | §4.2 Trap 1 + test 5.4 |
| Telugu STT worse than 23.7% on phone mic | Medium | High | §5.2 — validate, plan demo script around it |
| MT quality poor on code-mixed chat text | Medium | Medium | IndicProcessor (§7.4); LoRA fine-tune is a v2 option |
| Telugu CC-BY attribution missed | Medium | Low | §3.3 |
| Proper nouns garbled in English STT | Medium | **Confirmed** | Lexicon repair (§7.2.1); typed entry for personal names |
| Load times make app feel broken | Medium | Medium | §6.2 tiered residency |

---

## 11. Performance reference

All figures **desktop CPU, 4 threads**. No on-device measurement exists (§3.2). Estimated phone RTF is 10–20x desktop — still comfortably within realtime.

| Stage | Language | RTF | Load time | Per-utterance | RAM (desktop RSS Δ) |
|---|---|---|---|---|---|
| STT | Hindi | 0.030 | ~1 s | — | not measured |
| STT | English | 0.016 | ~1 s | ~0.1 s (4 s audio) | not measured |
| STT | Telugu | 0.029 | ~1 s | — | not measured |
| TTS | Hindi | 0.062–0.175 | 1.5–2.6 s | 220–630 ms | ~210 MB |
| TTS | English | 0.052–0.168 | 1.4–3.5 s | 260–690 ms | ~180–200 MB |
| TTS | Telugu | 0.055–0.180 | 1.0–3.2 s | 210–710 ms | ~180–210 MB |
| TTS | Bengali | 0.053–0.190 | 0.8–2.6 s | 250–730 ms | ~200–310 MB |

**Accuracy (Google FLEURS, 50 clips, clean read speech):** Hindi 12.2% WER · English 12.5% WER · Telugu 23.7% WER / 7.7% CER.

Caveats carried forward from the handoffs: TTS RTF varied 3x run-to-run on identical hardware with no other load. STT accuracy is clean read speech — noisy, far-field, and accented phone-mic audio is not represented. Indian-accented English is not what the English checkpoint was trained on; expect worse than 12.5% on demo audio.

---

## 12. Contacts

| Area | Owner | Contact |
|---|---|---|
| TTS (4 languages), VAD | Raj | rajaryan08161@gmail.com |
| STT (3 languages) | Shivanshu Vats | vatsshivanshu05@gmail.com |
| MT / translation | Unassigned | — |
| Orchestrator / app | — | — |

**Repositories:** TTS — `github.com/Naitik328/itantra`, branch `ai_raj` (folders `hindifinal/`, `englishfinal/`, `telegufinal/`, `bengalifinal/`). STT — `dist/indicconformer_hi_android/`, `dist/fastconformer_en_android/`, `dist/indicconformer_te_android/`.

**Update convention (both collaborators):** version tag + sha256 of the new file, direct message, one-line changelog. A changed hash is the signal that anything changed.

---

*Compiled 2026-09-03 from the two collaborator handoff documents. Every performance figure, size, and configuration value traces to one of those two sources; items neither document measured are marked "not measured" rather than estimated.*
