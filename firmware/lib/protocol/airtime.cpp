#include "airtime.h"

#include <cmath>

namespace lorax {

double symbolTimeMs(const LoRaParams& p) {
    if (p.bandwidthHz == 0) {
        return 0.0;
    }
    const double chips = static_cast<double>(1u << p.sf);
    return chips * 1000.0 / static_cast<double>(p.bandwidthHz);
}

bool lowDataRateOptimizeActive(const LoRaParams& p) {
    if (p.lowDataRateOptimize >= 0) {
        return p.lowDataRateOptimize != 0;
    }
    // Semtech's rule: enable when the symbol period exceeds 16 ms.
    return symbolTimeMs(p) > 16.0;
}

uint32_t payloadSymbolCount(const LoRaParams& p, size_t payloadBytes) {
    const int sf  = static_cast<int>(p.sf);
    const int de  = lowDataRateOptimizeActive(p) ? 1 : 0;
    const int ih  = p.explicitHeader ? 0 : 1;
    const int crc = p.crcOn ? 1 : 0;
    const int cr  = static_cast<int>(p.codingRate) - 4;  // 4/5 -> 1 ... 4/8 -> 4

    const int denominator = 4 * (sf - 2 * de);
    if (denominator <= 0) {
        return 8;  // degenerate parameters (e.g. SF < 3 with LDRO); not reachable in practice
    }

    const long numerator = 8L * static_cast<long>(payloadBytes) - 4L * sf + 28 +
                           16L * crc - 20L * ih;

    long blocks = 0;
    if (numerator > 0) {
        // Integer ceiling division.
        blocks = (numerator + denominator - 1) / denominator;
    }

    const long symbols = blocks * (cr + 4);
    return static_cast<uint32_t>(8 + (symbols > 0 ? symbols : 0));
}

double timeOnAirMs(const LoRaParams& p, size_t payloadBytes) {
    const double tsym = symbolTimeMs(p);
    const double preamble = (static_cast<double>(p.preambleSymbols) + 4.25) * tsym;
    const double payload = static_cast<double>(payloadSymbolCount(p, payloadBytes)) * tsym;
    return preamble + payload;
}

size_t maxPayloadForBudget(const LoRaParams& p, double budgetMs) {
    // Time-on-air is monotonic in payload length, but it is a step function, so
    // walk down from the cap rather than trying to invert the formula.
    for (size_t n = 255; n > 0; --n) {
        if (timeOnAirMs(p, n) <= budgetMs) {
            return n;
        }
    }
    return 0;
}

double requiredOffTimeMs(double toaMs, double dutyCyclePercent) {
    if (dutyCyclePercent <= 0.0) {
        return 0.0;
    }
    return toaMs * (100.0 / dutyCyclePercent - 1.0);
}

}  // namespace lorax
