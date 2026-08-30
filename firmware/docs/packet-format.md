# LoRa Wire Format — SIH26173 Extender

**Status:** implemented and tested (69 native tests passing).
**Version:** protocol version 1.
**Reference implementation:** [`lib/protocol/packet.h`](../lib/protocol/packet.h), [`packet.cpp`](../lib/protocol/packet.cpp).

---

## Scope — read this first

**This format is internal to the two ESP32 boxes. It describes one hop: the LoRa
link between them. Nothing outside this firmware implements it.**

```
Phone A  --BLE-->  ESP32-A  ==LoRa: THIS FORMAT==>  ESP32-B  --BLE-->  Phone B
         ^                                                   ^
         |                                                   |
    app-level interface                              app-level interface
    (Android's to define)                            (Android's to define)
```

Concretely:

- **The app never sees this format.** Over BLE the app sends and receives plain,
  uncompressed UTF-8 text plus a language ID and a priority flag.
- **The boxes are transparent at the app layer.** A message looks identical to
  the app whether it went phone-to-phone directly over Bluetooth or through both
  extenders. Same text in, same text out.
- **Compression, fragmentation, CRC, sequence numbers, ACKs and the network ID
  are all radio-hop concerns.** They are applied after the text leaves the phone
  and undone before it reaches the other phone. None of it leaks upward.
- **The BLE-level interface is Android's to define** (Naitik). This document does
  not specify it and does not constrain it. Tell us the format and the firmware
  parser will be built to match.

### Relationship to the app-level frame

A separate app-level frame **does** exist, in the Android repo
(`Naitik328/itantra`, `protocol/WireFrame.kt`). It is the phone-to-phone
contract and carries a 6-byte header: `ver`/`type` nibbles, `src`, `dst`,
`lang`, `seq`, `len`, plus a 2-byte CRC-16/CCITT-FALSE trailer.

**The two formats are not in conflict — they describe different hops.**
`WireFrame` is what the app speaks. The format below is what the two ESP32s
speak to each other across the LoRa link. A message is unwrapped from one and
carried by the other, then handed back up unchanged.

Points of contact worth knowing:

- **The CRC is already identical** — CRC-16/CCITT-FALSE, poly `0x1021`, init
  `0xFFFF`, no reflection, big-endian trailer over all preceding bytes. Same
  algorithm, same placement, both sides.
- `WireFrame` has **no fragmentation and no compressed flag**, and it throws
  when text exceeds its payload limit rather than splitting. That is exactly the
  gap this format fills: Tamil at 239 B does not fit one SF10 fragment.
- `WireFrame.MAX_PAYLOAD = 255` with a 6-byte header yields a 263-byte frame,
  which **exceeds the SX1262's 255-byte maximum**. A max-size app frame cannot
  cross the radio hop as-is; this is a real constraint to raise with the Android
  side, not a disagreement about format.

---

## Frame layout

**7 bytes of overhead per fragment**: a 5-byte header plus a 2-byte CRC trailer.

```
 off  size  field
 ---  ----  ----------------------------------------------------------
  0    1    [ver:2][type:2][lang:4]
  1    1    [reserved:4][netId:2][ALERT:1][COMPRESSED:1]
  2    1    msgId          0..255
  3    1    [fragIndex:4][fragCount-1:4]
  4    1    payloadLen     0..248
  5    N    payload        opaque bytes (UTF-8 text, possibly compressed)
 5+N   2    CRC-16/CCITT-FALSE over bytes [0 .. 5+N-1], big-endian
```

Maximum frame is 255 B (SX1262 explicit header), so **maximum payload is 248 B**.
An ACK is a zero-payload frame: exactly **7 bytes**, ~247 ms at SF10.

### Fields

| Field | Bits | Range | Meaning |
|---|---|---|---|
| `ver` | 2 | 0–3 | Protocol version. Currently **1**. |
| `type` | 2 | 0–3 | `0` DATA, `1` ACK, `2` NACK, `3` BEACON |
| `lang` | 4 | 0–15 | Language ID. 10 languages today (0–9). |
| `COMPRESSED` | 1 | 0/1 | Payload ran through the codec |
| `ALERT` | 1 | 0/1 | Priority: 0 normal, 1 alert |
| `netId` | 2 | 0–3 | Network ID — see below |
| reserved | 4 | 0 | Must be transmitted as zero |
| `msgId` | 8 | 0–255 | Identifies one whole message |
| `fragIndex` | 4 | 0–15 | 0-based fragment index |
| `fragCount-1` | 4 | 0–15 | Fragment count, stored minus one → **1–16 fragments** |
| `payloadLen` | 8 | 0–248 | Payload byte count |
| `CRC` | 16 | — | CRC-16/CCITT-FALSE, big-endian |

The CRC is the only multi-byte field, so it is the format's only endianness
exposure. Everything else is a single byte or packed bits.

---

## Interference defence — two layers

A hackathon venue will have many teams on 865–867 MHz. This format assumes
exactly two nodes and carries **no source/destination address**, so a foreign
frame that got through would cause `msgId` collisions and garbled reassembly.
Two independent layers prevent that.

**Layer 1 — LoRa sync word `0x26`** ([`radio_config.h`](../lib/protocol/radio_config.h)).
The SX1262 rejects a non-matching sync word at the PHY, before the frame ever
reaches our code. `0x12` (SX126x default) and `0x34` (LoRaWAN) are explicitly
avoided — a `static_assert` enforces it, because leaving the default is exactly
the failure this prevents.

