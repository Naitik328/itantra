# SIH26173 — LoRa Communication Extender

ISRO problem statement, Smart India Hackathon. This repo is the **ESP32 firmware**
for a LoRa relay that extends an offline phone-to-phone walkie-talkie.

## What the system is

Two Android phones run **offline STT and TTS in 10 Indian languages** and talk to
each other like a walkie-talkie. Direct phone-to-phone over Bluetooth is what the
problem statement requires, and it works without this hardware at all.

This extender is an **addition**, not a replacement: when the phones are too far
apart for Bluetooth, each phone talks over BLE to a nearby ESP32, and the two
ESP32s relay over LoRa across kilometres.

```
Phone A --BLE--> ESP32-A  ==LoRa 865-867MHz==>  ESP32-B --BLE--> Phone B
 (STT)                                                            (TTS)
```

The phones do all language work. This firmware moves **opaque UTF-8 bytes**
reliably. It never parses, validates, or interprets the text.

## Hardware

| Part | Detail |
|---|---|
| MCU board | Waveshare ESP32-S3 Mini (a.k.a. **ESP32-S3-Zero**) |
| Chip | **ESP32-S3FH4R2** — dual core, 512 KB SRAM, **4 MB in-package flash**, **2 MB in-package quad PSRAM** |
| Radio | Ebyte **E22-900M22S** (SX1262, SPI, 22 dBm) |
| Band | India ISM **865–867 MHz** |
| Radio lib | **RadioLib** (`jgromes/RadioLib`) |
| BLE lib | **NimBLE-Arduino** (`h2zero/NimBLE-Arduino`) — *not* Bluedroid, for RAM |

### Board definition — read this before changing `platformio.ini`

PlatformIO has **no Waveshare ESP32-S3 board definition**. We use
**`lolin_s3_mini`** because the WEMOS LOLIN S3 Mini carries the *same*
ESP32-S3FH4R2 part: 4 MB flash + 2 MB quad PSRAM.

**Do not use `esp32-s3-devkitc-1`.** It is the N8 variant: **8 MB flash and no
PSRAM**. Both are wrong for this chip, and the flash size is the dangerous one —
an 8 MB partition table on a 4 MB part overruns the chip.

`platformio.ini` also sets these explicitly rather than inheriting them, because
getting them wrong fails at flash time, not build time:

```ini
board_build.flash_size = 4MB
board_upload.flash_size = 4MB
board_build.arduino.memory_type = qio_qspi   ; quad flash + quad PSRAM (R2)
```

## Measured facts

These came from **real tested sentences and real radio measurements**, not
datasheets or assumptions. Treat them as ground truth.

### UTF-8 sentence lengths (real emergency sentences)

The exact strings live in `test/corpus.h` and are asserted against these numbers
on every test run. They are the same sentences the TTS pipeline was measured
with, and they are the training/benchmark corpus for the compression work.

| Language | Bytes |
|---|---|
| English | 81 |
| Gujarati | 145 |
| Hindi | 162 |
| Bengali | 164 |
| Kannada | 172 |
| **Tamil** | **239** ← worst case, **2.95×** English |

Indic scripts cost ~3 bytes/character in UTF-8. Sizing anything off the English
length will break on Tamil.

### LoRa airtime, 168 B payload, 125 kHz BW, CR 4/5

| SF | Time on air |
|---|---|
| SF7 | 272 ms |
| SF8 | 482 ms |
| SF9 | 861 ms |
| SF10 | 1559 ms |
| SF11 | 3445 ms |
| SF12 | 6234 ms |

`lib/protocol/airtime.cpp` reproduces **all six of these to the millisecond**;
`test/test_airtime` asserts it. If that test ever fails, the model no longer
describes the radio — trust the measurement, not the code.

### Latency budget

| Stage | Budget |
|---|---|
| STT | 800 ms *(placeholder — unmeasured)* |
| TTS worst case (Gujarati) | 2000 ms *(confirmed by teammate)* |
| → leaves for radio, in a ~5 s target | **~2200 ms** |

**SF9–SF10 is the working range.** SF11/SF12 blow the budget on a single
fragment; SF7/SF8 waste range headroom we paid for.

