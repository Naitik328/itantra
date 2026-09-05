# Translation (MT) — state

Living document. Update this after every improvement/change to the
translation module — don't let it drift from what's actually in
`tools/` and `translation/kotlin/`. Branch: `translation-mt`.

---

## Status at a glance

| Layer | State |
|---|---|
| Export tooling (Python) | Built, **run for real against real weights**, verified |
| Preprocessing (`IndicProcessor.kt` + support files) | Ported faithfully, **compiled + runtime smoke-tested** |
| Adapter (`OnnxMtAdapter.kt`) | Matches the verified design, **compiled + smoke-tested (pure-logic paths only — see below)** |
| `languages.json` + `ConfigLoader`/`Orchestrator` | **Built, compiled, smoke-tested** — pivot routing verified against real config |
| Android Gradle module (real app, real dependencies) | Not started |
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

### Package size — investigated and decided, 2026-09-03: ~436 MB, accepted

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

**Still ~136 MB over a 300 MB target.**

#### CORRECTION (still 2026-09-03): the vocabulary-pruning plan above was wrong

The original plan (item 1 below, kept struck through for the record) assumed
`dict.SRC.json`/`dict.TGT.json`'s ~122k-entry multilingual vocabulary was
split by native script, so a Telugu-only build could drop the
Hindi/Bengali/other-language pieces. **Measured directly and found this
false.** Classified all 122,706 pieces in the shared multi-Indic vocab by
Unicode script:

| | count | % of vocab |
|---|---:|---:|
| neutral (digits, Latin, punctuation, specials) | 46,985 | 38.3% |
| **Devanagari (Hindi) script** | **75,518** | **61.5%** |
| Telugu script | 64 | 0.1% |
| Bengali script | 139 | 0.1% |

Reason: every Indic language is transliterated to Devanagari *before*
tokenization (`UnicodeIndicTransliterator`, already built into
`IndicProcessor.kt`) — so the SentencePiece model was trained almost
entirely on Devanagari text regardless of which Indic language it serves.
**A Telugu-only or Bengali-only vocabulary prune would keep ~99.8% of the
current vocabulary anyway** — there is no meaningful per-target-language
slice to cut, because Telugu/Bengali route through the same shared
Devanagari representation Hindi uses natively. This also means: **a
"per-language-mapping split package" doesn't help either** — every
language needs essentially the same vocabulary, so splitting wouldn't
shrink anything, and if the *shared transformer body* got duplicated
per split package (see below) it would make total storage worse.

#### The real, measured floor

The 268.4 MB "body" (transformer attention/FFN layers) is **not
per-language** — it's one shared computation graph for every language the
checkpoint knows, unsplittable by language pair without literally
re-training separate smaller models:

| | embedding (vocabulary) | body (shared, not per-language) |
|---|---:|---:|
| en-indic encoder | 16.5 MB | 57.5 MB |
| en-indic decoder | 62.8 MB | 76.3 MB |
| indic-en encoder | 62.8 MB | 58.3 MB |
| indic-en decoder | 16.5 MB | 76.3 MB |
| **total** | **158.6 MB** | **268.4 MB** |

Given the vocabulary finding above, most of that 158.6 MB is also not
really per-language-prunable (it's shared Devanagari infrastructure any
Indic language needs). **~436 MB is close to the practical floor** for
en+hi+te+bn without sacrificing quality or dropping a language.

Levers considered, roughly ordered by effort:

1. ~~Vocabulary pruning by target language~~ — **invalidated by the
   measurement above.** Not viable as originally conceived.
2. ~~Restrict each install to one Indic language~~ — **also considered
   and rejected.** Asked directly whether limiting a single device to
   only its own language (e.g. a Telugu phone never touching Hindi)
   would shrink its package. It would not: the model's vocab/weights
   aren't organized per-language (see above), so a Telugu-only install
   still needs essentially the full ~436 MB.
3. Frequency-based pruning (real corpora, uncertain savings, real
   quality risk), more aggressive quantization (int4/static), and
   dropping a language from scope entirely were also on the table —
   deferred, not needed given the decision below.

#### DECISION (2026-09-03): accept ~436 MB, both directions, full en/hi/te/bn support

