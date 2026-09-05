# MT CLI test bench (C++)

An interactive command-line tool for testing translation directly, in any
direction among the four languages this app ships (en/hi/te/bn) — pick the
source and target languages and type text, at the prompt. Pure C++, no
Android/JVM/Python needed at runtime.

**Built and verified for real** on 2026-09-05, against the real exported
models. All of the following produced correct output, matching what was
already verified in Python/Kotlin earlier (`translation/translation_state.md`):

```
[en->hi] Where is the nearest hospital? -> निकटतम अस्पताल कहाँ है
[hi->en] अस्पताल कहाँ है?              -> Where is the hospital ?
[en->te] Where is the nearest hospital? -> సమీప ఆసుపత్రి ఎక్కడ ఉంది
[te->en] సమీప ఆసుపత్రి ఎక్కడ ఉంది?      -> Where is the nearest hospital ?
[en->bn] Where is the nearest hospital? -> নিকটতম হাসপাতাল কোথায়
[hi->te] अस्पताल कहाँ है?              -> ఆసుపత్రి ఎక్కడ ఉంది ?   (two-hop: hi->en->te)
[te->hi] సమీప ఆసుపత్రి ఎక్కడ ఉంది?      -> निकटतम अस्पताल कहाँ है ?  (two-hop: te->en->hi)
```

The last two confirm the two-hop pivot chaining (never direct
indic-to-indic, matching `Orchestrator.kt`) works correctly end-to-end,
independent of both the Python and Kotlin implementations.

## What this exercises, and what it doesn't

Uses the ONNX Runtime **C++ API** directly against the real
`encoder.int8.onnx`/`decoder.int8.onnx` per direction, with the same
onnxruntime-extensions custom-op tokenizer `OnnxMtAdapter.kt` uses. This is
**not the same code path as the Android app** (which goes through
`ai.onnxruntime`'s Java bindings) — but it's the same native ONNX Runtime
libraries, the same graphs, and the same tokenizer, which is what actually
matters for "is the model/tokenizer/pivot design correct." It does not
port `IndicProcessor.kt`'s full preprocessing (Moses punctuation
normalization, placeholder wrapping, English tokenization) — same
deliberate scope as `tools/quantize_and_verify.py`, which this file
mirrors closely (see the top of `translate_cli.cpp`).

## Dependencies (not checked in — all fetched, none built from source)

| What | Why | Where it came from |
|---|---|---|
| ONNX Runtime C++ SDK (headers + `libonnxruntime.so`) | The inference engine itself | `https://github.com/microsoft/onnxruntime/releases/download/v1.29.0/onnxruntime-linux-x64-1.29.0.tgz` |
| `nlohmann/json` (single header) | Parsing `vocab_ids.json`/`tgt_vocab.json` | `https://github.com/nlohmann/json/releases/download/v3.11.3/json.hpp` |
| `libortextensions.so` (standalone, **not** the Python wheel's copy) | The in-graph SentencePiece tokenizer custom op | See below — this one has a real gotcha |

### The `libortextensions.so` gotcha

`pip install onnxruntime-extensions` gives you
`onnxruntime_extensions/_extensions_pydll.cpython-*.so` — **do not use
this for a C++ program.** It looks standalone (`nm -D` shows the right
`RegisterCustomOps` export, `ldd` shows no explicit Python dependency) but
it's actually a CPython extension module with undefined symbols
(`PyInstanceMethod_Type` and friends) that only resolve inside a running
Python interpreter. Loading it via `RegisterCustomOpsLibrary` from a plain
C++ process fails with `undefined symbol: PyInstanceMethod_Type` — found
this out by trying it first, not by reading a warning somewhere.

**What actually works:** the NuGet package ships a genuine standalone
build:
```bash
curl -sL -o ortext.nupkg \
  "https://www.nuget.org/api/v2/package/Microsoft.ML.OnnxRuntime.Extensions/0.13.0"
unzip ortext.nupkg -d ortext_extract
# runtimes/linux-x64/native/libortextensions.so is what you want
```
Confirmed via `ldd`/`nm`: no Python dependency, exports `RegisterCustomOps`
cleanly.

## Build

```bash
cd translation/cli_testbench
# Fetch the ORT C++ SDK (once):
mkdir -p ~/.local/ort-cpp && cd ~/.local/ort-cpp
curl -sL -o ort.tgz "https://github.com/microsoft/onnxruntime/releases/download/v1.29.0/onnxruntime-linux-x64-1.29.0.tgz"
tar xzf ort.tgz
mkdir -p include-extra/nlohmann
curl -sL -o include-extra/nlohmann/json.hpp "https://github.com/nlohmann/json/releases/download/v3.11.3/json.hpp"
curl -sL -o ortext.nupkg "https://www.nuget.org/api/v2/package/Microsoft.ML.OnnxRuntime.Extensions/0.13.0"
mkdir -p libs && unzip -p ortext.nupkg runtimes/linux-x64/native/libortextensions.so > libs/libortextensions.so

# Then, back in translation/cli_testbench:
make
```

(`Makefile` defaults `ORT_ROOT`/`JSON_INCLUDE` to exactly the paths above;
override with `make ORT_ROOT=... JSON_INCLUDE=...` if you put them
elsewhere.)

## Run

```bash
./translate_cli --model-root /path/to/itantra-mt-export-shipped \
                 --extensions-lib ~/.local/ort-cpp/libs/libortextensions.so
```

`--model-root` is the directory containing `en-indic/` and `indic-en/`
(each with `encoder.int8.onnx`, `decoder.int8.onnx`, `vocab_ids.json`,
`tgt_vocab.json`) — see `translation/simulator_setup.md` §4 for how to get
this bundle if you don't already have it.

Then just answer the prompts:
```
source language [en/hi/te/bn]: en
target language [en/hi/te/bn]: hi
text to translate: Where is the nearest hospital?
-> [en->hi] निकटतम अस्पताल कहाँ है

source language [en/hi/te/bn]: quit
```
Type `quit` at any prompt to exit. Bengali as a *source* works here (the
`indic-en` checkpoint technically supports it) even though the real app
never calls it that way — Bengali has no STT (spec §3.4), so `bn` only
ever appears as a target in production. Useful for testing the model in
isolation regardless.

## If it fails

- **`undefined symbol: PyInstanceMethod_Type`** — you used the pip
  wheel's `.so` instead of the NuGet one. See the gotcha above.
- **`Failed to load library ... cannot open shared object file`** — check
  the `--extensions-lib` path is correct and the file actually exists.
- **`No such file or directory` loading a model** — check `--model-root`
  points at a directory with `en-indic/` and `indic-en/` subdirectories,
  each containing all four files.
- **Wrong-looking output, not just a different phrasing** — compare
  against `tools/test_sentences/*.tsv` and
  `translation/translation_state.md`; if the *meaning* is right but the
  wording differs, that's normal model paraphrasing, not a bug (same
  caveat `tools/quantize_and_verify.py` prints).