## Hard constraints (these drove the design)

1. **Downstream TTS degrades silently on corrupted-but-valid UTF-8.** It skips
   the bad character and speaks the rest, with no error. **Our CRC is the only
   gatekeeper — nothing downstream catches what we miss.**
2. **TTS cannot stream.** It needs the complete message before synthesis. So
   fragments must be *fully* reassembled before handoff. **Never pass partial
   text upward.**
3. A message spoken **twice** is a real failure, not a cosmetic one. Duplicate
   suppression is a correctness requirement.

---

# Packet format

**7 bytes of overhead per fragment**: a 5-byte header + a 2-byte CRC trailer.

```
 off  size  field
 ---  ----  ----------------------------------------------------------
  0    1    [ver:2][type:2][lang:4]
  1    1    [reserved:4][netId:2][ALERT:1][COMPRESSED:1]
  2    1    msgId   0..255
  3    1    [fragIndex:4][fragCount-1:4]
  4    1    payloadLen  0..248
  5    N    payload (opaque bytes; UTF-8 text)
 5+N   2    CRC-16/CCITT-FALSE over bytes [0 .. 5+N-1], big-endian
```

Max frame 255 B (SX1262 explicit header) → **max payload 248 B**.

## What each bit costs

At **SF10, CR 4/5**, one coding block is 5 payload bytes and emits 5 symbols, so
**1 payload byte = exactly 1 symbol = 8.192 ms**, and:

> **At SF10, every header bit costs ~1.02 ms of airtime, on every fragment.**

| Field | Bits | Cost @SF10 |
|---|---|---|
| version | 2 | 2.0 ms |
| type | 2 | 2.0 ms |
| lang | 4 | 4.1 ms |
| flags (COMPRESSED, ALERT, netId, 4 reserved) | 8 | 8.2 ms |
| msgId | 8 | 8.2 ms |
| fragIndex + fragCount | 8 | 8.2 ms |
| payloadLen | 8 | 8.2 ms |
| CRC-16 | 16 | 16.4 ms |
| **total** | **56** | **~57 ms** |

At SF9 halve it (~0.57 ms/bit); at SF12 quadruple it (~4.1 ms/bit).

## Why each field is the width it is

**`version` — 2 bits.** Lets a node reject a peer running mismatched firmware
instead of silently misparsing it. Four revisions outlasts a hackathon. Costs
2 ms/fragment; a mid-event protocol change without it costs a debugging evening.

**`type` — 2 bits.** DATA / ACK / NACK / BEACON. Exactly four, no slack. If a
fifth type is ever needed, take bits from the `flags` reserved space rather than
widening this field.

**`lang` — 4 bits.** 10 languages today (0–9), 6 spare codes.
*Known ceiling:* India has 22 scheduled languages. If this project ever grows
past 16, this field must widen — that is a breaking change, hence `version`.

**`flags` — 8 bits.** `COMPRESSED` (bit 0) and `ALERT` (bit 1) are the user
bits; **`netId` occupies bits 2–3** (see below). The remaining 4 bits are the
expansion joint: a codec ID (once there is more than one codec), a hop count for
mesh relay, or a retry counter all belong there. Reserved bits must be
transmitted as 0; `serialize()` **rejects** a packet that sets them, because that
is always a caller bug. `Packet::flags` holds only the two user bits — the
network id has its own struct field and is packed on the wire.

**`msgId` — 8 bits.** Groups fragments *and* detects duplicate messages.
256 ids at walkie-talkie pace (one message per 5–10 s) wraps in 20–40 minutes,
while the reassembly buffer times out in 30 s. **The wrap period is ~50× the
buffer lifetime**, so a stale fragment can never be mistaken for a live one.
16 bits would double this cost for nothing.

**`fragIndex` + `fragCount` — 4 bits each, one byte.** `fragCount` is stored as
`count-1`, so the nibble covers **1–16 fragments**.
- *Why carry the count on every fragment?* With out-of-order delivery, the first
  fragment you see may be #3. Without the total you cannot tell when the message
  is complete. The alternative — a 1-bit "last fragment" flag — defers that
  knowledge until the final fragment arrives, which is exactly the fragment most
  likely to be lost. 4 bits is worth it.
