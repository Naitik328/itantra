# Translation (MT) — state

Living document. Update this after every improvement/change to the
translation module — don't let it drift from what's actually in
`tools/` and `translation/kotlin/`. Branch: `translation-mt`.

---

## Status at a glance

| Layer | State |
|---|---|
| Export tooling (Python) | Built, **run for real against real weights**, verified |
| Preprocessing (`IndicProcessor.kt` + support files) | Ported faithfully, **not compiled** |
| Adapter (`OnnxMtAdapter.kt`) | Matches the verified design, **not compiled** |
| Android wiring (Gradle deps, `languages.json`, orchestrator) | Not started |
| On-device validation | Not started |

---

## What's achieved

### Build-time tooling (`tools/`) — runs with real HF access, on a developer machine, never on the phone

- `export_indictrans2_onnx.py` — exports IndicTrans2-Distilled (en-indic +
  indic-en checkpoints) to ONNX: `encoder.onnx`, `decoder.onnx`. No KV
  cache in the decoder — deliberate, chat messages are short (see the
  file's module doc for the tradeoff).
- `tokenizer_graph.py` — bakes SentencePiece tokenization *into* the
  encoder graph via onnxruntime-extensions' `SentencepieceTokenizer`
  custom op, plus a `Gather`-based vocabulary remap table built from the
  real `dict.SRC.json` at export time. This is not the naive design —
  see "Corrected design" below.
- `quantize_and_verify.py` — int8 dynamic quantization (MatMul only, spec
  §8.3) + `verify_tokenizer_ids()`, a **hard gate** that fails the export
  if in-graph tokenization ever drifts from the real HF tokenizer's ids.
- `colab_export.ipynb` — a runnable notebook for anyone without local
  Python/HF setup (not needed once you have a venv + token, see below).

**Ran for real on 2026-09-03**, both directions, with an authenticated HF
token (gated checkpoints, license accepted):

- `verify_tokenizer_ids()` — exact id match against the live HF tokenizer,
  both directions, clean.
- Real translations through the actual quantized graphs:
  - en→hi: *"Where is the nearest hospital?"* → *"निकटतम अस्पताल कहाँ है"*
  - hi→en: round-tripped back essentially verbatim
- Real measured int8 sizes (replaces the spec's "~100–150 MB, estimated"
  placeholder — not fed back into `docs/ITANTRA_INTEGRATION_SPEC.md`
  itself per `docs/CLAUDE.md`'s "reference; do not edit without team
  sign-off" rule; noted here for whoever does that update):
  - en-indic: encoder 119.5 MB + decoder 313.1 MB
  - indic-en: encoder 299.9 MB + decoder 136.6 MB

### Corrected design — what the naive approach got wrong

The first attempt at in-graph tokenization assumed IndicTrans2's tokenizer
was a plain SentencePiece wrapper. Reading the real
`tokenization_indictrans.py` (behind a gated HF download, only reachable
once authenticated) and reproducing `model.generate()` by hand showed
three load-bearing mistakes, now fixed:

1. **Vocabulary is not the raw SentencePiece vocabulary.**
   `dict.SRC.json`/`dict.TGT.json` are separate fairseq-style dictionaries
   — measured: 87/88 sampled real pieces had mismatched ids between the
   SPM model's own numbering and the dictionary's, no constant offset. Fix:
   a full `Gather` remap table built from the real dict file at export
   time, not a formula.
2. **FLORES tags are literal ids, never tokenized as text.** The encoder
   graph takes `raw_text` + `src_tag_id` + `tgt_tag_id` + `eos_id_const`
   as four separate inputs and assembles
   `[srcTagId, tgtTagId, <remapped SPM pieces>, eosId]` via `Concat` —
   feeding `"tag tag text"` through SentencePiece as one string would have
   sub-tokenized the tag strings themselves.
3. **Decoder seed is `decoder_start_token_id` alone**, not
   `[bos_id, tgt_tag_id]`. `decoder_start_token_id` (config.json, = 2) is
   *not* `bos_token_id` (= 0) for these checkpoints. No separate
   target-tag token goes to the decoder — the target language is already
   encoded in the source sequence.

Also: detokenizing needs no SentencePiece decoder at all —
`IndicTransTokenizer.convert_tokens_to_string` is a plain string join
(`"".join(pieces).replace("▁", " ").strip()`). `tgt_vocab.json`
(id-indexed piece-string array) replaced the earlier `detokenizer.onnx`
custom-op design — one fewer thing that can silently disagree with the
real tokenizer.

### Preprocessing (`translation/kotlin/com/itantra/mt/`)

Faithful port of `IndicTransToolkit`'s `processor.pyx` and the relevant
parts of `indic_nlp_library` (both MIT licensed, sources fetched and
cross-checked codepoint-by-codepoint, not reconstructed from memory).
Files: `IndicScripts.kt`, `UnicodeIndicTransliterator.kt`,
`IndicNormalizer.kt`, `IndicTokenizer.kt`, `EnglishTextNormalizer.kt`,
`DigitNormalizer.kt`, `Placeholders.kt`, `FloresTags.kt`,
`IndicProcessor.kt`. Details and known scope reductions (a deliberately
non-full Moses English tokenizer) in `translation/README.md`.

### Adapter (`OnnxMtAdapter.kt`)

Matches the verified Python design exactly: 4-input encoder call,
`decoder_start_id`-only decoder seed, `tgt_vocab.json`-backed detokenize.
Session lifecycle per direction (en-indic / indic-en) with idle-timeout
eviction (spec §6.2), `numThreads` from config not hardcoded (§6.3),
CPU-only (§6.4). Pivot-only: rejects indic↔indic calls directly, since
only en-indic/indic-en checkpoints exist — the orchestrator must chain
src→en→tgt.

---

## What's left

1. **Compile it.** No Kotlin toolchain has touched any of this yet. Needs
   the `onnxruntime-android` + `onnxruntime-extensions-android` Gradle
   dependencies added and a real build — the biggest untested surface
   right now (in particular, whether `OrtxPackage.getLibraryPath()` is
   the real AAR API).
2. **Export `te`/`bn`** the same way — only `en↔hi` and `hi→en` have been
   test-verified so far. Should work identically (same checkpoints, same
   `dict.SRC.json` already contains all four language tags — confirmed),
   but hasn't been run.
3. **Wire into `languages.json`** (spec §4.3) and the eventual
   `Orchestrator`/`ModelLifecycle` — `ModelLifecycle` is blocked on D3
   (docs/CLAUDE.md §2).
4. **Punctuation restoration** before MT — STT emits none, IndicTrans2
   expects it. Blocked on D5; naive full-stop is the documented fallback
   (spec §5.5).
5. **On-device validation.** Everything above ran on a desktop CPU with
   full RAM. Real phone RTF/RAM is still unmeasured — that's D3's whole
   point (spec §3.2).
6. **Feed the real MT sizes back to the team** for the D1 packaging
   decision (spec §2.1/§2.3) — this doc has the numbers; the spec file
   itself needs team sign-off to edit.

---

## Offline architecture (confirmed, not a TODO)

Everything requiring internet (HF auth, downloading `model.SRC`/
`dict.SRC.json`, running the export) happens **once, at build time, on a
developer's machine** — never on the phone. The output is a
self-contained set of files (`encoder.int8.onnx`, `decoder.int8.onnx`,
`vocab_ids.json`, `tgt_vocab.json` per direction) bundled into the app's
downloadable language package (`filesDir/mt/<direction>/`, extending spec
§8.4). At runtime, `OnnxMtAdapter` only reads those local files through
ONNX Runtime — no network calls, no live tokenizer service. The
`onnxruntime-extensions` custom-op library is a bundled `.so` in the
Android AAR, not fetched at runtime. Matches `docs/CLAUDE.md` §1's "no
internet, no cellular, no API calls" requirement — the only online
dependency is the one-time app/language-pack download, same as
Hindi/English STT/TTS already work.

---

## Update log

- **2026-09-03** — Export tooling built and run for real (both
  directions verified against live weights); tokenizer design corrected
  after reading real gated source; `IndicProcessor.kt` ported;
  `OnnxMtAdapter.kt` written and updated to match the verified design.
  Nothing compiled yet.
