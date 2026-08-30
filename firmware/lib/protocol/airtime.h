// LoRa time-on-air calculator.
//
// Formula from the Semtech SX1276 datasheet section 4.1.1.7 (unchanged for the
// SX1262 we actually use - the modem maths is identical):
//
//   Tsym      = 2^SF / BW
//   Tpreamble = (n_preamble + 4.25) * Tsym
//   n_payload = 8 + max(ceil( (8*PL - 4*SF + 28 + 16*CRC - 20*IH)
//                             / (4*(SF - 2*DE)) ) * (CR + 4), 0)
//   Tpayload  = n_payload * Tsym
//   ToA       = Tpreamble + Tpayload
//
// PL = payload bytes, CRC = 1 if payload CRC on, IH = 1 if implicit header,
// DE = 1 if low-data-rate optimisation on, CR = 1..4 for 4/5..4/8.
//
// This file is verified against six independently measured data points
// (SF7-SF12, 168 B, 125 kHz, CR 4/5) in test/test_airtime - it reproduces all
// six to the millisecond.

#pragma once

#include <cstddef>
#include <cstdint>

namespace lorax {

struct LoRaParams {
    uint8_t  sf              = 9;       // 6..12
    uint32_t bandwidthHz     = 125000;  // 125000 / 250000 / 500000
    uint8_t  codingRate      = 5;       // denominator of 4/x: 5..8
    uint16_t preambleSymbols = 8;       // SX1262 default
    bool     explicitHeader  = true;
    bool     crcOn           = true;

    // Low-data-rate optimisation. Mandatory when a symbol lasts longer than
    // 16 ms (SF11/SF12 at 125 kHz) or the receiver drifts off the symbol.
    // -1 = decide automatically from the 16 ms rule, 0 = force off, 1 = force on.
    int8_t lowDataRateOptimize = -1;
};

// Duration of one LoRa symbol, milliseconds.
double symbolTimeMs(const LoRaParams& p);

// Whether LDRO applies for these parameters (resolves the -1 "auto" case).
bool lowDataRateOptimizeActive(const LoRaParams& p);

// Number of symbols in the payload portion of the frame.
uint32_t payloadSymbolCount(const LoRaParams& p, size_t payloadBytes);

// Total time-on-air, milliseconds, for a physical payload of `payloadBytes`.
double timeOnAirMs(const LoRaParams& p, size_t payloadBytes);

// Largest physical payload whose time-on-air still fits inside `budgetMs`.
// Returns 0 if even a 1-byte frame overruns the budget. Capped at 255.
// This is how you pick a fragment size for a given SF and latency budget.
size_t maxPayloadForBudget(const LoRaParams& p, double budgetMs);

// Off-air time a duty-cycle limit demands after a transmission of `toaMs`.
// e.g. 1% duty cycle after a 900 ms frame -> 89100 ms of silence.
double requiredOffTimeMs(double toaMs, double dutyCyclePercent);

}  // namespace lorax