**Layer 2 — 2-bit network ID in flags bits 2–3.** Checked after the CRC, so it
also catches a foreign frame that happened to share our sync word. Four networks
are available; both boxes are flashed from the same build, so they agree by
construction. Bump `NETWORK_ID` if another team turns out to be on `0x26` too.

---

## Decode order

`deserialize()` validates in this order, and the order is deliberate:

| # | Check | Failure |
|---|---|---|
| 1 | length bounds | `TooShort` / `TooLong` |
| 2 | **CRC** | `CrcMismatch` |
| 3 | `payloadLen` matches buffer | `LengthMismatch` |
| 4 | protocol version | `BadVersion` |
| 5 | network ID | `WrongNetwork` |
| 6 | `fragIndex < fragCount` | `BadFragmentation` |

**No field is interpreted before the CRC passes.** Until then we do not know
that any byte in the buffer means what it claims to mean. This matters more here
than in most protocols: downstream TTS degrades *silently* on corrupted-but-valid
UTF-8 — it skips the bad character and speaks the rest with no error. The CRC is
the only gatekeeper.

`serialize()` **rejects rather than masks or truncates** — an out-of-range
language, a bad fragment index, a set reserved bit, or an oversized payload all
return 0. A malformed frame that reaches the air corrupts speech at the far end;
failing loudly at the call site is always cheaper.

---

## Fragmentation

Messages are split into **1–16 fragments**. Every fragment except the last is
full, and all carry the same `msgId`, `lang` and flags.

Fragments are cut on **raw byte boundaries, not UTF-8 character boundaries**.
This is safe *because* reassembly emits a message only when every fragment has
arrived — the reconstructed byte stream is identical to the input, so a split
codepoint is never observed. Codepoint alignment would only matter if partial
text could be rendered, and it cannot: **TTS needs the complete string before it
can synthesise, so partial text is never passed upward.**

The reassembler handles out-of-order arrival, duplicate fragments, duplicate
whole messages (a peer retransmitting after a lost ACK), `msgId` reuse, and a
30 s abandonment timeout.

---

## Why the header is this size

At **SF10, CR 4/5** one coding block is 5 payload bytes and emits 5 symbols, so
1 payload byte = exactly 1 symbol = 8.192 ms:

> **Every header bit costs ~1.02 ms of airtime, on every fragment.**

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

Halve at SF9 (~0.57 ms/bit); quadruple at SF12 (~4.1 ms/bit).

### Design rationale

**CRC-16, not CRC-32.** At ≤2040 bits both give Hamming distance 4 — every 1-,
2- and 3-bit error caught with certainty. CRC-32 would improve only the random
residual (2⁻³² vs 2⁻¹⁶) for **+16 ms per fragment**. This CRC also sits on top
of the SX1262's own hardware CRC-16, so corruption must defeat both: ~2⁻³²
effective.

**CRC as a trailer, not a header field.** Its input is then one contiguous span,
which removes the "zero the field, hash, write it back" dance that
header-embedded checksums need — and a class of bugs with it.

**`fragCount` on every fragment.** With out-of-order delivery the first fragment
seen may be #3; without the total you cannot tell when the message is complete.
The 1-bit "last fragment" alternative defers that knowledge until the final
fragment arrives — the one most likely to be lost.

**16 fragments, not 256.** 16 × 15 B still carries the 239 B Tamil worst case.
A 2-byte index/count pair would cost 8 ms per fragment forever to buy headroom
with no use. The fragmenter returns `TooManyFragments` rather than truncating,
so the ceiling fails loudly.

**`msgId` 8 bits.** Wraps in 20–40 min at walkie-talkie pace; the reassembly
buffer times out in 30 s. The wrap period is ~50× the buffer lifetime, so a
stale fragment can never be mistaken for a live one.

**`payloadLen` is redundant** when frames come from RadioLib, which reports the
received length. It is kept so `deserialize()` can verify the caller's `len`
against the packet's own claim — a stale length variable is a real bug class.
It also makes the format self-delimiting over BLE or serial. *This is the first
field to drop if the header ever needs to shrink.*

---

## Sizing

Maximum **text** bytes per fragment (after the 7 B overhead) for a given
per-fragment airtime budget:

| SF | Tsym | 800 ms | 1200 ms | 1600 ms | 2200 ms |
|---|---|---|---|---|---|
| SF7 | 1.02 ms | 248 | 248 | 248 | 248 |
| SF8 | 2.05 ms | 248 | 248 | 248 | 248 |
| SF9 | 4.10 ms | 149 | 235 | 248 | 248 |
| SF10 | 8.19 ms | 67 | 117 | 167 | **237** |
| SF11 | 16.38 ms | 15 | 38 | 60 | 92 |
| SF12 | 32.77 ms | 0 | 8 | 18 | 38 |

**At SF10 with the full 2200 ms radio budget one fragment carries 237 text bytes
— and Tamil is 239.** It misses single-fragment delivery by two bytes.

Use `maxPayloadForBudget()` then `maxFragmentPayload()` to pick a chunk size.
**Do not hardcode 168** — a 168 B frame leaves 161 text bytes, so Hindi (162)
would need two fragments to carry one byte.

---

## Compression

`FLAG_COMPRESSED` marks whether the payload went through the codec. It exists
and round-trips through the whole stack; **no codec is wired in yet**, and the
uncompressed path is the working path.

The codec slots in at exactly two points: compress before fragmenting,
decompress after a complete reassembly. **Compress the whole message, not each
fragment** — per-fragment compression wastes the dictionary and loses the
cross-fragment redundancy that makes it worth doing.

Because the flag is per-packet, the codec can be switched off at runtime and the
fallback path is always available.
