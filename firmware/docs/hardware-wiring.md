# Wiring: Waveshare ESP32-S3-Zero ↔ Ebyte E22-900M22S

> ## ⚠️ CONNECT THE ANTENNA BEFORE FIRST POWER-ON
> The E22's `ANT` pad (pin 21) must have an antenna or a 50 Ω load on it before
> the board is ever powered. Transmitting into an open pad reflects the full
> 22 dBm back into the PA and destroys the output stage — sometimes immediately,
> sometimes after a few transmissions. The bring-up sketch transmits every 3
> seconds, so there is no safe window to "just check something quickly".

## Solder table

Eleven connections: 9 signals + power + ground.

| E22 pin | E22 name | → ESP32-S3 | Direction | Purpose |
|---:|---|---|---|---|
| 9 | `VCC` | **3V3** | — | 1.8–3.7 V, 3.3 V nominal. 119 mA peak on TX |
| 1–5, 10–12, 20, 22 | `GND` | **GND** | — | Tie **all** of them, not just one |
| 19 | `NSS` | **GPIO10** | ESP32 → E22 | SPI chip select |
| 18 | `SCK` | **GPIO12** | ESP32 → E22 | SPI clock |
| 17 | `MOSI` | **GPIO11** | ESP32 → E22 | SPI data in |
| 16 | `MISO` | **GPIO13** | E22 → ESP32 | SPI data out |
| 15 | `NRST` | **GPIO5** | ESP32 → E22 | Reset, **active low** |
| 14 | `BUSY` | **GPIO6** | E22 → ESP32 | Busy status |
| 13 | `DIO1` | **GPIO7** | E22 → ESP32 | Interrupt (RxDone / TxDone) |
| 6 | `RXEN` | **GPIO15** | ESP32 → E22 | RF switch RX, **active high** |
| 7 | `TXEN` | **GPIO16** | ESP32 → E22 | RF switch TX, **active high** |
| 8 | `DIO2` | *leave unconnected* | — | See "RF switch" below |
| 21 | `ANT` | **antenna** | — | **Never leave open.** Connector type must be verified — see below |

These are the values in [`lib/protocol/radio_config.h`](../lib/protocol/radio_config.h).
Change them there, in one place, if you solder differently.

### Two extra wires you might not have planned for

`RXEN` and `TXEN` are easy to miss — the module has an **external RF switch**,
and both pins are active-high inputs that must be driven. Leave them floating
and you get the most confusing possible failure: SPI works, `begin()` returns
success, `randomByte()` varies, and **nothing transmits or receives**. RadioLib
drives them via `setRfSwitchPins()`; the firmware already does this.

### ⚠️ Verify the antenna connector on the unit you actually receive

**The datasheet contradicts itself, so this cannot be settled on paper.**

| Where in the manual | What it says |
|---|---|
| Pin definition table, pin 21 | "ANT Antenna interface, **stamp hole** (50 ohm characteristic impedance)" |
| Specification table | "Antenna **IPEX**/IPEX 50 ohm impedance" |
| Features list | "**IPEX and stamp hole are optional**" |

The last line is the resolution: Ebyte ships this module in **both variants**, and
the pin table documents the stamp-hole one. So the connector on your unit is not
determined by the part number.

**Inspect the module before ordering or cutting anything:**

- **U.FL/IPEX connector present** (a small square metal socket near one edge) →
  an SMA-to-IPEX pigtail plugs straight in.
- **Bare castellated pad only** → there is nothing to plug into. You must
  **hand-solder a U.FL pigtail** (or coax) to the `ANT` pad, centre conductor to
  pin 21 and shield to the adjacent grounds (pins 20 and 22). Keep the stub as
  short as possible; at 866 MHz a long unshielded tail radiates and detunes.

Getting this wrong is not a soldering inconvenience — an unterminated or badly
matched antenna path is the same hazard as no antenna at all.

### Power decoupling

Two capacitors across `VCC`/`GND`, both as close to the module pads as you can
solder them. **They do different jobs and you need both:**

| Cap | Value | Job |
|---|---|---|
| **Ceramic** | **0.1 µF (100 nF)** | High-frequency noise and supply ripple. The datasheet calls this out explicitly at pin 9: *"It is recommended to add external ceramic filter capacitor"* |
| **Electrolytic / bulk** | **100–470 µF** | The 119 mA transmit burst. A ceramic alone cannot supply a step load of that size |

The datasheet also notes: *"the filter capacitor should be increased and as close
as possible to the VCC and GND pins of module."*

Skip the bulk cap and the 3V3 rail sags on every transmission — random resets or
SPI corrupted mid-frame, which looks exactly like a firmware bug and is not one.
Skip the ceramic and you get a raised noise floor that quietly costs you range.

