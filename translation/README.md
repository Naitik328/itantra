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
| `OnnxMtAdapter.kt` | the MT adapter: in-graph tokenization, session lifecycle (spec #6.2/#6.3/#6.4), pivot-only routing, KV-cache-free greedy decode loop |

## Tokenization: resolved, in-graph, not in Kotlin

`OnnxMtAdapter` previously depended on an `MtTokenizer` interface with no
implementation (Android has no first-party SentencePiece binding). That
interface and file are gone — tokenization now happens *inside* the ONNX
graph via onnxruntime-extensions' `SentencepieceTokenizer` /
`SentencepieceDecoder` custom ops (domain `ai.onnx.contrib`), built by
`tools/tokenizer_graph.py` and merged into `encoder.onnx` /
`detokenizer.onnx` by `tools/export_indictrans2_onnx.py`. The only Android
dependency is the `onnxruntime-extensions-android` AAR, loaded as a
custom-op library at session-creation time (`OrtxPackage.getLibraryPath()`
in `OnnxMtAdapter.kt` — import path and exact API not verified against the
real AAR, no Kotlin toolchain here; confirm on first compile).

**What's verified vs. not**, in `tools/tokenizer_graph.py`'s module doc:
the ONNX node schema is confirmed against onnxruntime-extensions' own test
suite, and `model.SRC`/`model.TGT` (the raw sentencepiece files these nodes
need) are confirmed to exist in the HF repo's public file listing. What's
**not** verified is IndicTrans2's exact `add_bos`/`add_eos`/fairseq-vocab-shift
settings — the tokenizer source that would answer this sits behind a gated
HF download this environment couldn't reach with an anonymous request.
`tools/quantize_and_verify.py`'s `verify_tokenizer_ids()` checks the
in-graph tokenizer's ids against the real HF tokenizer for every test
sentence and **fails the export** (not a warning) on any mismatch — that
check is what actually resolves the "not verified" state, not this file.

Everything else in `OnnxMtAdapter.kt` — session loading per direction,
idle-timeout eviction, tensor construction, the greedy decode loop — is
real and independently reviewable against `tools/quantize_and_verify.py`'s
`greedy_decode_onnx_embedded` (they must be kept in lockstep). None of it
has been compiled; see below.

## Known gaps, on purpose

- **English tokenizer/detokenizer is not a full MosesTokenizer port.** The
  real one carries a several-hundred-entry per-language non-breaking-prefix
  abbreviation list. This app's input is short chat messages, not documents,
  so a reduced version was used instead — see the doc comment in
  `EnglishTextNormalizer.kt`. Revisit only if English MT quality measurably
  suffers from mis-tokenized abbreviations/decimals (spec's own "measure,
  don't guess" principle).
- **No KV cache in the ONNX decoder these files feed** (see
  `tools/export_indictrans2_onnx.py`) — `greedy_decode_onnx_embedded` in
  `tools/quantize_and_verify.py` and `OnnxMtAdapter.kt`'s decode loop
  recompute the full prefix each step, by design.
- **Bundle layout extends spec #8.4.** The spec shows one encoder/decoder
  pair under `mt/`; this project needs two (en-indic, indic-en — see spec
  #4.1), so `OnnxMtAdapter` expects `mt/en-indic/` and `mt/indic-en/`
  subdirectories, each with its own `encoder.int8.onnx` / `decoder.int8.onnx`
  / `detokenizer.onnx` / `vocab_ids.json` / `SHA256SUMS.txt`. Flagging the
  deviation per docs/CLAUDE.md's own instruction to flag spec discrepancies
  rather than silently diverge.
- **Not compiled or unit-tested in this environment** — no Kotlin toolchain
  was available when this was written. Every non-ASCII character was
  verified codepoint-by-codepoint against the Python source with a Python
  script (stdlib only) rather than trusted by eye, but that is not a
  substitute for `kotlinc` + real test strings. Before this can run: export
  with `tools/export_indictrans2_onnx.py`, get a clean
  `verify_tokenizer_ids()` pass from `tools/quantize_and_verify.py`, add the
  `onnxruntime-android` + `onnxruntime-extensions-android` Gradle
  dependencies, compile, and run it against real STT output for each
  language, including a round-trip preprocess → postprocess identity check.

## Where this belongs once the Android module exists

docs/CLAUDE.md's repo layout (§3) doesn't list a preprocessing package
explicitly, but by function this sits next to the future MT adapter:
`android/app/src/main/kotlin/com/itantra/adapters/mt/` (package
`com.itantra.mt`, unchanged). Move the files, don't rewrite them.
