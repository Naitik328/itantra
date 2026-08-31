# Radio bring-up checklist

Work these in order. Each step has one thing to prove and one place to look when
it fails. **Do not skip ahead** — every later step assumes the earlier ones hold,
and debugging a range problem that is actually a solder bridge wastes a day.

> ## ⚠️ STEP ZERO: THE ANTENNA
> **Solder the antenna (or a 50 Ω load) to the E22's `ANT` pad before the board
> is ever powered.** Transmitting into an open pad reflects 22 dBm straight back
> into the PA. It may die instantly or after a dozen transmissions — the delayed
> case is worse, because you will spend the evening blaming the firmware.
>
> The bring-up sketch transmits every 3 seconds from the moment it boots. There
> is no "just power it on quickly to check" window.

---

## 1. Board alone, before soldering anything

**Do:** plug in the ESP32-S3-Zero over USB-C, `pio run -e bringup -t upload`,
then `pio device monitor`.

**Working:** upload succeeds, and the banner prints. The radio init will fail —
that is expected and correct with nothing wired.

| Symptom | Likely cause |
|---|---|
| No serial port appears | Hold BOOT, tap RESET, release BOOT to force download mode |
| Port appears, no text | `monitor_speed` mismatch, or USB CDC not up. Wait 3 s after reset |
| Upload fails on flash size | Board env wrong. Must be `lolin_s3_mini` (4 MB), never `esp32-s3-devkitc-1` (8 MB) |

Proving USB and flashing work *before* there is a radio to blame is the whole
point of this step.

## 2. Solder, then inspect before powering

**Do:** wire per [`docs/hardware-wiring.md`](hardware-wiring.md). Then, **with
power off**, meter it.

**Working:**
- Continuity from each ESP32 pad to its E22 pad — all 9 signals plus VCC/GND.
- **No continuity between 3V3 and GND.** A short here kills the regulator on
  power-up.
- No continuity between adjacent E22 pads. The pitch is fine and bridges are
  easy; `MISO`/`MOSI` (16/17) and `RXEN`/`TXEN` (6/7) are the pairs to check
  twice.
- All ground pads tied, not just one.