### Supply voltage — another datasheet inconsistency

| Where | Range |
|---|---|
| Operating-parameter table | Min **1.8 V**, Typ **3.3 V**, Max **3.7 V** |
| Pin 9 description | "1.8~3.7 V" |
| Features list | "Support **2.5 V**~3.7 V power supply" |

Moot in practice — we run **3.3 V** from the board's regulator, which every
version of the spec agrees on and which the manual explicitly recommends. Worth
knowing only if you ever consider running the module from a battery directly.
**Above 3.7 V causes permanent damage.** Logic levels are 3.3 V; the manual warns
5 V TTL "may be at risk of burning down". The ESP32-S3 is 3.3 V, so no level
shifting is needed.

## Why these pins

### Excluded outright on this board

| Pins | Why |
|---|---|
| GPIO 26–32 | In-package SPI flash / PSRAM. Not exposed, not usable. |
| GPIO 33–37 | **Not led out by Waveshare** (reserved for octal-PSRAM variants). A common trap: they are fine on the ESP32-S3 die, but this board has no pads for them. |
| GPIO 19, 20 | Native USB D−/D+. Not exposed, and the USB-C port is our only serial console. |
| GPIO 43, 44 | UART0. |
| GPIO 21 | Onboard WS2812 RGB LED. |
| GPIO 0, 3, 45, 46 | ESP32-S3 **strapping pins**, sampled at reset. GPIO45 sets `VDD_SPI` voltage — driving it at boot can misconfigure the flash rail. Not worth the risk for a signal wire. |

That leaves **1–2, 4–18, 38–42**.

### SPI on GPIO 10–13

These are the ESP32-S3's **FSPI IO_MUX** pins: `FSPICS0`=10, `FSPID`=11,
`FSPICLK`=12, `FSPIQ`=13. Using them routes SPI through the dedicated IO_MUX
path rather than the general GPIO matrix. At the 2 MHz we clock the SX1262 the
GPIO matrix would work fine — this costs nothing and removes one variable from
bring-up, which is the whole point. They are also physically contiguous, so it
is four adjacent solder joints.

### Control on GPIO 5, 6, 7 and RF switch on GPIO 15, 16

Contiguous runs again, all outside the strapping and peripheral sets, none with
boot-time behaviour. `DIO1` needs to be interrupt-capable — every ESP32-S3 GPIO
is, so that placed no constraint. Nothing here is special; they were chosen to
be adjacent and boring.

## RF switch: why `DIO2` is unconnected

The E22 datasheet allows `TXEN` to be driven either by an MCU GPIO **or** by the
SX1262's own `DIO2` pin (`setDio2AsRfSwitch(true)`), which saves a wire. We
drive both `RXEN` and `TXEN` from the MCU instead because:

- `RXEN` still needs a GPIO either way, so `DIO2` saves exactly one wire.
- With both under RadioLib's control the switch state is explicit and provable
  with a multimeter during bring-up. With `DIO2` you are debugging chip-internal
  behaviour you cannot probe.
- GPIO is not scarce here — 22 usable pins, 9 in use.

**RadioLib does support it** — `SX126x::setDio2AsRfSwitch(bool)` exists in 7.7.1.
Switching would be a two-line firmware change:

```cpp
radio_.setDio2AsRfSwitch(true);                        // DIO2 drives TXEN
radio_.setRfSwitchPins(PIN_LORA_RXEN, RADIOLIB_NC);    // MCU drives RXEN only
```

We are **not** doing it by default, because it is not actually simpler where it
counts. It frees one ESP32 GPIO — which we do not need, 22 usable and 9 in use —
but the wire count is unchanged: you drop the MCU→`TXEN` wire and add a
`DIO2`(pin 8)→`TXEN`(pin 7) jumper on the module itself. What you lose is the
ability to meter the TX switch line during bring-up, replacing a probeable
signal with chip-internal behaviour. For a first hand-soldered build that is the
wrong trade.

Revisit it if BLE work ever leaves you short of pins; the change is above.

## Antenna

865–867 MHz. A 3.2 dBi antenna gives, at the configured +22 dBm:

```
EIRP = 22 dBm + 3.2 dBi          = 25.2 dBm EIRP
ERP  = EIRP − 2.15 dB            = 23.05 dBm ERP
```

India's 865–867 MHz ISM allowance is **1 W ERP (30 dBm)**, so this sits ~7 dB
under the limit. Note that 25.2 dBm is the **EIRP** figure; ERP is 2.15 dB lower
because it references a half-wave dipole rather than an isotropic radiator. The
two are easy to conflate and the arithmetic is in
[`radio_config.h`](../lib/protocol/radio_config.h) so it stays checkable.