- *Why 16 and not 256?* 16 fragments × 15 B minimum still carries the 239 B Tamil
  worst case. A 2-byte index/count pair would cost 8 ms/fragment forever to
  buy headroom we have no use for. The fragmenter **returns
  `TooManyFragments` rather than truncating**, so the limit fails loudly.

**`payloadLen` — 8 bits.** Genuinely redundant when frames come from the radio
(RadioLib reports the received length). Kept anyway, for one reason: it lets
`deserialize()` verify that the caller's `len` agrees with the packet's own
claim. A caller passing a stale length variable is a real bug class, and given
that *nothing downstream catches what we miss*, 8 ms/fragment for
self-consistency is the right trade. It also makes the format self-delimiting,
so it works unchanged over BLE or serial where framing differs.
*This is the field to drop first if the header ever needs to shrink.*

**`CRC-16/CCITT-FALSE` — 16 bits, trailer.**
- *Why CRC-16 over CRC-32?* At our frame sizes (≤2040 bits) **both give Hamming
  distance 4** — every 1-, 2-, and 3-bit error caught with certainty. CRC-32
  would improve only the random-burst residual (2⁻³² vs 2⁻¹⁶) for **+16 ms per
  fragment**. And this CRC sits *on top of* the SX1262's own hardware CRC-16, so
  a corrupt frame must defeat both: ~2⁻³² effective. CRC-32 was not worth it.
- *Why a trailer and not a header field?* Its input is then **one contiguous
  span**, which removes the "zero the field, hash, write it back" dance that
  header-embedded checksums need — and a whole class of bugs with it.
- Big-endian. It is the only multi-byte field in the format, so this is the
  format's only endianness exposure.

## Decode order — deliberate

`deserialize()` checks in this order, and the order matters:

1. length bounds → `TooShort` / `TooLong`
2. **CRC** → `CrcMismatch`
3. `payloadLen` consistency → `LengthMismatch`
4. `version` → `BadVersion`
5. `networkId` → `WrongNetwork`
6. `fragIndex < fragCount` → `BadFragmentation`

**No field is interpreted before the CRC passes.** Until then we do not know
that any byte means what it claims to mean.

## Interference defence — two layers

A venue will have many teams on 865–867 MHz, and this format carries **no
source/destination address**. Two independent layers keep foreign traffic out.

**Layer 1 — LoRa sync word `0x26`** (`lib/protocol/radio_config.h`). The SX1262
rejects a non-matching sync word at the PHY, before the frame reaches our code.
`0x12` (SX126x default) and `0x34` (LoRaWAN) are avoided, and a `static_assert`
enforces it — leaving the default is exactly the failure this prevents.

**Layer 2 — 2-bit `netId` in flags bits 2–3.** Checked after the CRC, so it also
catches a foreign frame that shared our sync word. Four networks; both boxes are
flashed from the same build so they agree by construction. Bump `NETWORK_ID` if
another team turns out to be on `0x26` too.

## Fragmentation

Fragments are cut on **raw byte boundaries, not UTF-8 character boundaries**.
This is safe *because* reassembly only emits a message once every fragment has
arrived — the reconstructed byte stream is identical to the input, so a split
codepoint is never observed. Aligning cuts to codepoints would only matter if we
wanted to render partial text, and we cannot: TTS needs the whole string.

## Practical sizing at SF10

Max **text** bytes per fragment (after the 7 B overhead) for a given per-fragment
airtime budget:

| SF | Tsym | 800 ms | 1200 ms | 1600 ms | 2200 ms |
|---|---|---|---|---|---|
| SF7 | 1.02 ms | 248 | 248 | 248 | 248 |
| SF8 | 2.05 ms | 248 | 248 | 248 | 248 |
| SF9 | 4.10 ms | 149 | 235 | 248 | 248 |
| SF10 | 8.19 ms | 67 | 117 | 167 | **237** |
| SF11 | 16.38 ms | 15 | 38 | 60 | 92 |
| SF12 | 32.77 ms | 0 | 8 | 18 | 38 |

