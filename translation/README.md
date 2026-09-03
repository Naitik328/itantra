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

## Tokenization: resolved, in-graph, not in Kotlin — and verified end-to-end

`OnnxMtAdapter` previously depended on an `MtTokenizer` interface with no
implementation (Android has no first-party SentencePiece binding). That
interface and file are gone — tokenization now happens *inside* the ONNX
graph via onnxruntime-extensions' `SentencepieceTokenizer` custom op plus a
vocabulary-remap `Gather` (domain `ai.onnx.contrib`), built by
`tools/tokenizer_graph.py` and merged into `encoder.onnx` by
`tools/export_indictrans2_onnx.py`. The only Android dependency is the
`onnxruntime-extensions-android` AAR, loaded as a custom-op library at
session-creation time (`OrtxPackage.getLibraryPath()` in `OnnxMtAdapter.kt`
— import path and exact API not verified against the real AAR, no Kotlin
toolchain here; confirm on first compile).

**This was run for real, on 2026-09-03, with authenticated HF access and
real weights — not left as a documented guess.** What earlier notes here
called "unverified" turned out to be more than a couple of boolean flags:
`IndicTransTokenizer`'s real vocabulary (`dict.SRC.json`/`dict.TGT.json`,
confirmed by reading the actual `tokenization_indictrans.py`) is a
fairseq-style dictionary completely different from the raw sentencepiece
models' own ids (measured: 87/88 sampled real pieces had no matching id,
no constant offset), and FLORES language tags are inserted as literal ids,
never run through the tokenizer op at all. The design was corrected to
match:

- `encoder.onnx` takes `raw_text` (string) + `src_tag_id` + `tgt_tag_id` +
  `eos_id_const` (all dict.SRC-space ids) and builds
  `[srcTagId, tgtTagId, <remapped SPM pieces>, eosId]` internally via a
  Gather-based remap table built from the real `dict.SRC.json` at export
  time — not a formula, a full lookup table, since the mapping is an
  arbitrary corpus-frequency-sorted permutation.
- The decoder is seeded with **only** `decoder_start_id` (from
  `config.json`, not `bos_token_id` — they differ, 2 vs 0, for these
  checkpoints). No separate target-tag token goes to the decoder; the
  target language is already encoded in the source sequence. Confirmed by
  reproducing `model.generate()`'s output token-for-token with a bare
  `use_cache=False` loop.
- Detokenizing needs no SentencePiece decoder at all —
  `IndicTransTokenizer.convert_tokens_to_string` is a plain string join
  (`"".join(pieces).replace("▁", " ").strip()`). `tgt_vocab.json` (an
  id-indexed piece-string array) replaces the earlier `detokenizer.onnx`
  design; `OnnxMtAdapter.kt`'s `detokenize()` does the same three
  operations directly.

`tools/quantize_and_verify.py`'s `verify_tokenizer_ids()` checks the
in-graph tokenizer's ids against the real HF tokenizer for every test
sentence and **fails the export** (not a warning) on any mismatch. Both
directions passed clean, and both produced correct translations end-to-end
through the actual quantized ONNX graphs (e.g. en→hi: "Where is the
nearest hospital?" → "निकटतम अस्पताल कहाँ है"; hi→en round-tripped back
essentially verbatim). See `tools/tokenizer_graph.py`'s module doc for the
full, itemized ground truth.

Everything else in `OnnxMtAdapter.kt` — session loading per direction,
idle-timeout eviction, tensor construction, the greedy decode loop — is
real and independently reviewable against `tools/quantize_and_verify.py`'s
`greedy_decode_onnx_embedded` (they must be kept in lockstep, and both were
exercised against real weights together). The Kotlin file itself has still
never been compiled; see below.

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
  / `vocab_ids.json` / `tgt_vocab.json` / `SHA256SUMS.txt`. Flagging the
  deviation per docs/CLAUDE.md's own instruction to flag spec discrepancies
  rather than silently diverge. Real measured sizes (int8, this checkpoint
  family): en-indic encoder 119.5 MB / decoder 313.1 MB; indic-en encoder
  299.9 MB / decoder 136.6 MB — encoder/decoder trade places in size
  because dict.SRC/dict.TGT swap which one is the "many Indic languages"
  vocabulary per direction. These replace the spec's own "~100-150 MB,
  estimated" placeholder (§2.1) with real numbers — worth feeding back into
  the D1 packaging decision (spec §2.3).
- **The Kotlin file itself is not compiled or unit-tested** — no Kotlin
  toolchain was available when this was written, even though the Python/
  ONNX side it depends on has been. Every non-ASCII character was verified
  codepoint-by-codepoint against the Python source with a Python script
  (stdlib only) rather than trusted by eye, but that is not a substitute
  for `kotlinc` + real test strings. Before this can run on-device: add the
  `onnxruntime-android` + `onnxruntime-extensions-android` Gradle
  dependencies, compile, and run it against real STT output for each
  language, including a round-trip preprocess → postprocess identity check.

## Where this belongs once the Android module exists

docs/CLAUDE.md's repo layout (§3) doesn't list a preprocessing package
explicitly, but by function this sits next to the future MT adapter:
`android/app/src/main/kotlin/com/itantra/adapters/mt/` (package
`com.itantra.mt`, unchanged). Move the files, don't rewrite them.