Discussed the remaining options (frequency pruning, int4, per-language
restriction, dropping a language) and the team decided **not** to pursue
further size cuts right now — the practical floor is ~436 MB for the
model itself, and closing the gap to 300 MB would mean either real
quality risk (untested corpus-based pruning, int4) or a product scope cut
(dropping a language), neither of which is worth it just to hit a number
that was never confirmed to apply to MT specifically rather than the
whole app (spec §2.2 already put Hindi+English STT/TTS alone at ~477 MB
before MT even enters the picture). **~436 MB is the accepted MT package
size going forward.** Revisit only if real on-device constraints (D3,
still unmeasured) force the question, or if a genuinely low-risk lever
turns up later.

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

### Compiled and smoke-tested (2026-09-03)

Set up a real, standalone Kotlin compile (no Android module exists on
this branch): downloaded `kotlinc` 2.0.21 directly, plus the real
dependency jars — `com.microsoft.onnxruntime:onnxruntime` (desktop jar,
same `ai.onnxruntime.*` API as `onnxruntime-android`), the real
`onnxruntime-extensions-android` AAR's `classes.jar` (confirmed
`OrtxPackage.getLibraryPath()` exists with the exact signature used, via
`javap` — not assumed), and real `org.json`. **All files, including
`OnnxMtAdapter.kt`, compile cleanly: zero errors, zero warnings, even at
Android's typical `-jvm-target 1.8`.**

Went further than just compiling: `translation/kotlin_verify/SmokeTest.kt`
+ `verify.sh` exercise the pure-logic paths at runtime (preprocessing,
transliteration, placeholder wrap/restore, `vocab_ids.json`/
`tgt_vocab.json` parsing, detokenize) — all pass. Notably, Kotlin's
Telugu→Devanagari transliteration produced `"आसुपत्रि"` (hospital), the
*exact same word* the real Python model produced when translating into
Telugu earlier — independent cross-validation that the Kotlin port
matches the verified Python pipeline's actual behavior, not just that it
type-checks.

**Real remaining gap, not glossed over:** `OnnxMtAdapter`'s actual
encoder/decoder ONNX session calls are NOT exercised by this — that needs
`onnxruntime-extensions`' native library, which only ships for Android
ABIs (bionic libc), and this environment is desktop Linux (glibc). Full
inference through the adapter still needs a real Android
device/emulator. `translation/kotlin_verify/verify.sh` is reproducible
from a clean cache and worth re-running after any future change to these
files.

### `languages.json` + `ConfigLoader` + `Orchestrator` (2026-09-03)

Wired MT into config and a real orchestrator, per spec §5.1-§5.4 and
CLAUDE.md's repo layout:

- `translation/config/languages.json` — the spec §4.3 schema, copied
  verbatim, plus one addition: an `"mt"` block (`modelDir`, `maxNewTokens`,
  `idleTimeoutMillis`, and a `directions` map documenting which languages
  each checkpoint direction covers — descriptive, not load-bearing;
  `OnnxMtAdapter` validates against the real `vocab_ids.json` at runtime
  regardless). Final home: `android/app/src/main/assets/config/languages.json`.
- `com/itantra/config/LanguageConfig.kt` + `ConfigLoader.kt` — data classes
  and `org.json`-based parsing for the schema above. Handles `stt: null`
  (Bengali) without crashing, per CLAUDE.md #5 Phase 1 task 1's explicit
  requirement.
- `com/itantra/adapters/SttAdapter.kt` + `TtsAdapter.kt` — spec §7.1's
  interfaces, copied verbatim. Interface only; real sherpa-onnx-backed
  implementations are Shivanshu's/Raj's areas (spec §12) and don't exist
  on this branch. Defined so `Orchestrator` compiles against the real
  contract instead of a guess.
- `com/itantra/orchestrator/Punctuation.kt` — spec §5.5's "minimum viable
  approach" (append a full stop per STT output) — this specific piece is
  unblocked; the model-based alternative stays D5-blocked and unbuilt.
- `com/itantra/orchestrator/Orchestrator.kt` — sequences the send path
  (STT → punctuate → `MtAdapter.translate(text, senderLang, "en")`) and
  receive path (same-language shortcut → reuse original, avoiding the
  lossy round trip; else `MtAdapter.translate(pivotText, "en", receiverLang)`),
  per spec §5.3/§5.4 exactly. Constructs `OnnxMtAdapter` directly from
  `LanguagesConfig.mt` + `filesDir` — this is the actual "wire MT into
  config and the orchestrator" work. STT/TTS adapters are constructor-
  injected (interfaces only exist here, see above); a caller with real
  ones wires them in. Fails loudly (not silently) if asked to send from a
  language with no STT adapter — e.g. Bengali, spec #3.4.