**Note the punchline: at SF10 with the full 2200 ms budget, one fragment carries
237 text bytes — and Tamil is 239.** It misses a single-fragment delivery by two
bytes. Fragmentation is not optional for this project; it is load-bearing for
exactly one language, which is precisely why it must be correct.

Use `maxPayloadForBudget()` to pick the frame size, then `maxFragmentPayload()`
to convert it to a chunk size. Do not hardcode 168.

---

# Layout

```
platformio.ini          two envs: esp32s3 (firmware), native (tests)
src/main.cpp            firmware entry — radio + protocol live, no BLE
src/radio.{h,cpp}       SX1262 driver: ISR receive, non-blocking transmit
src/link.{h,cpp}        protocol glue — the verified send/receive ordering
src/codec_hook.{h,cpp}  compression seam (declines; no codec shipped)
src/ble_service.{h,cpp} NimBLE GATT server + the app<->radio ADAPTER
src/bringup.cpp         standalone radio bring-up sketch (env:bringup)
lib/protocol/           pure C++17, NO Arduino headers (so it tests natively)
  crc16.{h,cpp}         CRC-16/CCITT-FALSE, bitwise
  packet.{h,cpp}        wire format: serialize / deserialize
  fragmenter.{h,cpp}    split a message into fragments
  reassembler.{h,cpp}   out-of-order, duplicate, timeout handling
  airtime.{h,cpp}       time-on-air calculator
  radio_config.h        ALL radio settings + the ESP32<->E22 pin map
  envelope.{h,cpp}      Naitik's APP envelope codec (not our LoRa format)
  rate_control.{h,cpp}  adaptive SF/BW ladder - PURE LOGIC, no Arduino
  packet_log.{h,cpp}    range-test CSV formatter - PURE, no I/O
  tx_queue.{h,cpp}      ALERT-priority send queue - PURE, no radio
docs/packet-format.md   the wire-format reference (radio hop only)
docs/hardware-wiring.md solder table + why each pin
docs/bringup-checklist.md  ordered hardware bring-up, with fault tables
docs/adaptive-rate-design.md  adaptive SF/BW - approved and implemented
test/corpus.h           the REAL tested sentences, verified byte-exact
test/test_packet/       codec, boundaries, corruption, CRC
test/test_fragment/     split/reassemble, out-of-order, dupes, timeouts
test/test_airtime/      verified against the six measured numbers
tools/                  NATIVE-ONLY codec lab - never built into firmware
  src/huffman_codec.*   runtime codec (firmware-ready, just not shipped yet)
  src/huffman_train.*   codebook trainer (tools only)
  vendor/unishox2/      upstream Unishox2, Apache 2.0
  generated/            emitted flash tables
```

`lib/protocol` has **no Arduino dependency by design**. Keep it that way — it is
what makes the whole protocol layer testable on a laptop with no hardware.

## Commands

```bash
pio test -e native      # 152 tests, ~8 s, no hardware needed
pio run -e esp32s3      # build firmware
pio run -e esp32s3 -t upload

pio run -e bringup -t upload   # radio bring-up sketch - flash this FIRST

cd tools && make        # codec lab (separate build, never touches firmware)
./build/codeclab analyze|train|bench|test
```

If `pio` is not on PATH: `export PATH="$HOME/Library/Python/3.11/bin:$PATH"`.

## Memory notes

- `Reassembler` is **~16 KB** (3 slots × 16 fragments × 248 B, plus an assembly
  buffer). Declare it **static or as a member — never on a stack.**
- Current firmware build: **RAM 5.8%** (18.9 KB / 320 KB), **Flash 20.1%**
  (264 KB / 1.31 MB). Plenty of headroom for NimBLE and RadioLib.
- `Packet` embeds its 248 B payload inline: no allocation, no lifetime
  questions, ~256 B per instance. Also not a stack-friendly type in bulk.

---

# State of the work

## Two "type" fields, two layers — do not conflate

- `PacketType` (packet.h): **ours**. DATA/ACK/NACK/BEACON, the radio hop's own
  control plane.
- `appType` (flags bits 4-5): **the app's**. NORMAL/ALERT/ACK from Naitik's
  envelope, carried opaquely so the far-side adapter can rebuild it.

## App envelope vs LoRa packet

