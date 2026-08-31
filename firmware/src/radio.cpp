#include "radio.h"

#include "airtime.h"

namespace lorax {

namespace {

// Set by the ISR, cleared by loop(). volatile because the compiler must not
// cache it across the loop test.
volatile bool g_dio1Fired = false;

// IRAM_ATTR: this must not live in flash. If the flash cache is disabled when
// the interrupt arrives - which happens during SPI-flash operations - an ISR in
// flash faults the chip. Sets a flag and returns; no SPI, no logging, no work.
void IRAM_ATTR onDio1Interrupt() {
    g_dio1Fired = true;
}

LoRaParams paramsForRung(Rung r) {
    const RungConfig& c = rungConfig(r);
    LoRaParams p;
    p.sf              = c.sf;
    p.bandwidthHz     = c.bandwidthHz;
    p.codingRate      = c.codingRate;
    p.preambleSymbols = LORA_PREAMBLE_SYMBOLS;
    p.explicitHeader  = true;
    p.crcOn           = true;
    return p;
}

// The compile-time default must be a rung, so that adaptation starts from a
// known ladder position rather than an arbitrary SF that is not on it.
Rung defaultRung() {
    for (uint8_t i = 0; i < RUNG_COUNT; ++i) {
        const RungConfig& c = rungConfig(static_cast<Rung>(i));
        if (c.sf == LORA_SPREADING_FACTOR &&
            c.bandwidthHz == static_cast<uint32_t>(LORA_BANDWIDTH_KHZ * 1000.0f)) {
            return static_cast<Rung>(i);
        }
    }
    return Rung::Far;
}

}  // namespace

const char* Sx1262Radio::resultName(Result r) {
    switch (r) {
        case Result::Ok:              return "Ok";
        case Result::InitFailed:      return "InitFailed";
        case Result::Busy:            return "Busy";
        case Result::TooLong:         return "TooLong";
        case Result::AirtimeExceeded: return "AirtimeExceeded";
        case Result::TxFailed:        return "TxFailed";
    }
    return "Unknown";
}

double Sx1262Radio::airtimeMs(size_t frameBytes) const {
    return timeOnAirMs(paramsForRung(rung_), frameBytes);
}

double Sx1262Radio::airtimeMsAt(Rung r, size_t frameBytes) {
    return timeOnAirMs(paramsForRung(r), frameBytes);
}

LoRaParams Sx1262Radio::currentParams() const { return paramsForRung(rung_); }

Sx1262Radio::Result Sx1262Radio::applyRung(Rung r) {
    const RungConfig& c = rungConfig(r);
    int16_t st = radio_.setBandwidth(static_cast<float>(c.bandwidthHz) / 1000.0f);
    if (st == RADIOLIB_ERR_NONE) st = radio_.setSpreadingFactor(c.sf);
    if (st == RADIOLIB_ERR_NONE) st = radio_.setCodingRate(c.codingRate);
    if (st != RADIOLIB_ERR_NONE) {
        Serial.printf("[radio] applyRung(%s) failed: %d\n", rungName(r), st);
        return Result::InitFailed;
    }
    rung_ = r;
    Serial.printf("[radio] rung -> %s (SF%u / %lu Hz)\n", rungName(r),
                  static_cast<unsigned>(c.sf),
                  static_cast<unsigned long>(c.bandwidthHz));
    enterReceive();
    return Result::Ok;
}

Sx1262Radio::Result Sx1262Radio::begin() {
    SPI.begin(PIN_LORA_SCK, PIN_LORA_MISO, PIN_LORA_MOSI, PIN_LORA_NSS);

    // Every radio parameter comes from radio_config.h. Nothing is set anywhere
    // else, so there is exactly one place to change a setting.
    const int16_t st = radio_.begin(LORA_FREQUENCY_MHZ,
                                    LORA_BANDWIDTH_KHZ,
                                    LORA_SPREADING_FACTOR,
                                    LORA_CODING_RATE,
                                    LORA_SYNC_WORD,
                                    LORA_TX_POWER_DBM,
                                    LORA_PREAMBLE_SYMBOLS,
                                    LORA_TCXO_VOLTAGE,
                                    false /* DC-DC regulator, not LDO */);
    if (st != RADIOLIB_ERR_NONE) {
        Serial.printf("[radio] begin() failed: %d\n", st);
        if (st == RADIOLIB_ERR_SPI_CMD_TIMEOUT || st == RADIOLIB_ERR_SPI_CMD_INVALID ||
            st == RADIOLIB_ERR_SPI_CMD_FAILED) {
            Serial.println("[radio]   -706/-707 is usually the TCXO or the wiring.");
            Serial.printf("[radio]   TCXO is set to %.1f V - see docs/bringup-checklist.md\n",
                          LORA_TCXO_VOLTAGE);
        }
        return Result::InitFailed;
    }

    // The E22-900M22S has an external RF switch. Without this the module is
    // deaf and silent even though SPI works perfectly.
    radio_.setRfSwitchPins(PIN_LORA_RXEN, PIN_LORA_TXEN);
    radio_.setCurrentLimit(LORA_CURRENT_LIMIT_MA);

    radio_.setDio1Action(onDio1Interrupt);

    rung_  = defaultRung();
    state_ = State::Receiving;
    enterReceive();
    Serial.printf("[radio] up at rung %s\n", rungName(rung_));
    return Result::Ok;
}

void Sx1262Radio::enterReceive() {
    const int16_t st = radio_.startReceive();
    if (st != RADIOLIB_ERR_NONE) {
        Serial.printf("[radio] startReceive failed: %d\n", st);
    }
    state_ = State::Receiving;
}

Sx1262Radio::Result Sx1262Radio::startSend(const uint8_t* frame, size_t len,
                                           uint32_t nowMs) {
    if (state_ == State::Uninitialised) return Result::InitFailed;
    if (state_ == State::Transmitting)  return Result::Busy;
    if (frame == nullptr || len == 0 || len > MAX_FRAME) return Result::TooLong;

    // Airtime guard. A misconfigured SF/payload combination would hold the
    // radio for seconds; refuse it loudly rather than discovering it as a
    // mysterious stall during a demo.
    const double toa = airtimeMs(len);
    if (toa > static_cast<double>(MAX_TX_AIRTIME_MS)) {
        ++stats_.txRefused;
        Serial.printf("[radio] REFUSED tx: %zu B at SF%u = %.0f ms > %u ms limit\n",
                      len, static_cast<unsigned>(LORA_SPREADING_FACTOR), toa,
                      static_cast<unsigned>(MAX_TX_AIRTIME_MS));
        return Result::AirtimeExceeded;
    }

    const int16_t st = radio_.startTransmit(const_cast<uint8_t*>(frame),
                                            static_cast<size_t>(len));
    if (st != RADIOLIB_ERR_NONE) {
        Serial.printf("[radio] startTransmit failed: %d\n", st);
        enterReceive();
        return Result::TxFailed;
    }

    state_        = State::Transmitting;
    txStartedMs_  = nowMs;
    txExpectedMs_ = toa;
    return Result::Ok;
}

void Sx1262Radio::loop(uint32_t nowMs) {
    if (state_ == State::Uninitialised) return;

    if (!g_dio1Fired) {
        // Transmit watchdog. If TxDone never arrives we would sit deaf forever;
        // 3x the predicted airtime plus a floor is generous but finite.
        if (state_ == State::Transmitting) {
            const uint32_t limit =
                static_cast<uint32_t>(txExpectedMs_ * 3.0) + 1000u;
            if (static_cast<uint32_t>(nowMs - txStartedMs_) > limit) {
                Serial.println("[radio] TxDone never arrived - recovering to RX");
                radio_.finishTransmit();
                enterReceive();
            }
        }
        return;
    }
    g_dio1Fired = false;

    if (state_ == State::Transmitting) {
        radio_.finishTransmit();
        ++stats_.framesSent;
        enterReceive();
        return;
    }

    // Receiving.
    const size_t len = radio_.getPacketLength();
    if (len == 0 || len > MAX_FRAME) {
        ++stats_.rxErrors;
        enterReceive();
        return;
    }

    if (rxReady_) {
        // Previous frame not collected yet. The link layer drains every loop,
        // so this means something upstream blocked for longer than a whole
        // time-on-air - worth counting rather than hiding.
        ++stats_.rxOverruns;
    }

    uint8_t scratch[MAX_FRAME];
    const int16_t st = radio_.readData(scratch, len);
    if (st != RADIOLIB_ERR_NONE) {
        // Radio-level failure: the SX1262's own hardware CRC rejected it, or
        // the header was malformed. Our packet CRC never even sees this one.
        ++stats_.rxErrors;
        enterReceive();
        return;
    }

    memcpy(rxBuf_, scratch, len);
    rxLen_   = len;
    rxReady_ = true;

    lastRx_.rssiDbm     = radio_.getRSSI();
    lastRx_.snrDb       = radio_.getSNR();
    lastRx_.freqErrorHz = static_cast<int32_t>(radio_.getFrequencyError());
    lastRx_.timestampMs = nowMs;
    lastRx_.length      = len;
    ++stats_.framesReceived;

    enterReceive();
}

bool Sx1262Radio::takeFrame(uint8_t* out, size_t outCap, size_t& outLen,
                            RxInfo& info) {
    if (!rxReady_) return false;
    if (out == nullptr || outCap < rxLen_) {
        rxReady_ = false;
        return false;
    }
    memcpy(out, rxBuf_, rxLen_);
    outLen   = rxLen_;
    info     = lastRx_;
    rxReady_ = false;
    return true;
}

}  // namespace lorax
