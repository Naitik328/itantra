# CLAUDE.md — iTantra voice pipeline

Operating guide for Claude Code working in this repo. Read this fully before writing code.

**Reference document:** `docs/ITANTRA_INTEGRATION_SPEC.md` is the source of truth for every number, model contract, and configuration value. This file tells you how to work; that file tells you what is true. When they disagree, the spec wins — and flag the discrepancy.

---

## 1. What this project is

An Android app for **fully offline** voice messaging across Indian languages. A user speaks in their language; the recipient hears it in theirs. No internet, no cellular, no API calls.

Pipeline: **speech → STT → text → MT → text → TTS → speech**, with AES-256-GCM on the wire. Transport is WiFi Direct (100–200 m) or a clip-on LoRA extender (10–15 km).

Four languages have models today: Hindi, English, Telugu (STT + TTS) and Bengali (TTS only).

**Stack:** Kotlin / Android · sherpa-onnx (single inference runtime for both STT and TTS) · ONNX Runtime underneath · models loaded from `filesDir`, not APK assets.

---

## 2. STOP — decisions you must not make alone

These are blocked on humans. **Do not guess, do not pick a default, do not implement around them.** If a task depends on one, say so and stop.

| # | Decision | Why you can't decide it | Spec |
|---|---|---|---|
| D1 | **Packaging model** — first-launch download vs. onboarding language picker vs. Play Asset Delivery | Changes app architecture, UX, and the SIH pitch. ~600 MB of models cannot ship in an APK. | §2.3 |
| D2 | **VAD owner** | Unassigned across both collaborator handoffs. Nothing live works without it. | §3.1 |
| D3 | **Model residency** — all resident vs. tiered vs. strict sequential | Depends on on-device RAM, which **nobody has measured on any platform**. The spec's tiered recommendation is provisional. | §6.2 |
| D4 | **Bengali scope** — receive-only, or source a Bengali STT model | Bengali has TTS but no STT. Product decision. | §3.4 |
| D5 | **Punctuation strategy** — naive full-stop vs. a restoration model | A model here is a 4th thing in the memory budget. Needs D3 first. | §5.5 |

If asked to "just build the app," build the parts that don't depend on these (§5 Phase 1), and report which tasks are blocked.

---

## 3. Repo structure

Create this layout. Paths in code should match exactly.

```
itantra/
├── CLAUDE.md
├── README.md
├── docs/
│   ├── ITANTRA_INTEGRATION_SPEC.md      ← reference; do not edit without team sign-off
│   ├── handoff_tts.pdf                   ← Raj's filled requirements
│   └── handoff_stt.pdf                   ← Shivanshu's filled requirements
│
├── android/
│   ├── app/
│   │   └── src/main/
│   │       ├── assets/
│   │       │   └── config/languages.json ← §4.3 of spec, verbatim
│   │       ├── kotlin/com/itantra/
│   │       │   ├── config/
│   │       │   │   ├── LanguageConfig.kt      ← data classes for languages.json
│   │       │   │   └── ConfigLoader.kt
│   │       │   ├── models/
│   │       │   │   ├── ModelStore.kt          ← download, verify, path resolution
│   │       │   │   └── Sha256Verifier.kt
│   │       │   ├── adapters/
│   │       │   │   ├── SttAdapter.kt          ← interface
│   │       │   │   ├── SherpaSttAdapter.kt
│   │       │   │   ├── TtsAdapter.kt          ← interface
│   │       │   │   ├── SherpaTtsAdapter.kt
│   │       │   │   ├── MtAdapter.kt           ← interface
│   │       │   │   ├── OnnxMtAdapter.kt
│   │       │   │   ├── VadAdapter.kt          ← blocked on D2
│   │       │   │   └── postprocess/
│   │       │   │       └── LexiconRepair.kt   ← English only; see §7.2.1
│   │       │   ├── orchestrator/
│   │       │   │   ├── Orchestrator.kt        ← stage sequencing
│   │       │   │   ├── ModelLifecycle.kt      ← load/unload; blocked on D3
│   │       │   │   └── Punctuation.kt         ← blocked on D5
│   │       │   ├── crypto/
│   │       │   │   └── MessageCrypto.kt       ← AES-256-GCM
│   │       │   ├── transport/
│   │       │   │   ├── WifiDirectTransport.kt
│   │       │   │   └── LoraTransport.kt       ← USB-C / Bluetooth to SX1262
│   │       │   └── ui/
│   │       └── res/
│   └── build.gradle.kts
│
├── tools/
│   ├── export_indictrans2_onnx.py       ← already written; not yet run on real weights
│   ├── quantize_and_verify.py           ← already written
│   ├── patch_tts_metadata.py            ← TO BUILD; see §4.2 below
│   └── bundle_models.sh                 ← TO BUILD; assembles the layout in §4.4
│
└── models/                              ← gitignored; build artifacts only
    └── (see §8.4 of spec for on-device layout)
```

