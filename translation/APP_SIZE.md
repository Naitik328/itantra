# App size — full breakdown

Where every megabyte of iTantra goes, measured 2026-09-05 against the
`kotlin_app_wifidirect` branch with STT, TTS and translation all present.

All figures are **decimal MB** (10⁶ bytes) unless marked MiB, because that is
what the measurement commands at the bottom emit. `ls -lh` reports MiB, which
is why the release APK reads as "545M" there and 571 MB here — the same file.

---

## The short answer

| | |
|---|---|
| Release APK | **571 MB** |
| Debug APK | 588 MB |
| **Installed on the device** | **≈ 1.03 GB** |
| Realistic free space needed to install | **1.5–2 GB** |

Installed used to be ≈1.54 GB. The speech models were staged out of `assets/`
onto the filesystem at first launch, so all 533 MB of them existed **twice** on
every device. They are now read directly from `assets/` and that duplicate is
gone — see [What is staged, and why so little](#what-is-staged-and-why-so-little).

---

## What is in the APK

Release build, `arm64-v8a` only (`abiFilters` in `app/build.gradle.kts`).

| Component | Size | Share |
|---|---|---|
| STT models (Hindi + English) | 368.3 MB | 64.5% |
| TTS voices (Hindi + English) | 144.2 MB | 25.3% |
| sherpa-onnx + its bundled ONNX Runtime | 31.5 MB | 5.5% |
| Official ONNX Runtime — translation | 18.5 MB | 3.2% |
| ONNX Runtime extensions — MT tokenizer | 6.2 MB | 1.1% |
| Compiled code (dex, post-R8) | 1.2 MB | 0.2% |
| Resources | 1.1 MB | 0.2% |
| **Total** | **571 MB** | |

Speech models are **89.8%** of the package. Everything the team writes —
all the Kotlin, the UI, the Wi-Fi Direct stack, the whole translation port —
is 1.2 MB of it.

### Native libraries, individually

| Library | Size | Belongs to |
|---|---|---|
| `libonnxrtsherp.so` | 21.7 MB | sherpa's own ONNX Runtime (renamed — see below) |
| `libonnxruntime.so` | 17.6 MB | official ORT, for translation |
| `libsherpa-onnx-jni.so` | 4.8 MB | STT/TTS |
| `libsherpa-onnx-c-api.so` | 4.5 MB | STT/TTS |
| `libonnxruntime_extensions4j_jni.so` | 3.8 MB | in-graph SentencePiece tokenizer |
| `libortextensions.so` | 2.4 MB | in-graph SentencePiece tokenizer |
| `libonnxruntime4j_jni.so` | 0.9 MB | official ORT bindings |
| `libsherpa-onnx-cxx-api.so` | 0.5 MB | STT/TTS |

**Two ONNX Runtimes ship in one APK — 39.3 MB where one would be 21.7 MB.**
This is deliberate and currently unavoidable: sherpa-onnx bundles a runtime
k2-fsa built themselves, exporting `OrtGetApiBase@@VERS_1.27.1`, while the
translation stage needs the official `onnxruntime-android` artifact and its
Java API. Microsoft never published an Android 1.27.1, so the versions cannot
be matched. `tools/patch-sherpa-ort.sh` renames sherpa's copy so both can
load in one process. Full write-up in
[TRANSLATION_INTEGRATION_ISSUES.md](TRANSLATION_INTEGRATION_ISSUES.md) #1.

The duplication costs **17.6 MB**, not the ~40 MB the original estimate
assumed — because the `arm64-v8a` filter drops the other three ABIs the AAR
ships.

---

## Per-file inventory

### Speech models — `assets/`

| File | On disk | In APK |
|---|---|---|
| `stt/hi/indicconformer_hi.int8.onnx` | 185.6 MB | 185.6 MB |
| `stt/en/stt_en_fastconformer_ctc_large.int8.onnx` | 182.7 MB | 182.7 MB |
| `tts/en/en_IN-female-medium.onnx` | 63.2 MB | 63.2 MB |
| `tts/hi/hi_IN-finetune-medium.onnx` | 63.1 MB | 63.1 MB |
| `tts/hi/espeak-ng-data/` | 18.9 MB | ~8.9 MB |
| `tts/en/espeak-ng-data/` | 18.9 MB | ~8.9 MB |
| tokens/lexicon files | ~1 MB | ~1 MB |

`.onnx` is listed under `noCompress` in `app/build.gradle.kts`, so those files
are stored uncompressed — ONNX Runtime memory-maps them, and a compressed
asset would have to be inflated into heap on every load instead. espeak data
is not in `noCompress`, so it roughly halves inside the APK and expands again
on staging.

### Translation models — pushed, not bundled

Not in the APK at all. `tools/push-mt-models.sh` puts these in the app's
external files directory.

| File | Size |
|---|---|
| `en-indic/decoder.int8.onnx` | 139.5 MB |
| `indic-en/encoder.int8.onnx` | 125.6 MB |
| `indic-en/decoder.int8.onnx` | 93.2 MB |
| `en-indic/encoder.int8.onnx` | 75.3 MB |
| `en-indic/tgt_vocab.json` | 2.3 MB |
| `indic-en/tgt_vocab.json` | 0.4 MB |
| `*/vocab_ids.json` | < 1 KB each |
| **Total** | **436 MB** |

Encoder and decoder trade places in size between directions because
`dict.SRC` and `dict.TGT` swap which one carries the many-Indic-language
vocabulary.

---

## What is staged, and why so little

| | Size |
|---|---|
| APK as stored in `/data/app` | 571 MB (debug: 588 MB) |
| `espeak-ng-data/`, staged once | 18.8 MB |
| MT bundle in external files | 437 MB |
| **Total** | **≈ 1.03 GB** (debug: 1.04 GB) |

Measured on the device rather than computed: `adb shell run-as com.sih.itantra
du -sh files` reports **18 MB**, all of it espeak — it was 533 MB.

**Before:** `ModelInstaller` copied the whole of `assets/` onto the real
filesystem at first launch — all four model directories, 533 MB — so every
speech model existed twice on every device. That was over a third of the
installed footprint, and it is now gone.

**Why it was ever done:** loading a model by absolute path lets ONNX Runtime
memory-map it rather than inflate it onto the heap, which is a real advantage
worth having. But sherpa-onnx's `OfflineRecognizer` and `OfflineTts` both
accept an `AssetManager`, and `.onnx` is listed under `noCompress` in the
Gradle config, so the assets are stored uncompressed and Android can hand
sherpa a pointer into the mapped APK rather than a copy. The engines now do
that, and nothing is staged for them.

**What still has to be staged:** `espeak-ng-data/` alone. espeak-ng opens its
data files with `fopen`, so it needs a genuine directory tree — an
`AssetManager` will not do. It is staged **once**, not once per voice: the two
copies shipped in `assets/` are byte-for-byte identical (`diff -rq` confirms),
so both voices point at the same staged directory.

**The open question, honestly:** mapped pages are evictable under memory
pressure and anonymous ones are not, so if ONNX Runtime copies the buffer
sherpa hands it, this trades ~514 MB of disk for resident RAM. That is not a
cosmetic distinction on a low-end phone. `ModelLoadMemoryTest` measures it;
**it has not yet been run on hardware** — the change compiles and the app
builds, but no device was attached when it was made. Run it before trusting
these figures:

```bash
adb shell am instrument -w -e class 'com.sih.itantra.ai.ModelLoadMemoryTest' \
  com.sih.itantra.test/androidx.test.runner.AndroidJUnitRunner
adb logcat -d | grep -o "event=MODELMEM.*"
```

Budget **1.5–2 GB of free space**, not 1.03 GB, because:

- `install -r` stages the new APK before removing the old one, so peak usage
  during an upgrade is roughly double the APK.
- ART generates AOT compilation artifacts alongside the install.

This is why MT work kept exhausting the test device.
`./gradlew :app:installDebug -PmtOnly` exists for that: it builds without
`assets/` entirely — **71 MiB instead of 588 MB**, and no staging — since the
translation models are read from external storage and need none of the speech
assets. Speech does not work in such a build; it is for MT work only.

---

## What translation added

**≈ 460 MB**, split very unevenly:

| | Size | Where |
|---|---|---|
| Official ONNX Runtime + JNI | 18.5 MB | in the APK |
| ORT extensions (tokenizer op) | 6.2 MB | in the APK |
| Kotlin MT port (12 files) | < 0.1 MB | in the APK |
| IndicTrans2 models | 436 MB | pushed to the device |

Keeping the models out of the APK is what makes this affordable. Bundled like
the speech models, they would have cost ~870 MB installed instead of 436 MB,
for exactly the duplication reason above — on a device that had 2.9 GB free
before any of this started.

### Runtime memory, which is a separate budget

| State | Native heap |
|---|---|
| Translating | 334 MB |
| Idle (translator unloaded) | 12 MB |

Was ~1 GB until ONNX Runtime's CPU arena allocator was disabled; see
[TRANSLATION_INTEGRATION_ISSUES.md](TRANSLATION_INTEGRATION_ISSUES.md) #6 for
the measurements behind that choice. `ModelResidency` keeps one model resident
at a time, so these do not stack with STT or TTS.

---

## Where the size could go, ranked

Nothing here is done. Listed with real numbers so the trade can be judged
rather than guessed at.

### ~~1. Stop staging the models~~ — done, 514 MB saved, no RAM cost

Models are read from `assets/` via sherpa's `AssetManager` constructors; only
`espeak-ng-data` is staged, once, shared between both voices. Installed
footprint went from ≈1.54 GB to ≈1.03 GB, and the staged-vs-asset comparison
above shows identical memory (226 MB either way), so nothing was traded for
it.

### 2. Prune espeak-ng-data — ~17 MB installed, ~9 MB in the APK

Now the only staged thing, at 18.9 MB, and almost none of it is used.
The app needs `en_dict` (0.17 MB) and `hi_dict` (0.09 MB). It currently also
carries:

| Unused dictionary | Size |
|---|---|
| `ru_dict` | 8.53 MB |
| `cmn_dict` | 1.57 MB |
| `lb_dict` | 0.69 MB |
| `yue_dict` | 0.56 MB |
| `ar_dict` | 0.48 MB |
| …plus 106 more (113 dictionaries ship; 2 are used) | |

**17 MB of dictionaries, of which 0.26 MB is used.** The sharing half of this
is now done (one staged copy, not two); pruning the unused dictionaries out of
`assets/` is still open and would take the staged directory from 18.9 MB to
roughly 2 MB, and remove ~9 MB from the APK by dropping the second copy that
is still shipped but never read. A file-deletion change, not an engineering
problem.

### 3. Ship one language pair for the demo — ~180 MB

STT is 368 MB for two languages. Dropping either halves it.

### 4. Accept the duplicate ONNX Runtime — 17.6 MB

Only removable by building sherpa-onnx from source against
`onnxruntime-android`, which means standing up and maintaining a full NDK
build. Poor return for the effort at this size.

---

## Reproducing these numbers

```bash
# APK sizes
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./gradlew :app:assembleRelease
ls -l app/build/outputs/apk/release/app-release-unsigned.apk

# What is inside, by category
python3 - <<'PY'
import zipfile, collections
z = zipfile.ZipFile("app/build/outputs/apk/release/app-release-unsigned.apk")
b = collections.Counter()
for i in z.infolist():
    n = i.filename
    k = ("assets: STT" if n.startswith("assets/stt") else
         "assets: TTS" if n.startswith("assets/tts") else
         "native"      if n.startswith("lib/") else
         "dex"         if n.endswith(".dex") else "other")
    b[k] += i.compress_size
for k, v in b.most_common():
    print(f"{v/1e6:8.1f} MB  {k}")
PY

# Staged + pushed footprint, on a connected device
adb shell du -sh /data/data/com.sih.itantra/files
adb shell du -sh /sdcard/Android/data/com.sih.itantra/files/mt

# Runtime memory (needs the models pushed)
adb shell am instrument -w \
  -e class 'com.sih.itantra.ai.MtMemoryProfileTest' \
  com.sih.itantra.test/androidx.test.runner.AndroidJUnitRunner
adb logcat -d | grep -o "event=MTPROFILE.*"
```