`lib/protocol/envelope.{h,cpp}` implements **Naitik's** frame (6 B header +
payload + 2 B CRC, max payload **247**, total 255). It is the phone-facing
contract and the app never sees our format. The adapter in `src/ble_service.cpp`
is the only place both are in scope.

Crossing down: **type, lang, payload**. Regenerated on the far side:
**src, seq, crc** (agreed advisory — we own seq/ACK/dedup on the radio hop).
**`dst` is regenerated as broadcast** — our format has no address field, so
per-peer addressing cannot survive the hop. Fine for two nodes; a real
limitation to raise if the app ever addresses specific phones.

Their CRC and ours are the **same algorithm** (CRC-16/CCITT-FALSE, big-endian
trailer). `test_envelope` pins this with a golden vector computed from an
independent reimplementation of `Crc16.kt`.

## Adaptive rate control

Four rungs, fastest first: **FAST** SF7/250k, **MEDIUM** SF9/125k, **FAR**
SF10/125k (default + anchor), **MAX** SF12/125k (deep rendezvous).

**Direction, since it is easy to invert:** lower SF *and* wider BW both mean
faster and shorter range. There is no `++`/`--` on rungs anywhere — use
`faster()` and `moreRobust()`.

- **Promotion is slow**: 8 consecutive frames above threshold, negotiated
  Propose/Accept over the link that currently works.
- **Demotion is instant**: one CRC failure or one marginal frame.
- **Fallback is unilateral**: after 60 s of silence each node independently
  returns to the anchor. Recovery needs no communication, which is what makes it
  work when communication has failed.
- **SNR feedback rides only on ACKs**, so it exists exactly when packets are
  getting through. Decisions use `min(local, peer)` — a link is only as good as
  its weaker direction and both ends must run the same rung.
- **Rung choice changes fragment count**: a max-size 247 B payload is one
  fragment at MEDIUM, two at FAR (2255 ms vs the 2200 ms budget).

The handshake is `RungNegotiator`, a **pure state machine** in `rate_control.h`
with no I/O. It lives there rather than in `LoraLink` because the two roles are
asymmetric and a shared flag made the proposer switch without waiting for the
peer — a silent split-brain on every change. Untestable logic gets it wrong.

Runtime override, no rebuild, over serial:

```
rate status | auto | off | fast | medium | far | max | silence <ms>
log  csv | on | off | status | mark <text>
```

Pin a rung for A/B range tests. `silence <ms>` (2000..600000, default 60000)
tunes the watchdog empirically; every fallback logs the silence it **actually
observed**, so the threshold becomes a measured decision rather than a guess.

**Promotion is sized from the measured margin**, not one rung at a time: a
confirmed streak jumps straight to the rung the SNR supports, capped at 2 rungs.
The jump uses the *worst* sample in the streak, so a freak reading cannot carry
it. Retreat is unchanged — immediate, one rung.

## Range-test logging

`log csv` emits a header then one row per packet event, 17 constant columns,
RFC 4180 quoted, designed to paste straight into a sheet. `log mark <text>`
annotates the stream while walking — without markers there is no way to
correlate distance to packets afterwards. Marks print even with logging off.

This log is the evidence for the presentation, so its format is tested
(`test/test_log`): column count, quoting of commas in marker text, and empty
rather than zero in columns a spreadsheet would average.

## Done and tested (152/152 passing)

Packet codec, CRC, fragmentation, reassembly, airtime calculator, native test
target. Firmware compiles and links for the ESP32-S3 with RadioLib **7.7.1** and
NimBLE-Arduino **2.5.1** resolved.

**Radio layer built, not yet run on hardware.** `Sx1262Radio` does
interrupt-driven receive (DIO1 ISR sets a flag, `loop()` does the work) and
non-blocking transmit via a state machine, with an airtime guard that refuses
anything over `MAX_TX_AIRTIME_MS`. RSSI/SNR/frequency-error are captured per
frame. `LoraLink` sequences it against the protocol layer. Both `env:esp32s3`
and `env:bringup` build clean; nothing has touched real hardware yet.

**BLE adapter built.** NimBLE GATT server on the four confirmed UUIDs, envelope
codec, thread-safe write queue (BLE task -> FreeRTOS queue -> loop()), STATUS
JSON with live RSSI/SNR. Types 3-15 are dropped defensively.

