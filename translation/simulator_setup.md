# On-device MT test setup (handoff)

For a collaborator setting up Android testing of the translation (MT)
module — with or without real STT/TTS — on their own machine. Branch:
`translation-mt`. If anything here disagrees with `translation_state.md`,
that file is the living source of truth; update this doc to match, not
the other way around.

## What you're testing

`OnnxMtAdapter` (in-graph SentencePiece tokenization via
onnxruntime-extensions, no KV cache) has been verified against real
IndicTrans2 weights on desktop — every language pair the app uses
(hi→en, te→en, en→hi, en→te, en→bn) produces correct translations, and
the Kotlin source compiles cleanly against real dependency jars. What has
**never been run** is the actual ONNX inference through Android's real
native `onnxruntime-extensions` library — desktop Linux can't do this
(that library only ships for Android ABIs). That's what this setup is for.

See `translation/translation_state.md` for the full history and exactly
what's verified vs. not; `translation/README.md` for the `IndicProcessor`/
`OnnxMtAdapter` design details.

---

## 1. Read first

| File | Purpose |
|---|---|
| `docs/CLAUDE.md` | Operating rules for this repo — non-negotiables, silent-failure traps, build order |
| `docs/ITANTRA_INTEGRATION_SPEC.md` | The full spec — config schema (§4.3), adapter interfaces (§7.1), send/receive pipeline (§5.3/§5.4) |
| `translation/translation_state.md` | Current status, what's verified, what's not, full history |
| `translation/README.md` | Deep-dive on `IndicProcessor`/`OnnxMtAdapter` — what's proven vs. assumed |
| `translation/android_smoketest/README.md` | Exact steps to build and run the MT-only on-device test |

## 2. MT source code

Already on `translation-mt` — `git checkout`/merge gets you all of this,
no separate action needed.

| File(s) | Purpose |
|---|---|
| `translation/kotlin/com/itantra/mt/*.kt` | `IndicProcessor` (preprocessing/postprocessing) + `OnnxMtAdapter` (the ONNX inference adapter) |
| `translation/kotlin/com/itantra/config/*.kt` | `languages.json` data classes + parser |
| `translation/kotlin/com/itantra/adapters/SttAdapter.kt`, `TtsAdapter.kt` | **Interfaces only** — spec §7.1, verbatim. Real STT/TTS classes need to implement these |
| `translation/kotlin/com/itantra/orchestrator/*.kt` | `Orchestrator` (send/receive pivot sequencing) + `Punctuation` |
| `translation/config/languages.json` | Config asset — has an `mt` block in addition to STT/TTS |
| `translation/kotlin_verify/` | Desktop compile+logic check — not needed for the on-device test itself, but worth re-running after editing any `.kt` file (see its own `verify.sh`) |

## 3. On-device MT test harness

Built, never run — this is the first real test of it.

| File(s) | Purpose |
|---|---|
| `translation/android_smoketest/app/build.gradle.kts` | Gradle module wiring `onnxruntime-android` + `onnxruntime-extensions-android` as real dependencies |
| `translation/android_smoketest/app/src/androidTest/kotlin/.../OnnxMtAdapterInstrumentedTest.kt` | The actual test — constructs a real `OnnxMtAdapter`, translates known sentences, asserts against known-good output |
| `translation/android_smoketest/push_models.sh` | Pushes the model bundle (§4 below) onto a connected device/emulator |
| `translation/android_smoketest/gradle/wrapper/gradle-wrapper.properties` | Pins Gradle 8.9 — `gradlew` itself isn't checked in; run `gradle wrapper` once, or just open the folder in Android Studio |
| `translation/android_smoketest/README.md` | Build/run commands, and what to check if it fails (ABI mismatch, native lib packaging, etc.) |

## 4. MT model files — **not in git**, must be transferred or regenerated

`models/` (and everything under it) is gitignored per `docs/CLAUDE.md`
§3 — these are large binaries, they never belong in git.

| File (per direction: `en-indic/`, `indic-en/`) | Purpose | Size |
|---|---|---|
| `encoder.int8.onnx` | Tokenizer + encoder, quantized | 72–120 MB |
| `decoder.int8.onnx` | Decoder, quantized | 89–134 MB |
| `vocab_ids.json` | `decoder_start_id`, `eos_id`, per-language tag ids | 4 KB |
| `tgt_vocab.json` | id→piece array for detokenizing | 360 KB–2.2 MB |

Two ways to get these (~415 MB total for both directions):

- **Receive them directly** — ask whoever ran the export (sitting at
  `~/itantra-mt-export/` on the machine that built this) to transfer the
  four files per direction above.
- **Regenerate them yourself** — run `tools/export_indictrans2_onnx.py`
  then `tools/quantize_and_verify.py` for both `--direction en-indic` and
  `--direction indic-en`, with your own HF-authenticated environment
  (`pip install -r tools/requirements.txt`; you'll need to accept the
  gated license on both `ai4bharat/indictrans2-{en-indic,indic-en}-dist-200M`
  model pages first). `tools/colab_export.ipynb` is a ready-to-run
  alternative if you'd rather not set up Python locally.