**Antenna:** confirm which connector your module actually has — the datasheet
lists both IPEX and stamp-hole variants and its own pin table says stamp hole.
If there is no U.FL socket, a pigtail must be hand-soldered to pin 21 before
power-on. See [hardware-wiring.md](hardware-wiring.md#verify-the-antenna-connector-on-the-unit-you-actually-receive).

Check the antenna is on. Again.

## 3. Power on — does `begin()` succeed?

**Do:** power up, watch the monitor.

**Working:** `radio.begin() ... OK`.

If it fails the sketch prints the RadioLib code and stops. Work the table:

| Code | Meaning | Check, in this order |
|---:|---|---|
| **−705 / −706 / −707** | SPI command timeout / invalid / failed | **TCXO voltage first.** The E22 has a 32 MHz TCXO on the SX1262's `DIO3`; without it the chip cannot calibrate and fails exactly like a wiring fault. `LORA_TCXO_VOLTAGE` is 1.8 V — try 1.6, 2.4, 3.3, then 0.0. Then `BUSY` (GPIO6): if it is stuck high, every command times out. Then 3V3 sag. |
| **−2** | Chip not found | SPI wiring. `NSS` (10), `SCK` (12), `MOSI` (11), `MISO` (13), and `NRST` (5) — a floating reset holds the chip down |
| **−12** | Invalid frequency | `LORA_FREQUENCY_MHZ` outside the SX1262's range — should be 866.0 |
| **−13** | Invalid output power | `LORA_TX_POWER_DBM` above +22 |
| **−105** | Invalid sync word | `LORA_SYNC_WORD` malformed |

**−705/−706/−707 is by far the most common E22 first-boot failure, and TCXO is
by far the most common cause.** Try that before you re-flow any joints.

## 4. Is SPI actually alive?

**Working:** the sketch prints 8 random bytes and says `SPI read path OK`.

Those come from `randomByte()`, which samples RSSI noise. They **must vary**.

| Symptom | Meaning |
|---|---|
| All `00` | `MISO` not connected, or the module is unpowered |
| All `FF` | `MISO` floating high — check the solder joint on E22 pin 16 |
| Values vary | SPI read path is genuinely working. Move on. |

`begin()` can succeed while `MISO` is broken, because most SX126x setup is
write-only. This step is what actually proves the read path.

## 5. Transmit from one board

**Working:** `[TX] BRINGUP seq=0 (N ms on air)` every 3 seconds, counter rising,
**and the board does not reset.**

| Symptom | Likely cause |
|---|---|
| Board resets or browns out on every TX | 3V3 rail sagging under the 119 mA burst. **You need BOTH caps: 0.1 µF ceramic (datasheet-recommended, HF noise) and 100–470 µF electrolytic (the TX burst).** A ceramic alone cannot supply a 119 mA step. This is the single most common power fault |
| `[TX] refused: AirtimeExceeded` | The airtime guard fired — SF too high for the frame size. Working as designed |
| `TxDone never arrived - recovering to RX` | `DIO1` (GPIO7) not connected, or the wrong pin |
| Counter rises, nothing received elsewhere | Expected until step 6 — you need a second board |

At SF10 a 15-byte bring-up frame is roughly 100 ms on air, so nothing here
should feel slow.

## 6. Two boards — receive

**Do:** flash a second board identically. Keep them **at least 1 m apart** — a
+22 dBm transmitter a few centimetres from a receiver saturates the front end
and produces nonsense or nothing at all.

**Working:** each prints the other's counter:

```
[RX] 15 B  RSSI  -42.0 dBm  SNR   9.5 dB  ferr   +312 Hz  | BRINGUP seq=7
```

| Symptom | Likely cause |
|---|---|
| Both transmit, neither receives | **`RXEN`/`TXEN` (GPIO15/16).** The RF switch is the classic cause: everything looks healthy and no RF moves. Meter them — one should be high, the other low, and they should swap around a transmission |
| Nothing at all, boards touching | Front-end saturation. Move them apart |
| Garbage text, RSSI plausible | Sync word or radio-parameter mismatch between the two builds. Flash both from the *same* build |
| Very negative SNR (< −15 dB) | Antenna not connected, or badly mismatched |
| Large `ferr` (> ±5 kHz) | TCXO not running — revisit step 3 |

RSSI at 1 m should be roughly **−30 to −50 dBm**, SNR **+8 to +12 dB**. Much
worse than that at arm's length means an antenna or RF-switch problem, not range.

## 7. Range

**Do:** leave one board transmitting, walk the other away. Log RSSI and SNR.

**Working:** RSSI falls smoothly with distance. Packets keep arriving until SNR
approaches the SF10 demodulation floor around **−15 dB**, then drop off quickly.

`RxInfo` carries `rssiDbm`, `snrDb` and `freqErrorHz` for every frame — these are
your range-test evidence, so record them rather than eyeballing the console.

If range is far worse than expected, in order: antenna type and orientation
(these are vertically polarised — keep both antennas parallel), then TX power,
then the SF actually compiled in.

## 8. Move to the real firmware

Only once steps 1–7 all pass:

```bash
pio run -e esp32s3 -t upload
```

`env:esp32s3` adds the protocol layer — CRC, fragmentation, reassembly,
deduplication — on top of the radio you have just proven. If something breaks
now, it is protocol, not wiring, and the `[stat]` line every 10 s tells you
which: `dropped` counts frames that failed CRC or validation, `dupes` counts
suppressed retransmissions, `rxerr` counts frames the SX1262's own hardware CRC
rejected before we saw them.

---

## Notes

**The USB CDC warning at build time** — RadioLib warns that USB CDC debug output
"might stop on first sleep". We never sleep, and the S3-Zero has no other
convenient console, so it is expected. If serial output ever stops mid-session,
that warning is where to start.

**Changing spreading factor** costs a rebuild, not an edit:

```bash
pio run -e esp32s3 -DLORA_SF=9 -t upload
```

Both boards must match — mismatched SF means they cannot hear each other at all.
