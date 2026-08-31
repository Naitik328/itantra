// Radio link configuration constants.
//
// Deliberately written BEFORE any radio code exists, so that the values are a
// decision on record rather than something typed into a RadioLib call at 3am
// during bring-up. No RadioLib or Arduino dependency - plain constants, so the
// native tests and the airtime tooling can use the same numbers the firmware will.

#pragma once

#include <cstdint>

namespace lorax {

// --- band ------------------------------------------------------------------
// India ISM 865-867 MHz. 866.0 MHz centres a 125 kHz channel with ~1 MHz of
// clearance either side.
// TODO(hardware): confirm the applicable WPC power/duty-cycle limit before any
// extended field testing. 22 dBm is the E22-900M22S module maximum.
// 866.0 MHz: dead centre of 865-867, so a 125 kHz channel keeps ~1 MHz of
// clearance from both band edges. It also sits clear of 865.0625 / 865.4025 /
// 865.6025 MHz, the channels Indian LoRaWAN gateways commonly occupy - one
// fewer thing to collide with at a venue. Move to 866.5 if 866.0 turns out busy.
constexpr float   LORA_FREQUENCY_MHZ    = 866.0f;
constexpr float   LORA_BANDWIDTH_KHZ    = 125.0f;
constexpr uint8_t LORA_CODING_RATE      = 5;     // 4/5
constexpr uint16_t LORA_PREAMBLE_SYMBOLS = 8;

// Spreading factor. SF9-SF10 is the working range: SF11/SF12 blow the latency
// budget on a single fragment, SF7/SF8 waste range we paid for.
//
// This is deliberately a build flag, because the 800 ms STT figure is still an
// assumption. If STT measures worse, the radio budget shrinks and SF10 may stop
// fitting - drop to SF9 by rebuilding, no code edit:
//     pio run -e esp32s3 -DLORA_SF=9
#ifndef LORA_SF
#define LORA_SF 10
#endif
constexpr uint8_t LORA_SPREADING_FACTOR = LORA_SF;
static_assert(LORA_SPREADING_FACTOR >= 7 && LORA_SPREADING_FACTOR <= 12,
              "SF must be 7..12");

// --- transmit power --------------------------------------------------------
//
// E22-900M22S maximum is +22 dBm conducted. Compliance arithmetic, with the
// 3.2 dBi antenna:
//
//     EIRP = 22 dBm + 3.2 dBi            = 25.2 dBm EIRP
//     ERP  = EIRP - 2.15 dB              = 23.05 dBm ERP
//
// India's 865-867 MHz ISM allowance is 1 W ERP (30 dBm ERP), so 23.05 dBm ERP
// leaves ~7 dB of headroom. Legal either way you measure it - note that 25.2
// dBm is the EIRP figure, not ERP; the two differ by the 2.15 dB dipole
// reference and are easy to conflate.
//
// TODO(hardware): confirm the applicable WPC duty-cycle limit before extended
// field testing. requiredOffTimeMs() computes off-time but nothing enforces it.
constexpr int8_t  LORA_TX_POWER_DBM     = 22;

// SX1262 over-current protection. The datasheet specifies 140 mA for +22 dBm;
// leaving this at the reset default can clip the PA on transmit.
constexpr float   LORA_CURRENT_LIMIT_MA = 140.0f;

// The E22-900M22S carries a 32 MHz TCXO powered from the SX1262's DIO3 pin at
// 1.8 V. THIS IS NOT OPTIONAL: if the TCXO is left unpowered the chip cannot
// calibrate, and begin() fails with -706/-707 (SPI/CMD timeout) that looks
// exactly like a wiring fault. It is the single most common E22 bring-up trap.
constexpr float   LORA_TCXO_VOLTAGE     = 1.8f;

// Refuse to transmit anything whose time-on-air exceeds this. A misconfigured
// SF12 max-payload frame is ~6.2 s of the radio being deaf and BLE starved.
// 4000 ms passes any full SF10 frame (~2.4 s) and stops the absurd cases.
constexpr uint32_t MAX_TX_AIRTIME_MS    = 4000;

// --- sync word -------------------------------------------------------------
//
// FIRST layer of defence against foreign traffic at a crowded venue.
//
// The SX1262 rejects any frame whose sync word does not match, at the PHY,
// before the packet ever reaches our CRC. Every team that leaves RadioLib at
// its default is invisible to us and we to them.
//
//   0x12 = SX126x private default   <- DO NOT USE, everyone else will
//   0x34 = LoRaWAN public           <- reserved, do not use
//   0x26 = ours, mnemonic for SIH26173
//
// Both boxes must be flashed from the same build for this to work.
constexpr uint8_t LORA_SYNC_WORD = 0x26;

static_assert(LORA_SYNC_WORD != 0x12, "0x12 is the SX126x default - every other team will be on it");
static_assert(LORA_SYNC_WORD != 0x34, "0x34 is reserved for LoRaWAN");

// --- pin map: Waveshare ESP32-S3-Zero <-> Ebyte E22-900M22S ----------------
//
// Full rationale and a solderable table: docs/hardware-wiring.md
//
// Constraints this mapping respects on THIS board:
//   * GPIO 26-32  consumed by in-package flash/PSRAM, not exposed
//   * GPIO 33-37  NOT led out by Waveshare (reserved for octal PSRAM variants)
//   * GPIO 19/20  native USB D-/D+, not exposed
//   * GPIO 43/44  UART0
//   * GPIO 21     onboard WS2812 RGB LED
//   * GPIO 0/3/45/46  ESP32-S3 strapping pins - avoided entirely
//
// SPI uses the FSPI IO_MUX pins (10-13) so the bus routes directly rather than
// through the GPIO matrix. At SX1262 clock rates either works; this costs
// nothing and removes a variable.
constexpr int8_t PIN_LORA_SCK   = 12;  // FSPICLK
constexpr int8_t PIN_LORA_MISO  = 13;  // FSPIQ
constexpr int8_t PIN_LORA_MOSI  = 11;  // FSPID
constexpr int8_t PIN_LORA_NSS   = 10;  // FSPICS0
constexpr int8_t PIN_LORA_RESET = 5;   // E22 NRST, active low
constexpr int8_t PIN_LORA_BUSY  = 6;   // E22 BUSY, output from module
constexpr int8_t PIN_LORA_DIO1  = 7;   // E22 DIO1, interrupt source

// The E22-900M22S has an external RF switch. Both pins are ACTIVE HIGH and both
// must be driven or the module neither transmits nor receives - a failure that
// presents as "SPI works, chip version reads fine, but nothing goes out".
constexpr int8_t PIN_LORA_RXEN  = 15;
constexpr int8_t PIN_LORA_TXEN  = 16;

constexpr uint32_t LORA_SPI_HZ = 2000000;  // 2 MHz; SX1262 tolerates up to 16

}  // namespace lorax