## ALERT priority

**An ALERT is never silently dropped.** `TxQueue` (2 slots) serves alerts ahead
of queued normals and **preempts at fragment boundaries** — the alert goes out at
the next boundary and the interrupted message resumes. Nothing is aborted.

Why fragment boundaries and not mid-fragment: queueing behind a whole message is
up to **14.5 s at MAX** (seven fragments), but preempting at a boundary is
bounded by the per-fragment airtime budget, so **≤2.2 s at every rung**. A
mid-fragment abort buys ~2.2 s more and costs the entire in-flight message —
there is no ARQ to recover it.

- An alert with no free slot displaces a **not-yet-started** normal, never one
  already on the air (that would strand fragments the peer is holding).
- A genuinely full queue returns `QueueFull`, and the BLE adapter **surfaces
  it**: STATUS gains `send_failed`/`alert_failed` and is pushed immediately.
  A message the radio would not take must never vanish.
- Promotions are withheld while an alert is pending (`setUrgentPending`).
  Retreats and the watchdog stay live — those make the link more reliable.

**Never send an ALERT at a unilaterally more-robust rung.** Both nodes must
agree on the rung, so a receiver on the wrong configuration hears *silence*, not
a weaker link. Full reasoning in `docs/adaptive-rate-design.md`; it is the
option most likely to be re-proposed.

Current builds: `esp32s3` **RAM 20.9%** (68.6 KB) / **Flash 42.4%** (556 KB);
`bringup` RAM 6.3% / Flash 23.6%.

### Unishox2 is NOT in the firmware

Measured, chosen, vendored, round-trip tested — **in `tools/` only**.
`codec_hook.cpp` still returns `false`, so `FLAG_COMPRESSED` is never set and
every message goes out as raw UTF-8. The Tamil single-fragment win depends on
compression and is therefore **not yet realised on air**.

Wiring it is a ~10-line change in `compressMessage`/`decompressMessage` plus
moving `tools/vendor/unishox2/` into `lib/`. Deliberately deferred until after
radio bring-up passes: compression changes the bytes on air, and debugging a
codec tangled with an RF-switch fault costs an evening. One variable at a time.

### Codec integration ordering — verified

Send: `text → compressMessage() → fragment() → serialize()+CRC → airtime guard
→ radio`. Receive: `radio → deserialize() (CRC first) → reassemble → read
FLAG_COMPRESSED → decompressMessage() → text`.

Compression touches **payload bytes only**, on the whole message, before any
frame exists. Never a serialized frame, never a header field, never a single
fragment. `FLAG_COMPRESSED` sits in header byte 1 outside the payload and is
CRC-covered, so a receiver reads it without decompressing. Full contract in
`src/codec_hook.h`.

## Deliberately NOT built yet

- **No retransmission.** ACKs now flow (they carry the SNR feedback byte), but
  nothing retries on a missing ACK. `PacketType::Nack` is still unused.
- **No compression in firmware.** The `FLAG_COMPRESSED` bit exists and
  round-trips through the whole stack, but no codec is wired in and the
  uncompressed path is the working path. A codec slots in at exactly two points:
  compress before `fragment()`, decompress after a `Complete` result.
  **Compress the whole message, not each fragment** — per-fragment compression
  wastes the dictionary and loses cross-fragment redundancy. The evaluation is
  done; see the verdict below.
- **No ARQ / retransmission.** `PacketType::Ack` and `Nack` exist in the format
  and round-trip, but no retry state machine drives them. An ACK is 7 bytes —
  ~247 ms at SF10.

## Known gaps — decide before the demo

1. **No source/destination address — mitigated, not solved.** The format still
   assumes exactly two nodes. Sync word `0x26` plus the 2-bit `netId` (above)
   keep foreign traffic out, which covers the realistic venue risk. What they do
   **not** provide is routing: a third node that shares both values would still
   collide. If the topology ever grows past a pair, a real address field is
   needed — that is a `version` bump.
2. **STT latency is a placeholder.** The 800 ms figure is assumed, not measured.
   If it turns out worse, the radio budget shrinks and SF10 may stop fitting —
   re-run the table above with the real number before committing to an SF.