`models/` must be in `.gitignore`. These are 60–190 MB files; they do not belong in git.

---

## 4. Non-negotiable technical rules

Violating any of these produces **silent failures** — plausible wrong output, no exception, no log. They cost the collaborators real debugging time. Do not rediscover them.

### 4.1 Never hardcode per-language values

Everything language-specific comes from `languages.json`. If you find yourself typing a literal `4`, `8`, `0`, `12`, `0.85`, `0.9`, or `0.8` inside adapter logic, stop — it belongs in config.

Two traps specifically:

- **English STT uses 8x subsampling**; Hindi and Telugu use 4x. A `4` copied from the Hindi path silently misplaces every timestamp.
- **Bengali TTS `sid=0` is a MALE speaker.** The correct female voice is `sid=12`. Every Bengali `synthesize()` call must pass it explicitly. A call copy-pasted from another language produces a male voice with no error.

Architecture numbers (`subsampling_factor`, `vocab_size`, `normalize_type`, `model_type`) are **stamped into the STT ONNX metadata** and sherpa-onnx reads them from the file. Read them from the model; the spec documents them for humans, not for hardcoding.

### 4.2 TTS models need metadata patched before they load

Raw Piper ONNX exports lack sherpa-onnx's `metadata_props` and crash native init with `'sample_rate' does not exist in the metadata`.

Raj patches this manually today. **Build `tools/patch_tts_metadata.py`** to script it. Fields: `model_type`, `comment`, `language`, `voice`, `n_speakers`, `sample_rate`.

### 4.3 Quantization policy differs by stage — this is deliberate

| Stage | Policy | Do not "fix" this |
|---|---|---|
| STT | int8 dynamic, **MatMul only** | Conformer's depthwise Conv + BatchNorm loses more accuracy to int8 than it saves |
| TTS | **fp32, NOT quantized** | int8 measured **3x slower** — HiFi-GAN Conv layers have no efficient int8 CPU kernel |
| MT | int8 dynamic | Standard for seq2seq |

If you see unquantized TTS and think it's an oversight: it isn't. See spec §8.3.

### 4.4 Audio contract

- STT input: **16 kHz mono float32 in [-1, 1]**. Capture `PCM_16BIT` from `AudioRecord` at 16 kHz directly, divide by `32768f`.
- **Do not pre-normalize features.** sherpa-onnx computes the 80-bin mel fbank with `per_feature` normalization internally.
- TTS output: 22,050 Hz mono 16-bit PCM.
- STT and TTS sample rates differ — that's fine, they never touch. MT sits between them passing text.

### 4.5 Never auto-detect language

Every model returns **confident in-vocabulary nonsense** for the wrong language rather than an error. Do not run several models and pick a winner — there is no signal to pick on. Language selection is explicit and user-driven.

### 4.6 Verify downloads

Check every model against its `SHA256SUMS.txt` before first use. A truncated download loads successfully and then emits garbage, which looks exactly like a model bug.

### 4.7 Model + tokens must come from the same export

Mixing another language's `tokens.txt` gives empty or wrong output with no error.

---

## 5. Build order

Work top to bottom. Each phase depends on the one above.

### Phase 1 — Unblocked, start here

Nothing in this phase depends on D1–D5.

1. **`ConfigLoader.kt` + `LanguageConfig.kt`** — parse `languages.json` (spec §4.3, copy verbatim into assets). Data classes for STT config, TTS config, shared config. Handle `stt: null` (Bengali) without crashing.
2. **`Sha256Verifier.kt`** — verify a file against an expected hash.
3. **`tools/patch_tts_metadata.py`** — see §4.2.
4. **`LexiconRepair.kt`** — port Shivanshu's `scripts/lexicon.py` (~40 lines). Algorithm in spec §7.2.1: compare each known phrase against every span of n−1/n/n+1 words; score `0.5 × string similarity + 0.5 × Soundex similarity`; substitute at ≥ 0.70, highest first, never rewrite a word twice, ignore phrases under 5 chars. **English only** — Soundex is Latin-only and is a silent no-op on Indic script.
5. **`MessageCrypto.kt`** — AES-256-GCM. Use GCM, not CBC. Hardware-accelerated on essentially every target device; negligible latency.

### Phase 2 — After D1 (packaging)

