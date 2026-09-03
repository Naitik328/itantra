# IndicProcessor (Kotlin port)

Preprocessing/postprocessing for the MT stage — docs/CLAUDE.md #4 task 12,
docs/ITANTRA_INTEGRATION_SPEC.md #7.4. This is what turns raw STT output
into what IndicTrans2 actually expects as input, and turns the model's
Devanagari-pivoted output back into the target script's text.

Ported from IndicTransToolkit's `processor.pyx` (VarunGumma) and
indic_nlp_library (Anoop Kunchukuttan) — both MIT licensed. Scoped to this
project's four languages (en, hi, te, bn) rather than the ~22 the upstream
libraries cover.

## Files

| File | Ports |
|---|---|
| `IndicScripts.kt` | Unicode block ranges (indic_nlp_library's `langinfo.py`) |
| `UnicodeIndicTransliterator.kt` | offset-based script transliteration |
| `IndicNormalizer.kt` | per-script Unicode normalization (hi/bn/te, default flags only) |
| `IndicTokenizer.kt` | trivial tokenize/detokenize for Indic scripts |
| `EnglishTextNormalizer.kt` | Moses punctuation normalizer (full) + a reduced tokenizer/detokenizer (partial — see file doc) |
| `DigitNormalizer.kt` | native-script digit → ASCII |
| `Placeholders.kt` | do-not-translate placeholder wrap/restore (URLs, emails, numbers, @/#-tags) |
| `IndicProcessor.kt` | ties the above into `preprocess`/`postprocess`, matching `processor.pyx`'s `_preprocess`/`_postprocess` |
| `FloresTags.kt` | ISO ↔ FLORES-200 tag mapping (spec #4.1), shared by `IndicProcessor` and `OnnxMtAdapter` |
| `MtAdapter.kt` | the adapter interface (spec #7.1) |
| `MtTokenizer.kt` | **interface only — no implementation. See "Blocking gap" below.** |
| `OnnxMtAdapter.kt` | the MT adapter: session lifecycle (spec #6.2/#6.3/#6.4), pivot-only routing, KV-cache-free greedy decode loop |

## Blocking gap: no SentencePiece tokenizer

`OnnxMtAdapter` cannot actually run yet. It depends on `MtTokenizer`, which
has no implementation — Android has no first-party SentencePiece binding,
and IndicTrans2's checkpoints need exact tokenization to match training, the
same way STT silently degrades if `normalize_type` is wrong (docs/CLAUDE.md
#8). See `MtTokenizer.kt`'s doc comment for the two realistic options (a JNI
binding to Google's sentencepiece, or exporting tokenization into the ONNX
graph itself via onnxruntime-extensions). This is unassigned, same as MT
generally (spec §12) — flagging rather than guessing at an implementation.

Everything else in `OnnxMtAdapter.kt` — session loading per direction,
idle-timeout eviction, tensor construction, the greedy decode loop — is
real and independently reviewable against `tools/indictrans_common.py`'s
`greedy_decode_onnx` (they must be kept in lockstep). None of it has been
compiled; see below.

## Known gaps, on purpose

- **English tokenizer/detokenizer is not a full MosesTokenizer port.** The
  real one carries a several-hundred-entry per-language non-breaking-prefix
  abbreviation list. This app's input is short chat messages, not documents,
  so a reduced version was used instead — see the doc comment in
  `EnglishTextNormalizer.kt`. Revisit only if English MT quality measurably
  suffers from mis-tokenized abbreviations/decimals (spec's own "measure,
  don't guess" principle).
- **No KV cache in the ONNX decoder these files feed** (see
  `tools/export_indictrans2_onnx.py`) — `greedy_decode_onnx` in
  `tools/indictrans_common.py` and `OnnxMtAdapter.kt`'s decode loop
  recompute the full prefix each step, by design.
- **Bundle layout extends spec #8.4.** The spec shows one encoder/decoder
  pair under `mt/`; this project needs two (en-indic, indic-en — see spec
  #4.1), so `OnnxMtAdapter` expects `mt/en-indic/` and `mt/indic-en/`
  subdirectories, each with its own `encoder.int8.onnx` / `decoder.int8.onnx`
  / `SHA256SUMS.txt`. Flagging the deviation per docs/CLAUDE.md's own
  instruction to flag spec discrepancies rather than silently diverge.
- **Not compiled or unit-tested in this environment** — no Kotlin toolchain
  was available when this was written. Every non-ASCII character was
  verified codepoint-by-codepoint against the Python source with a Python
  script (stdlib only) rather than trusted by eye, but that is not a
  substitute for `kotlinc` + real test strings. Before this can run: pick a
  tokenizer approach (above), add the `onnxruntime-android` Gradle
  dependency, compile, and run it against real STT output for each
  language, including a round-trip preprocess → postprocess identity check.

## Where this belongs once the Android module exists

docs/CLAUDE.md's repo layout (§3) doesn't list a preprocessing package
explicitly, but by function this sits next to the future MT adapter:
`android/app/src/main/kotlin/com/itantra/adapters/mt/` (package
`com.itantra.mt`, unchanged). Move the files, don't rewrite them.