3. **Duty cycle for India's 865–867 MHz band is not encoded anywhere.**
   `requiredOffTimeMs()` exists to compute off-time, but nothing enforces a
   limit. Confirm the applicable WPC limit before extended field testing.

---

# Compression — measured verdict

Evaluated in `tools/` (see `tools/README.md` for method). Corpus: the six real
sentences, one per language.

## Ship Unishox2. Do not build a custom codec on current evidence.

| Codec | Total (963 B raw) | Ratio | Notes |
|---|---|---|---|
| raw UTF-8 | 963 B | 1.00x | baseline |
| **Unishox2** | **400 B** | **2.41x** | no training, no tables, already proven in Meshtastic |
| Huffman, train=test | 230 B | 4.19x | **overfit — not achievable, never quote this** |
| Huffman, held-out | 294 B | ~1.5–2.3x | trained on ~30 codepoints; harsh lower bound |

Unishox2 beat our per-language Huffman on **every language** on the honest
(held-out) measurement, and it needs no codebooks at all. It wins because it
does something a frequency table cannot: **delta-codes adjacent codepoints**.
Indian scripts live inside a 128-codepoint block, so consecutive characters
differ by small numbers — exactly the structure Unishox2 exploits and plain
Huffman discards.

This is not a final verdict on a custom codec, because our Huffman could not be
evaluated fairly: half a sentence is not a training corpus. **Re-run
`codeclab bench` when the 200-sentence corpus arrives.** Until then, Unishox2 is
the choice, and "we measured against the state of the art and chose it" is the
honest answer.

## Three corrections to the compression argument

1. **Compression buys ~1.3 SF steps, not 2.** Per-byte airtime doubles per SF
   step, so ratio R buys log2(R) steps. The "compressed at SF12 ≈ uncompressed
   at SF10" claim needs a **4.0x** ratio. Unishox2 delivers 2.41x → **1.27
   steps**. Real and worth having, but roughly one SF, not two.
2. **The Tamil single-fragment win is real and already won.** Tamil raw is 239 B
   → 2 fragments at SF10 (2421 ms). Unishox2 → 94 B → **1 fragment** (1026 ms).
   That removes an entire packet-loss opportunity, and plain Unishox2 achieves
   it. No custom codec needed.
3. **Compression can make messages BIGGER.** Held-out Huffman *expanded* English
   to 0.93x. **Mandatory policy: compress, compare against the raw size, send
   whichever is smaller, and set `FLAG_COMPRESSED` to match.** This is exactly
   what the per-packet flag is for, and it makes compression a strict
   improvement with no downside case.

## Other findings worth keeping

- **Flash cost is a non-issue.** Per-language Huffman tables are ~350 B; ten
  languages project to **3.4 KB, 0.084% of the 4 MB flash**, none of it in SRAM.
  Table size was never going to be what killed the per-language approach.
- **Grapheme clusters beat codepoints by 22–34% on every Indic script**
  (Hindi 263→188 bits, Gujarati 248→164, Tamil 373→291). A future codebook
  should be built on grapheme clusters — base character plus its combining
  marks — not raw codepoints. English is unaffected (no combining marks).
- **The escape mechanism costs ~1.13x expansion in the worst case** (Tamil
  pushed through a Hindi table: 270 B vs 239 B raw) but never loses a character.
  Verified for cross-script text, the rupee sign, and emoji.
- **Truncated compressed streams were rejected at all 48 cut points tested**,
  but a codec cannot guarantee that in general — it has no redundancy to check.
  **The packet CRC is what protects against truncation**, and it runs before the
  codec is ever called.

## Conventions

- Everything in `lib/protocol` is `namespace lorax`.
- Functions return explicit result enums, never bare `bool`, so failures are
  distinguishable. Every enum has a `*Name()` function for logging.
- **Validation rejects rather than masks or truncates.** A packet that reaches
  the air malformed corrupts speech at the other end; failing loudly at the call
  site is always cheaper.
- `millis()` wraparound: compare timestamps with unsigned subtraction
  (`(uint32_t)(now - then) >= timeout`), never `<`. See `Reassembler::expired`.
