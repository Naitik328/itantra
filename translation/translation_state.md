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
- `quantize_and_verify.py` — int8 dynamic quantization (`MatMul` **and**
  `Gather` — see "Package size" below for why `Gather` matters a lot here)
  + `verify_tokenizer_ids()`, a **hard gate** that fails the export if
  in-graph tokenization ever drifts from the real HF tokenizer's ids.
- `colab_export.ipynb` — a runnable notebook for anyone without local
  Python/HF setup (not needed once you have a venv + token, see below).

**Ran for real on 2026-09-03**, both directions, with an authenticated HF
token (gated checkpoints, license accepted):

- `verify_tokenizer_ids()` — exact id match against the live HF tokenizer,
  both directions, clean.
- Real translations through the actual quantized graphs:
  - en→hi: *"Where is the nearest hospital?"* → *"निकटतम अस्पताल कहाँ है"*
  - hi→en: round-tripped back essentially verbatim
- Real measured int8 sizes, **current** (after the `Gather`-quantization
  fix below; superseded the first pass's MatMul-only numbers of
  119.5+313.1 / 299.9+136.6 MB). Replaces the spec's "~100–150 MB,
  estimated" placeholder — not fed back into
  `docs/ITANTRA_INTEGRATION_SPEC.md` itself per `docs/CLAUDE.md`'s
  "reference; do not edit without team sign-off" rule; noted here for
  whoever does that update:
  - en-indic: encoder 71.8 MB + decoder 133.0 MB = **204.8 MB**
  - indic-en: encoder 119.8 MB + decoder 88.9 MB = **208.7 MB**
  - **both directions on disk (the real "package size" number): ~436 MB**
    (encoder/decoder int8 + vocab_ids.json + tgt_vocab.json per direction)

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

### Package size — investigated 2026-09-03, ~50% cut found, more possible

The user asked whether the whole package could fit a ~300 MB budget.
Measured the real int8 files by initializer size (not guessed) and found
one dominant cause everywhere: the vocabulary embedding table
(`embed_tokens.weight` / the tied `lm_head.weight`, shape
`[vocab_size, 512]`) is consumed via a `Gather` node (embedding lookup),
which `quantize_dynamic(op_types_to_quantize=["MatMul"])` — the policy
this file used until now — silently never touches, since `Gather` isn't a
`MatMul`. That one fp32 tensor was ~251 MB for the largest cases
(en-indic decoder's tied embedding, indic-en encoder's), dwarfing
everything else in the graph (every other weight was already ~1 MB, int8).

**Fix applied:** added `"Gather"` to `op_types_to_quantize` in
`quantize_and_verify.py`. Re-quantized and re-verified both directions —
translations came out byte-identical to the MatMul-only versions (en→hi:
same two sentences, same output), so this cost nothing measured in
quality while cutting size roughly in half:

| | before (MatMul-only) | after (+ Gather) |
|---|---:|---:|
| en-indic (enc+dec) | 432.6 MB | 204.8 MB |
| indic-en (enc+dec) | 436.5 MB | 208.7 MB |
| **both directions, on disk** | **~869 MB** | **~436 MB** |

Spec §8.3 only commits STT to "MatMul only"; MT's own row says "not yet
validated on this stack" — this *is* that validation, not a deviation
from a rule that was never actually specified for MT.

**Still ~136 MB over a 300 MB target.** Real remaining levers, roughly
ordered by effort:

1. **Vocabulary pruning** (biggest remaining win, not yet done). The
   embedding table is sized for IndicTrans2's full ~30-language family;
   this app only ever needs en/hi/te/bn. `dict.SRC.json`/`dict.TGT.json`
   contain tens of thousands of pieces for scripts/languages this project
   never ships. Slicing the embedding+lm_head rows down to only the ids
   reachable for our 4 languages (Devanagari/Telugu/Bengali/Latin script
   ranges + specials + tags), and remapping `dict.SRC.json`/
   `dict.TGT.json` + the Gather remap table to match, could plausibly cut
   the largest remaining tables by 60–80% — but this is real model
   surgery (slice PyTorch tensors before export, rebuild both dict files,
   re-verify `verify_tokenizer_ids()` and translation quality from
   scratch) and hasn't been attempted. This is the next thing to try if
   300 MB is a hard requirement.
2. **Even more aggressive quantization** (int4, or static/calibrated
   quantization instead of dynamic) — smaller effort than #1 but higher
   quality risk, and dynamic int8 is what the spec's own STT precedent
   uses; would need its own accuracy validation.
3. **Scope tradeoff** — e.g. ship only one direction as the "default"
   package and download the other on demand (mirrors the D1 packaging
   options in spec §2.3, applied to MT). Doesn't reduce total download
   for a user who ends up needing both directions, only defers it.
4. **Accept ~436 MB for MT** and revisit the *overall* app budget instead
   — spec's own §2.2 already put Hindi+English STT/TTS alone at ~477 MB
   before MT, so a whole-app 300 MB target was already off the table
   before this investigation; worth clarifying with the team whether the
   300 MB budget is meant for MT alone or the whole first-launch package
   (this wasn't specified when asked, and changes which lever matters).

Not implemented yet — presented as findings + options, since #1 is
substantial surgery and #3/#4 are product/scope calls, not calls to make
unilaterally.

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
2. **Export `te`/`bn`** the same way — only `en↔hi` and `hi→en` have full
   test-file verification so far. A quick spot-check of en→te and en→bn
   raw model output did run and looked structurally right (Devanagari-
   pivoted, as expected pre-transliteration), but neither has a curated
   test file or a full `verify_tokenizer_ids()` pass yet.
3. **Decide on the package-size options above** (vocab pruning vs. scope
   tradeoff vs. accepting ~436 MB) — needed before this is "done" for
   packaging purposes, not just technically working.
4. **Wire into `languages.json`** (spec §4.3) and the eventual
   `Orchestrator`/`ModelLifecycle` — `ModelLifecycle` is blocked on D3
   (docs/CLAUDE.md §2).
5. **Punctuation restoration** before MT — STT emits none, IndicTrans2
   expects it. Blocked on D5; naive full-stop is the documented fallback
   (spec §5.5).
6. **On-device validation.** Everything above ran on a desktop CPU with
   full RAM. Real phone RTF/RAM is still unmeasured — that's D3's whole
   point (spec §3.2).
7. **Feed the real MT sizes back to the team** for the D1 packaging
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
- **2026-09-03** — Package-size investigation: found `Gather`-consumed
  embedding weights (the dominant tensor in every MT file) were being
  silently skipped by MatMul-only quantization. Fixed
  (`quantize_and_verify.py`), cutting both-directions size from ~869 MB
  to ~436 MB with zero measured quality change. Still ~136 MB over a
  300 MB target; vocabulary pruning is the next lever, not yet attempted
  — see "Package size" section above.