**Compiled and smoke-tested against real `languages.json`, not a
fixture.** `translation/kotlin_verify/OrchestratorSmokeTest.kt` (added
alongside `SmokeTest.kt`, both run by `verify.sh`) parses the actual
shipped config file and drives `Orchestrator` with fake STT/TTS/MT
adapters, asserting on exactly what gets called: punctuation is added
before translation, the same-language receive path makes zero MT calls,
a cross-language receive correctly pivots through English, TTS returns
null (not a crash) for an unwired language, and sending from Bengali
throws instead of silently doing something wrong. All 15 checks pass.

Same real gap as `OnnxMtAdapter` itself: the `MtAdapter` used in these
tests is a fake (`RecordingMtAdapter`) — the actual ONNX inference path
still needs a real Android device. What's verified here is the
*orchestration logic* (routing, config parsing, fail-loud behavior), which
is real and independent of that gap.

### Telugu and Bengali verified the same way as Hindi (2026-09-03)

Found and fixed a gap while extending verification to `te`/`bn`: both
`greedy_decode_onnx` (Python reference) and `greedy_decode_onnx_embedded`
were feeding raw native-script Telugu/Bengali text straight to the
tokenizer, with no Devanagari-pivot transliteration first. The earlier
`hi↔en` tests never caught this because Hindi *is* Devanagari natively —
there was nothing to transliterate. For Telugu/Bengali this matters:
the vocab is 61.5% Devanagari / ~0.1% Telugu / ~0.1% Bengali (measured
earlier, see "Package size"), so skipping the transliteration step would
have fed the model text its vocabulary can barely represent — not what
`IndicProcessor.kt`'s real pipeline does, and not a meaningful test.

Fixed: added `transliterate()` to `indictrans_common.py` (same
offset-based algorithm as `UnicodeIndicTransliterator.kt`, ported from
indic_nlp_library) and applied it — source text into Devanagari before
tokenization, output text out of Devanagari after detokenization —
in both `greedy_decode_onnx` and `greedy_decode_onnx_embedded`, plus
`verify_tokenizer_ids()`'s input. This makes the Python verify tooling
actually mirror the real pipeline for te/bn, not just for hi.

Added `tools/test_sentences/en-te.tsv`, `te-en.tsv`, `en-bn.tsv` (no
`bn-en.tsv` — Bengali has no STT, spec §3.4, so that direction is never
called in production) and ran `verify_tokenizer_ids()` + real translation
for all three. All pass, with real native-script output matching hand-
written references closely:

| Pair | Result |
|---|---|
| en→te | "Where is the nearest hospital?" → `సమీప ఆసుపత్రి ఎక్కడ ఉంది` (exact match to reference) |
| en→bn | "Where is the nearest hospital?" → `নিকটতম হাসপাতাল কোথায়` (exact match to reference) |
| te→en | `సమీప ఆసుపత్రి ఎక్కడ ఉంది?` → "Where is the nearest hospital ?" (exact round trip) |

**Every language pair this app actually uses (spec's exact 5 directed
pairs: hi→en, te→en, en→hi, en→te, en→bn) now has a curated test file and
a clean `verify_tokenizer_ids()` pass against real weights.**

---

## What's left

