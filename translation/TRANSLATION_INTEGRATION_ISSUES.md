# Translation (MT) Integration — Issues Log

Tracks the problems hit while integrating the teammate's IndicTrans2 MT drop
(`~/Documents/translation/`) into the iTantra app, in the order they surfaced.
Scope so far: Phase 1 — prove the MT stage can run on-device alongside
sherpa-onnx. See `tools/patch-sherpa-ort.sh` and `tools/push-mt-models.sh` for
the fixes referenced below.

---

## 1. Two ONNX Runtime builds export the same C API under different versioned symbols

**Severity:** Blocking — the app cannot build a working combination of
sherpa-onnx + MT without this fix.

`sherpa-onnx-1.13.6.aar` bundles its own `libonnxruntime.so`, built by k2-fsa
from `onnxruntime-libs`. The MT stage needs the official
`onnxruntime-android` artifact for its Java API. Both files are named
`libonnxruntime.so`, and Android's dynamic linker can only keep one under
that name — a plain Gradle `pickFirst` picks which one survives, not whether
both work.

Inspecting the two `.so` files directly showed the real problem: ONNX
Runtime exports its C entry point as a **versioned symbol**, and the two
builds disagree:

| Library | Exports | Consumer needs |
|---|---|---|
| sherpa's `libonnxruntime.so` | `OrtGetApiBase@@VERS_1.27.1` | `libsherpa-onnx-{jni,c-api,cxx-api}.so` need `@VERS_1.27.1` |
| `onnxruntime-android:1.27.0`'s `libonnxruntime.so` | `OrtGetApiBase@@VERS_1.27.0` | `libonnxruntime4j_jni.so` needs `@VERS_1.27.0` |

Whichever file `pickFirst` discards fails at `dlopen` with *"cannot locate
symbol OrtGetApiBase version VERS_1.27.x"* — deterministic, not
intermittent. Microsoft never published an Android `1.27.1` build, so
version-matching wasn't an option either; sherpa builds its own ORT.

**Fix:** `tools/patch-sherpa-ort.sh` uses `patchelf` to rename sherpa's
bundled runtime to `libonnxrtsherp.so` and repoints the `DT_NEEDED` entries
in its three consumer libraries (`libsherpa-onnx-jni.so`,
`libsherpa-onnx-c-api.so`, `libsherpa-onnx-cxx-api.so`), across all four
ABIs the AAR ships. This is done on sherpa's side, not ORT's, because sherpa
only reaches its runtime through `DT_NEEDED` (the linker follows the rename
automatically), whereas ORT's own Java loader calls
`System.loadLibrary("onnxruntime")` by that literal name and would break if
renamed.

Verified statically before any device was involved, by dumping the link
graph of the patched AAR with `llvm-objdump`/`llvm-nm` and confirming every
`NEEDED` entry resolves to a matching exported symbol version. Confirmed for
real afterward: `System.loadLibrary("sherpa-onnx-jni")` and
`OrtEnvironment.getEnvironment()` both succeed in the same process on a real
device (`MtCoexistenceTest#a_bothRuntimesLoadInOneProcess`).

**Cost of the fix:** ~40 MB of duplicated ONNX Runtime in the APK, and the
patch script must be re-run (and its output re-committed) on every future
sherpa-onnx upgrade.

**Ruled out:**
- *Hunting for a sherpa build whose bundled ORT exactly matches a published
  `onnxruntime-android` release* — sherpa isn't published to Maven, its
  release notes don't state the ORT version, and each candidate would mean a
  blind ~50 MB AAR download to check. Low odds for the effort.
- *Building sherpa-onnx from source against `onnxruntime-android:1.27.0`* —
  most correct long-term, but a full NDK build to stand up and maintain;
  deferred unless the patch approach causes real problems later.

---

## 2. `onnxruntime-extensions` turned out to have no version dependency at all

Not a problem in the end, but worth recording since it was flagged as a risk
in the original integration plan: whether `onnxruntime-extensions-android`
(the library providing the in-graph SentencePiece tokenizer custom op) would
also need version-matching against whichever ORT build wins.

Inspecting its two native libraries (`libortextensions.so`,
`libonnxruntime_extensions4j_jni.so`) showed neither declares
`libonnxruntime.so` in `NEEDED`, and neither references any `Ort*` symbol.
The library is `dlopen`'d as a custom-op library at ONNX session-creation
time (`OrtxPackage.getLibraryPath()` + `registerCustomOpLibrary()`), not
linked against a specific runtime build. It works against either ORT version
unmodified.

Confirmed on-device: `registerCustomOpLibrary()` and
`OrtxPackage.getLibraryPath()` both execute without error inside
`MtCoexistenceTest`. This was the largest unverified assumption in the
teammate's original Kotlin port (their own README calls the import path
"not verified against the real AAR").

**Caveat carried forward:** this only proves the extensions library *loads
and registers*. Whether the `SentencepieceTokenizer` op actually *resolves*
when a real graph is parsed is still unconfirmed — that requires a session
to actually load, which is blocked by issue #4 below.

---

## 3. adb-pushed model directories were unreadable by the app (silent, looked like "models missing")

**Severity:** Cost one full push-and-test cycle before being caught.

