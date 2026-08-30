# codeclab — compression measurement harness

**Native only. Nothing here is compiled into the ESP32 firmware.**
Deliberately a plain Makefile rather than a PlatformIO environment, so there is
no path by which training code reaches the microcontroller.

```bash
cd tools && make
./build/codeclab analyze   # corpus statistics, entropy, theoretical ceiling
./build/codeclab train     # emit generated/huffman_tables.h, report flash cost
./build/codeclab bench     # raw vs Unishox2 vs per-language Huffman
./build/codeclab test      # round-trip correctness
```

## What this exists to answer

*Should we build a custom per-language codec, or just use Unishox2?*
The harness was built to measure that, not to justify a decision already made.
**On the evidence available, Unishox2 wins.** See the verdict in `CLAUDE.md`.

## Layout

```
src/utf8.{h,cpp}           codepoint iteration, Indic grapheme clustering
src/huffman_codec.{h,cpp}  RUNTIME codec - firmware-ready, no dependencies
src/huffman_train.{h,cpp}  TRAINING - tools only, allocates freely
src/main.cpp               the four subcommands
vendor/unishox2/           upstream Unishox2 (Apache 2.0), unmodified
generated/                 emitted flash tables (regenerate, do not hand-edit)
```

`huffman_codec.{h,cpp}` plus a generated table header is everything the firmware
would need. It has no Arduino dependency, no allocation, and no exceptions, so
shipping it is a file copy into `lib/protocol/` — nothing to port.

## Why Huffman, and why it might be the wrong tool

**Huffman** assigns each symbol a whole number of bits. That is its strength
(fast, tiny, table-driven, decodes without arithmetic) and its ceiling: a symbol
with probability 0.3 wants 1.74 bits and gets 2. Across a short message that
rounding waste adds up — typically 2–5% above entropy.

**Arithmetic / range coding** removes the whole-bit constraint and reaches
entropy almost exactly. It costs multiply-divide per symbol and carries patent
and complexity baggage. For a 5% gain on a 100-byte message it is not worth it
here.

**Dictionary coding (LZ-family)** exploits *repeated substrings* rather than
symbol frequencies. On 100–240 byte messages there is almost nothing to repeat
— LZ needs kilobytes before its dictionary pays for itself. This is why
general-purpose gzip does badly on short strings and why Unishox2 exists.

**What Unishox2 actually does**, and why it is hard to beat: entropy coding
*plus* a static dictionary of common patterns *plus* delta coding of adjacent
codepoints. That last part is the one that matters here — Indian scripts pack
their entire alphabet into a 128-codepoint block, so consecutive characters have
small numeric differences, and delta coding turns ~21-bit codepoints into small
residuals. A plain per-language Huffman table captures the frequency structure
but throws that adjacency structure away.

## The measurement trap this harness avoids

Training a table on a sentence and then measuring compression *on that same
sentence* measures memorisation, not compression. With one sentence per language
the table essentially encodes the answer.

So `bench` reports two numbers:

- **`train=test`** — trained on the exact text being compressed. An
  **upper bound that cannot be achieved.** Reported only to show the gap.
- **`held-out`** — trained on the first half of the sentence, measured on the
  second half. The encoder has genuinely not seen this text. With ~30 codepoints
  of training data this is a harsh **lower bound**.

The true figure sits between them and cannot be pinned down until a real corpus
exists. Anything quoted from the `train=test` column in a slide is a fabrication.

## Feeding it a real corpus

`train::train()` takes a frequency map, and every subcommand builds that map by
concatenating whatever text it is given. Pointing this at 200 sentences per
language means replacing the `LANGS` table in `src/main.cpp` with a file reader
— no change to the trainer, codec, or table format.

**Expect the ratios to drop.** More sentences means more distinct characters and
flatter frequencies, which raises entropy. The `held-out` column will rise, the
`train=test` column will fall, and they will converge on the truth.