6. `ModelStore.kt` — download into `filesDir`, verify, resolve paths. Path-based sherpa-onnx construction, not asset-based.
7. Onboarding/download UI.

### Phase 3 — Adapters

8. `SherpaSttAdapter.kt` — `OfflineRecognizer.from_nemo_ctc(...)`, `modelType = "nemo_ctc"`, `numThreads = 4`.
9. `SherpaTtsAdapter.kt` — `OfflineTtsVitsModelConfig` with per-language `espeakVoice` and `lengthScale`; pass `sid` when non-null.
10. `VadAdapter.kt` — Silero via sherpa-onnx. **Blocked on D2.**

### Phase 4 — Translation

11. Run `tools/export_indictrans2_onnx.py` against real IndicTrans2-Dist checkpoints. Needs `huggingface.co` access — run on Colab/Kaggle/local, not in a sandbox. Run `--introspect_only` first.
12. Port **IndicProcessor** preprocessing to Kotlin (sentence normalization, number/URL placeholders). Rule-based but real work. MT quality degrades badly without it.
13. `OnnxMtAdapter.kt` — cap `max_length`, beam width 1–2 (chat messages are short).

### Phase 5 — Orchestration

14. `Orchestrator.kt` — sequence the stages, own all config.
15. `ModelLifecycle.kt` — **blocked on D3.**
16. `Punctuation.kt` — **blocked on D5.** STT emits no punctuation ever; IndicTrans2 expects it.
17. Payload format: transmit **pivot (English) text + original-language text together**. Text is tiny; carrying the original lets a receiver who shares the sender's language skip a lossy double-translation and enables "show original."

---

## 6. Verification

Before calling any adapter done:

| Check | How |
|---|---|
| STT produces real text | Decode a known clip; compare against the transcript in the handoff |
| **English timestamps correct** | Guards the 8x trap — verify against known word boundaries |
| **Bengali TTS is female** | Guards the `sid` trap — check median pitch ≈ 257 Hz, or just listen |
| TTS speech rate sane | Per-language `length_scale` applied |
| No hardcoded language values | grep adapters for literal `4`, `8`, `12`, `0.85`, `0.9`, `0.8` |
| Model integrity | SHA256 matches |

The two trap-guard rows are not optional. Both failures are inaudible in code review and obvious only at runtime.

---

## 7. Working conventions

- **Report blockers rather than routing around them.** If a task needs D1–D5, say which and stop.
- **Don't invent numbers.** Every figure in the spec traces to a collaborator measurement. If something is marked "not measured," it is genuinely unmeasured — don't substitute an estimate.
- **Keep config in JSON, not Kotlin.** Adding a language should mean editing `languages.json` and dropping in files, not writing code.
- **Adapters own their quirks.** Bengali `sid`, English lexicon repair, Telugu stray-glyph tolerance — all inside the relevant adapter. The orchestrator knows none of it.
- **Model files never enter git.**
- **Attribution:** Telugu STT is CC-BY-4.0 and requires crediting AI4Bharat and the trysem export in an about/licenses screen. This is a shipping blocker, not a nice-to-have.

---

## 8. Known model behaviour (don't file these as bugs)

- **No punctuation, no casing, ever.** The CTC vocabularies do not contain those characters. Numbers come out as words, never digits.
- **Telugu can emit stray non-Telugu glyphs.** Its vocabulary is shared across 12 languages (5633 classes). A stray Devanagari or Latin character is expected on a mishearing — the MT layer must tolerate it rather than treat it as a script-detection failure.
- **English garbles Indian proper nouns.** "Bengaluru" → *bangaloru*, "Lucknow" → *luknaow*. Fixed by lexicon repair for place names and app terms. **Personal names are not fixable this way** — any threshold low enough to catch them starts rewriting ordinary words. Use typed entry or a contacts list with confirmation.
- **Telugu WER is ~2x Hindi/English** (23.7% vs 12.2%/12.5%). Expected for a lower-resource language. CER is only 7.7%, meaning errors are near-misses rather than derailments — usually recoverable in context.
- **Silence returns an empty string**, not invented words.
- **`normalize_type` must stay `per_feature`.** Override it and the model loads cleanly and outputs noise — the most confusing failure in this stack.

---

## 9. Contacts

| Area | Owner |
|---|---|
| TTS (4 languages), VAD | Raj — rajaryan08161@gmail.com |
| STT (3 languages) | Shivanshu Vats — vatsshivanshu05@gmail.com |
| MT / translation | Unassigned |
| Orchestrator / app | — |

Model repo: `github.com/Naitik328/itantra`, branch `ai_raj`.
Update convention: version tag + sha256 + one-line changelog. A changed hash means something changed.
