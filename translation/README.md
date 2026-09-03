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
  `tools/indictrans_common.py` and the future `OnnxMtAdapter.kt` decode loop
  must recompute the full prefix each step, by design.
- **Not compiled or unit-tested in this environment** — no Kotlin toolchain
  was available when this was written. Every non-ASCII character was
  verified codepoint-by-codepoint against the Python source with a Python
  script (stdlib only) rather than trusted by eye, but that is not a
  substitute for `kotlinc` + real test strings. Before wiring this into
  `OnnxMtAdapter.kt`, compile it and run it against real STT output for each
  language, including a round-trip preprocess → postprocess identity check.

## Where this belongs once the Android module exists

docs/CLAUDE.md's repo layout (§3) doesn't list a preprocessing package
explicitly, but by function this sits next to the future MT adapter:
`android/app/src/main/kotlin/com/itantra/adapters/mt/` (package
`com.itantra.mt`, unchanged). Move the files, don't rewrite them.
