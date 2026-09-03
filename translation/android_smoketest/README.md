# MT on-device smoke test

A standalone, minimal Android app — **not** the real iTantra app — whose
only job is to answer one question for real: does `OnnxMtAdapter` (in-graph
SentencePiece tokenizer custom op included) actually run on a real Android
device? Everything up to this point (`tools/quantize_and_verify.py`,
`translation/kotlin_verify/`) verified the model, the tokenizer, and the
Kotlin logic on a desktop — this is the first place all three run together
through onnxruntime-extensions' actual Android native library, which only
ships for Android ABIs and can't be exercised on desktop Linux (see
`translation/translation_state.md`'s "Compiled and smoke-tested" section
for why that gap existed).

**Built here, not run** — no Android SDK, emulator, or device was
available in the environment this was written in. Treat the Gradle
versions/config below as a well-reasoned starting point, not a confirmed
build; the first real build may need adjustment.

## What's here

- `app/build.gradle.kts` — compiles directly from `../../kotlin/com/itantra/{mt,config,adapters,orchestrator}`
  (no copy, no drift risk) with `onnxruntime-android` and
  `onnxruntime-extensions-android` as real dependencies.
- `app/src/androidTest/kotlin/.../OnnxMtAdapterInstrumentedTest.kt` — the
  actual test: constructs a real `OnnxMtAdapter`, translates the exact
  sentences already verified against real weights on desktop
  (`translation/translation_state.md`), and asserts on the same expected
  substrings. If this disagrees with the desktop result, the Android
  *build* (native lib ABI, R8 stripping, a JNI mismatch) is the suspect —
  the model and tokenizer design are already independently confirmed
  correct.
- `push_models.sh` — pushes the shipped bundle (`encoder.int8.onnx`,
  `decoder.int8.onnx`, `vocab_ids.json`, `tgt_vocab.json` per direction —
  not the fp32/debug files) from a local export directory onto a
  connected device/emulator.

## What's NOT here

- **The gradle wrapper jar/scripts** (`gradlew`, `gradlew.bat`,
  `gradle/wrapper/gradle-wrapper.jar`) — only `gradle-wrapper.properties`
  is checked in (pins Gradle 8.9, compatible with AGP 8.5.2). Opening this
  folder in Android Studio generates the rest automatically; from a
  terminal with any local Gradle install, run `gradle wrapper` once inside
  this directory first.
- A launcher UI, or anything resembling the real app. This exists solely
  to run the instrumented test in `androidTest`.

## Running it, once you have a device/emulator

```bash
cd translation/android_smoketest
gradle wrapper   # only if gradlew doesn't exist yet -- see above
./gradlew :app:installDebug :app:installDebugAndroidTest
../push_models.sh ~/itantra-mt-export   # or wherever your export lives
./gradlew :app:connectedDebugAndroidTest
```

The last command's report lands at
`app/build/reports/androidTests/connected/index.html`.

## If it fails

- **App crashes on `OrtxPackage.getLibraryPath()` or similar at startup**:
  likely an ABI mismatch — confirm the device/emulator's ABI
  (`adb shell getprop ro.product.cpu.abi`) is one `onnxruntime-extensions-android`
  ships (arm64-v8a, armeabi-v7a, x86, x86_64 — should cover real
  devices and most emulators).
- **`register_custom_ops_library` / native op not found**: check
  `app/build/outputs` for whether the `.so` files actually made it into
  the APK — `packaging { resources.excludes }` in `app/build.gradle.kts`
  exists specifically to avoid a duplicate-`META-INF` merge failure
  between the two onnxruntime artifacts, but if it's swallowing something
  it shouldn't, that's the first thing to check.
- **Translation runs but the assertion fails**: compare the actual output
  against `tools/test_sentences/*.tsv` and the transcript in
  `translation/translation_state.md` — if the *meaning* is right but the
  exact substring differs, the model may just be paraphrasing (normal,
  see the same caveat in `quantize_and_verify.py`'s own output); if the
  output is garbled or wrong-script, that's a real regression worth
  chasing, not a paraphrase.