`adb push` created `/sdcard/Android/data/com.sih.itantra/files/mt/<direction>/`
owned by `shell` with directory mode `770`. The app's own uid could not
traverse into `770` directories it doesn't own, so `File(...).exists()`
checks inside the test (`requireModels()`) reported the models as simply
absent — no permission-denied error, just a false "not pushed yet."

Files inside the directories were already `-rwxrwxrwx` (adb pushes files
world-writable by default); it was specifically the *directory* mode that
blocked traversal.

**Fix:** `chmod 777` on the `mt/` directory and each `<direction>/`
subdirectory after every push. Folded into `tools/push-mt-models.sh` so this
doesn't have to be remembered by hand on every future push.

---

## 4. All four shipped `.onnx` model files are corrupted — currently blocking

**Severity:** Blocking. Translation cannot be tested end-to-end until this
is resolved by whoever produced the export.

All four MT model files (`en-indic/{encoder,decoder}.int8.onnx`,
`indic-en/{encoder,decoder}.int8.onnx`) fail to load on-device:

```
ai.onnxruntime.OrtException: Error code - ORT_INVALID_PROTOBUF -
message: Load model from .../encoder.int8.onnx failed:Protobuf parsing failed.
```

Diagnosis, in order:

1. **Checksum check.** All four `.onnx` files fail their own
   `SHA256SUMS.txt`; the two much-smaller `.json` files in the same export
   (`vocab_ids.json`, `tgt_vocab.json`) pass. Initially read as a stale
   manifest (sums written before a final in-place step) rather than
   corruption, since file sizes matched the teammate's documented figures
   exactly (en-indic encoder 71.8 MB / decoder 133.0 MB; indic-en encoder
   119.8 MB / decoder 88.9 MB).
2. **Header check.** All four files open with a valid ONNX protobuf header
   (`ir_version 10`, producer `onnx.quantize`) — ruling out "wrong file
   entirely."
3. **Structural scan.** A standalone protobuf field-by-field walk of each
   file parses cleanly through ~99.9% of the file, then hits an invalid
   wire type a few dozen bytes before EOF in every one of the four files —
   consistent with a truncated/corrupted tail, which is exactly what
   `ORT_INVALID_PROTOBUF` on a genuinely truncated download looks like
   (flagged as a known failure mode in the teammate's own integration spec,
   §7.2: *"a truncated download loads successfully and then emits garbage"*
   — except here it doesn't even load).
4. **Ruled out the "just a bad tail" theory.** Truncated a copy of the two
   en-indic files to exactly the byte offset where the scan first found
   garbage, re-pushed, re-ran the load. **Still failed** with the same
   `Protobuf parsing failed` error — meaning the corruption isn't confined
   to the tail; the graph body itself is damaged, not just trimmable
   trailing garbage.

**Conclusion:** the four `.onnx` files as currently stored in
`~/Documents/translation/itantra-mt-export-shipped/` are genuinely corrupt,
most likely from the transfer that produced that local copy (the small JSON
files in the same drop transferred intact, which points at the transfer
rather than the export step itself).

**Next step (not yet done):** re-obtain the four `.onnx` files from
whoever ran the export, with `shasum -a 256 -c SHA256SUMS.txt` verified on
their end before re-sending. `tools/push-mt-models.sh` (without
`--skip-checksum`) will verify on this end too before pushing, so a repeat
of this can't slip through unnoticed a second time.

**Workaround used to make progress anyway:** `push-mt-models.sh` gained a
`--skip-checksum` flag so the coexistence test (issue #1) could still be
verified on real hardware without waiting on new model files — that test
doesn't touch the actual model weights.

---

## 5. Device storage is tight, model placement is not optional

Not a bug, but a constraint that hardened into a real decision during this
phase. The test device (Galaxy S23) had only **2.9 GB free** before any of
this work, dropping to **2.3 GB** after installing just the debug APK
(598 MB, driven by ~508 MB of already-bundled STT/TTS assets under
`assets/`).

Adding the 416 MB MT bundle into `assets/` — mirroring how STT/TTS models
ship — was ruled out for this reason alone: `ModelInstaller` copies every
asset into `filesDir` on first launch, so it would cost the install twice
(APK size *and* a second on-disk copy), pushing installed size toward
~1.85 GB against ~2-3 GB of real-world free space on target hardware. MT
models are pushed directly into the app's external files directory instead
(`tools/push-mt-models.sh`), matching the teammate's own recommended
Option B from the integration spec (first-run download into `filesDir`,
never bundled in the APK).

---

## Summary table

| # | Issue | Status |
|---|---|---|
| 1 | ORT symbol-version collision between sherpa's bundled runtime and `onnxruntime-android` | **Fixed** — `tools/patch-sherpa-ort.sh`, verified on-device |
| 2 | Whether `onnxruntime-extensions` needs runtime version-matching | **Not an issue** — fully decoupled, verified on-device |
| 3 | adb-pushed model dirs unreadable by app (mode 770) | **Fixed** — `chmod` step folded into `tools/push-mt-models.sh` |
| 4 | All four shipped `.onnx` model files corrupted | **Blocking** — waiting on a clean re-export/re-transfer |
| 5 | Device storage too tight to bundle MT models in `assets/` | **Resolved by design** — models pushed to `filesDir`, not bundled |