Once you have them, lay them out as:
```
<wherever you keep exports>/
├── en-indic/{encoder.int8.onnx, decoder.int8.onnx, vocab_ids.json, tgt_vocab.json}
└── indic-en/{encoder.int8.onnx, decoder.int8.onnx, vocab_ids.json, tgt_vocab.json}
```
then `translation/android_smoketest/push_models.sh /path/to/that/dir`.

## 5. STT/TTS — not part of this branch at all

If you want STT → MT → TTS all working together (not just the MT-only
smoke test), you'll need these too. **None of this is on `translation-mt`**
— filenames below are what `translation/config/languages.json` and spec
§4.3/§8.4 declare; get the actual files from `ai_abhi`/wherever
Shivanshu/Raj keep them, and verify against their `SHA256SUMS.txt` before
trusting a filename match (docs/CLAUDE.md §4.6 — a truncated download
loads cleanly and emits garbage, which looks exactly like a model bug).

| File (per language dir `lang/<code>/stt/` or `tts/`) | Purpose |
|---|---|
| `lang/hi/stt/indicconformer_hi.int8.onnx` | Hindi STT model |
| `lang/hi/stt/tokens.txt` | Hindi STT vocabulary — must come from the *same export* as the model above (CLAUDE.md #4.7) |
| `lang/hi/tts/hi_IN-female-medium.onnx` | Hindi TTS voice |
| `lang/hi/tts/hi_IN-female-medium.tokens.txt` | Hindi TTS tokenizer |
| `lang/en/stt/stt_en_fastconformer_ctc_large.int8.onnx` | English STT model — **8x subsampling, not 4x** (CLAUDE.md's Trap 1) |
| `lang/en/stt/tokens.txt` | English STT vocabulary |
| `lang/en/stt/lexicon.txt` | English proper-noun repair list (spec §7.2.1) — English only, editable without re-shipping the model |
| `lang/en/tts/en_US-hfc_female-medium.onnx` | English TTS voice |
| `lang/en/tts/en_US-hfc_female-medium.tokens.txt` | English TTS tokenizer |
| `lang/te/stt/indicconformer_te.int8.onnx` | Telugu STT model — CC-BY-4.0, **attribution is a shipping blocker** (spec §3.3) |
| `lang/te/stt/tokens.txt` | Telugu STT vocabulary (shared 12-language, 5633 classes — stray non-Telugu glyphs are expected, spec §7.2.2) |
| `lang/te/tts/te_IN-female-medium.onnx` | Telugu TTS voice |
| `lang/te/tts/te_IN-female-medium.tokens.txt` | Telugu TTS tokenizer |
| `lang/bn/tts/bn_BD-google-medium.onnx` | Bengali TTS voice — **16-speaker model, `sid=12` required or you get a male voice with no error** (CLAUDE.md's Trap 2). No `lang/bn/stt/` — Bengali has no STT (spec §3.4) |
| `lang/bn/tts/bn_BD-google-medium.tokens.txt` | Bengali TTS tokenizer |
| `shared/espeak-ng-data/` | ~19 MB, shared across all four TTS voices |
| `<lang>/stt/SHA256SUMS.txt`, `<lang>/tts/SHA256SUMS.txt` | Per-directory integrity check — verify before first use, every time |

**One thing worth checking, not assuming:** at the start of this work, this
machine's checkout of `ai_abhi` had `models/tts/hi_IN/hi_IN-finetune-medium.onnx`
on disk — a different filename than `languages.json`'s
`hi_IN-female-medium.onnx` above. That may just mean the file was renamed
since, or it may be a real mismatch between config and what's actually
shipped. Confirm the real filename before wiring `ConfigLoader`'s output
straight into a file path — CLAUDE.md #4.7 is explicit that a
model/tokens mismatch fails silently, not loudly.

| What | Where it actually lives |
|---|---|
| Real `SttAdapter`/`TtsAdapter` implementations (sherpa-onnx-backed) | Not in `translation-mt`. Per `docs/CLAUDE.md` §9/§12, STT is Shivanshu's area, TTS is Raj's — check `ai_raj`/other branches |
| STT/TTS model files (table above) | `models/` at repo root (per `docs/CLAUDE.md` §3 layout) — present on `ai_abhi`, not on `translation-mt` |
| The real app UI/transport scaffold | `ai_abhi` (Wi-Fi Direct, wire protocol, screens) — never merged with this MT work |

The standalone `translation/android_smoketest/` harness only proves the MT
piece in isolation, by design — it doesn't need any of the above. Full
STT→MT→TTS testing means merging `translation-mt` with whichever branch
has the real app + STT/TTS adapters, and wiring `Orchestrator`'s
constructor to real (not fake) `SttAdapter`/`TtsAdapter` instances.

## 6. Quick start (MT-only, once you have §3 and §4)

```bash
cd translation/android_smoketest
gradle wrapper                       # only if gradlew doesn't exist yet
./gradlew :app:installDebug :app:installDebugAndroidTest
./push_models.sh /path/to/your/export/dir
./gradlew :app:connectedDebugAndroidTest
```
Report: `translation/android_smoketest/app/build/reports/androidTests/connected/index.html`.

If it fails, see `translation/android_smoketest/README.md`'s "If it
fails" section (ABI mismatch, native lib packaging, or genuinely wrong
output are the three things to tell apart).

## After you're done

Update `translation/translation_state.md` with what you found — pass or
fail, and if fail, what exactly broke. That file is the living record;
this one is a setup guide and shouldn't need to change often.