1. **Get a real translate() call running on-device.** Partial progress —
   see `translation/TRANSLATION_INTEGRATION_ISSUES.md` for the full log
   from a collaborator's real integration attempt. **Confirmed on real
   hardware:** the sherpa-onnx/ORT native-library coexistence problem
   (issue #1, fixed) and `onnxruntime-extensions` loading + registering
   independent of ORT version (issue #2). **Still blocked:** the actual
   model files that reached the collaborator's machine were corrupted in
   transit (issue #4) — re-verified the source files on this end and they
   pass checksum + load correctly, so this is a transfer problem, not a
   re-export; needs a clean re-transfer, verified with
   `sha256sum -c SHA256SUMS.txt` on both ends before trusting a copy.
   `push_models.sh` now verifies before pushing and fixes the mode-770
   directory permission issue (issue #3) automatically.
2. ~~Export `te`/`bn` the same way~~ — **done, see below.** All five
   language pairs the app actually uses (hi↔en, te↔en, en→bn) now have
   curated test files and a clean `verify_tokenizer_ids()` pass.
3. **Merge into the real app module.** This branch (`translation-mt`)
   deliberately never had an `app/` — the real UI/transport scaffold
   (Wi-Fi Direct, wire protocol, screens) lives on `ai_abhi`/`ai_raj` and
   was never merged with this MT work. `translation/android_smoketest/`
   is a deliberately separate, minimal harness for exactly this reason —
   it answers "does the MT module run on Android" without waiting on a
   branch merge. The real integration (this code moving into
   `android/app/src/main/kotlin/com/itantra/...` per each file's own "where
   this belongs" note, `ModelStore` download wiring, D1) is still ahead.
4. **`ModelLifecycle`** (spec §6.2 full tiered residency across STT/MT/TTS,
   not just `OnnxMtAdapter`'s own `evictIdle()`) — blocked on D3
   (docs/CLAUDE.md §2). `Orchestrator.evictIdleModels()` forwards to what
   `OnnxMtAdapter` already does for itself; a real `ModelLifecycle` that
   also manages STT/TTS residency is separate, unbuilt work.
5. **Real `SttAdapter`/`TtsAdapter` implementations** — only interfaces
   exist here (spec §7.1, copied verbatim). The real sherpa-onnx-backed
   ones are Shivanshu's/Raj's areas and live outside this branch; wiring
   them into `Orchestrator`'s constructor is what turns the smoke-tested
   fake-adapter pipeline into a real one.
6. **Real-device performance measurement** (RTF/RAM, spec §3.2, D3) —
   distinct from item 1's correctness check: even once the smoke test
   passes, everything so far has only run on a desktop CPU with full RAM.
   Real phone RTF/RAM is still unmeasured — that's D3's whole point, and
   the actual blocker on §6.2's tiered-residency decision.
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
- **2026-09-03** — Corrected the vocabulary-pruning plan above: measured
  the multi-Indic vocab's actual script distribution and found it's
  61.5% Devanagari / 0.1% Telugu / 0.1% Bengali, because all Indic
  languages get transliterated to Devanagari before tokenization. A
  Telugu-only or Bengali-only prune would keep ~99.8% of the vocab
  anyway — no meaningful per-language slice exists. Also rules out
  per-language-mapping split packages (asked directly): every language
  needs essentially the same vocabulary, so splitting doesn't shrink
  anything, and duplicating the 268.4 MB shared body per split package
  would make total storage worse, not better. ~436 MB now treated as
  close to the practical floor; frequency-based corpus pruning is the
  only remaining lever with real (if uncertain) upside, and needs real
  Hindi/Telugu/Bengali corpora this environment doesn't have.
- **2026-09-03** — Also asked and ruled out: restricting a single
  install to one Indic language (e.g. Telugu-only phones). Doesn't
  shrink anything, same reason as the split-package finding above.
  **Decision: accept ~436 MB as the MT package size** rather than chase
  further cuts that all require either real quality risk or a product
  scope cut. Revisit only if on-device measurement (D3) forces it.
- **2026-09-03** — Set up a real standalone Kotlin compile (downloaded
  `kotlinc` + the real onnxruntime/onnxruntime-extensions-android/org.json
  jars, no Android module exists on this branch yet). All files including
  `OnnxMtAdapter.kt` compile cleanly, zero errors/warnings.
  `kotlin_verify/SmokeTest.kt` + `verify.sh` added and passing — exercises
  preprocessing, transliteration, placeholder handling, and vocab JSON
  parsing at runtime. Cross-validated against the real Python pipeline:
  Kotlin's Telugu→Devanagari transliteration produced the same word
  ("आसुपत्रि") the real model produced translating into Telugu earlier.
  Real gap still open: `OnnxMtAdapter`'s actual ONNX session calls need a
  real Android device (`onnxruntime-extensions`' native lib is
  Android-only, no desktop-Linux build to test against here).
- **2026-09-03** — Wired MT into config and an orchestrator:
  `translation/config/languages.json` (spec §4.3 schema + a new `mt`
  block), `ConfigLoader`/`LanguageConfig.kt`, `SttAdapter`/`TtsAdapter`
  interfaces (spec §7.1, verbatim), `Punctuation.kt` (spec §5.5 minimum
  viable), and `Orchestrator.kt` sequencing the send/receive pivot paths
  (spec §5.3/§5.4) and constructing `OnnxMtAdapter` directly from config.
  Compiled clean; `OrchestratorSmokeTest.kt` parses the real shipped
  `languages.json` and drives the full pivot-routing logic with fake
  adapters — all 15 checks pass, including the same-language shortcut
  making zero MT calls and a Bengali send failing loudly (no STT).
- **2026-09-03** — Extended verification to Telugu and Bengali. Found and
  fixed a real gap: the Python verify tooling wasn't transliterating te/bn
  text to the Devanagari pivot before tokenization (hi↔en tests never
  caught this, since Hindi is already Devanagari). Added `transliterate()`
  to `indictrans_common.py` and wired it into both decode paths. All five
  language pairs the app actually uses now have curated test files and a
  clean `verify_tokenizer_ids()` + real-translation pass — en→te and
  en→bn produced exact matches to hand-written Telugu/Bengali references,
  and te→en round-tripped back to the original English exactly.
- **2026-09-03** — Moved the exported model bundles off `/tmp` to
  `~/itantra-mt-export/` on the machine this was built on (2.1 GB;
  regenerating means re-running the full HF-authenticated export). Built
  `translation/android_smoketest/` — a standalone, minimal Gradle Android
  module (not the real app) with `onnxruntime-android` +
  `onnxruntime-extensions-android` as real dependencies, sourcing directly
  from `translation/kotlin/` (no copy), an instrumented test that
  constructs a real `OnnxMtAdapter` and checks the same sentences already
  verified on desktop, and `push_models.sh` to get the shipped bundle onto
  a device. **Built, not run** — no Android SDK/emulator/device was
  available in this environment. This is the concrete next step toward
  closing the "needs a real Android device" gap that's been open since
  `OnnxMtAdapter.kt` was first compiled.
- **2026-09-03** — Added `translation/simulator_setup.md`, a standalone
  handoff doc for a collaborator setting up on-device MT (± STT/TTS)
  testing on their own machine — what to read first, which files are
  already on this branch vs. need transferring/regenerating, and a
  quick-start command sequence. Extended it with the specific STT/TTS
  model files needed per language (filenames from `languages.json`/spec
  §4.3/§8.4, since none of those files or their real STT/TTS adapters
  exist on this branch) and flagged one thing worth checking, not
  assuming: this machine's `ai_abhi` checkout had
  `hi_IN-finetune-medium.onnx` on disk where `languages.json` expects
  `hi_IN-female-medium.onnx` — possibly just a rename since, but a real
  model/config mismatch fails silently (CLAUDE.md #4.7), so it's called
  out rather than glossed over.
- **2026-09-03** — A collaborator's real integration attempt (merging MT
  into the actual app alongside sherpa-onnx STT/TTS, not just the
  standalone `android_smoketest` harness) surfaced real findings, logged
  in `translation/TRANSLATION_INTEGRATION_ISSUES.md`:
  - **Confirmed on real hardware, resolving two things this doc used to
    flag as unverified:** `OrtxPackage.getLibraryPath()` +
    `registerCustomOpLibrary()` both work, and `onnxruntime-extensions`'
    native libraries are fully decoupled from whichever ONNX Runtime
    build is present (no `libonnxruntime.so` dependency at all).
  - **A real architectural blocker found and fixed:** sherpa-onnx bundles
    its own `libonnxruntime.so` at a different version than
    `onnxruntime-android`, both named identically — Gradle's `pickFirst`
    can't resolve this, only one survives, and native symbol-version
    resolution fails deterministically for whichever one loses. Fixed via
    `patchelf`-renaming sherpa's copy and repointing its consumers'
    `DT_NEEDED` entries. Only relevant once MT and sherpa-onnx share a
    process — added a prominent warning to `simulator_setup.md` so the
    next person doesn't lose time rediscovering this.
  - **A real corruption**, not yet resolved: all four `.onnx` model files
    that reached the collaborator's machine fail their own
    `SHA256SUMS.txt` and fail to parse (`ORT_INVALID_PROTOBUF`). Re-
    verified the source files on this end — checksums pass and they load
    correctly through `onnxruntime` + `onnxruntime_extensions` right now
    — so this is a transfer-corruption problem, not a bad export. Needs a
    clean re-transfer with checksums verified on both ends.
  - Fixed `push_models.sh` to match: verifies `SHA256SUMS.txt` before
    pushing (catches exactly the corruption above before it reaches a
    device) and `chmod 777`s the pushed directories afterward (adb's
    default mode 770 silently blocks the app's own uid from reading what
    it just received — CLAUDE.md's "silent failure" pattern showing up in
    a new place).
