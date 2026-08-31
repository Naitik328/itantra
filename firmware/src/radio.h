// SX1262 driver wrapper: interrupt-driven receive, non-blocking transmit.
//
// ===========================================================================
// HOW THE INTERRUPT MODEL WORKS
// ===========================================================================
//
// The SX1262 raises DIO1 when a configured IRQ fires - for us, RxDone and
// TxDone. RadioLib's setDio1Action() attaches an ISR to that pin. Our ISR sets
// one volatile flag and returns; loop() does the work.
//
// The ISR does NOT touch the radio, because servicing it means SPI, and SPI
// transactions take locks and cannot run in interrupt context on ESP32. It is
// also IRAM_ATTR: the flash cache can be disabled mid-SPI-flash-operation, and
// an ISR living in flash would fault when that happens.
//
// HOW MUCH TIME YOU ACTUALLY HAVE
//   The received frame sits in the SX1262's own 256-byte buffer until read over
//   SPI. Nothing is lost while it waits. What you are racing is the NEXT frame
//   arriving and wrapping that buffer - so the deadline is roughly one full
//   time-on-air:
//
//       SF7  ~270 ms      SF10 ~1.6 s
//       SF9  ~860 ms      SF12 ~6.2 s
//
//   That is an eternity for a 240 MHz MCU. Missing DIO1 by a millisecond costs
//   nothing. The real hazard is blocking for SECONDS, which is exactly what
//   RadioLib's blocking transmit() does at SF10-12 - hence the state machine
//   below. Being slow starves BLE and delays RX servicing; it does not corrupt
//   the frame already in the buffer.
//
//   Note also that LoRa is half-duplex: while transmitting we are deaf. A 2.4 s
//   Tamil frame at SF10 is 2.4 s of not hearing the peer.

#pragma once

#include <Arduino.h>
#include <RadioLib.h>

#include <cstdint>

#include "airtime.h"
#include "packet.h"
#include "radio_config.h"
#include "rate_control.h"

namespace lorax {

class Sx1262Radio {
public:
    enum class State : uint8_t { Uninitialised, Receiving, Transmitting };

    enum class Result : uint8_t {
        Ok = 0,
        InitFailed,
        Busy,             // a transmit is already in flight
        TooLong,          // frame exceeds MAX_FRAME
        AirtimeExceeded,  // refused by the airtime guard
        TxFailed,
    };

    // Range-test evidence. Captured for every received frame.
    struct RxInfo {
        float    rssiDbm     = 0.0f;
        float    snrDb       = 0.0f;
        int32_t  freqErrorHz = 0;
        uint32_t timestampMs = 0;
        size_t   length      = 0;
    };

    struct Stats {
        uint32_t framesReceived = 0;
        uint32_t framesSent     = 0;
        uint32_t rxErrors       = 0;  // radio-level (PHY CRC, header) failures
        uint32_t rxOverruns     = 0;  // frame dropped because the slot was full
        uint32_t txRefused      = 0;  // airtime guard rejections
    };

    Result begin();

    // Runs one non-blocking slice. Call every loop iteration.
    void loop(uint32_t nowMs);

    // Begins transmitting one frame. Returns immediately; completion is
    // observed via txBusy() going false.
    Result startSend(const uint8_t* frame, size_t len, uint32_t nowMs);
    bool   txBusy() const { return state_ == State::Transmitting; }

    // Collects one received frame, if any. Returns false when none is waiting.
    bool takeFrame(uint8_t* out, size_t outCap, size_t& outLen, RxInfo& info);

    const RxInfo& lastRx()  const { return lastRx_; }
    const Stats&  stats()   const { return stats_; }
    State         state()   const { return state_; }

    // Reconfigures SF/BW/CR to a ladder rung and returns to receive. Safe to
    // call at any time; a transmit in flight is not interrupted (callers check
    // txBusy() first).
    Result applyRung(Rung r);
    Rung   rung() const { return rung_; }

    // Time-on-air under the CURRENTLY APPLIED rung.
    double airtimeMs(size_t frameBytes) const;
    // Time-on-air at an arbitrary rung, for planning.
    static double airtimeMsAt(Rung r, size_t frameBytes);
    // Parameters of the current rung, for the fragmenter's chunk sizing.
    LoRaParams currentParams() const;

    static const char* resultName(Result r);

    // Direct RadioLib access. For the bring-up sketch only - the firmware path
    // must go through this class so every radio setting stays in one place.
    SX1262& raw() { return radio_; }

private:
    void enterReceive();

    SX1262   radio_ = new Module(PIN_LORA_NSS, PIN_LORA_DIO1,
                                 PIN_LORA_RESET, PIN_LORA_BUSY);
    State    state_ = State::Uninitialised;
    Rung     rung_  = Rung::Far;
    RxInfo   lastRx_;
    Stats    stats_;

    uint8_t  rxBuf_[MAX_FRAME] = {};
    size_t   rxLen_            = 0;
    bool     rxReady_          = false;

    uint32_t txStartedMs_ = 0;
    double   txExpectedMs_ = 0.0;
};

}  // namespace lorax
