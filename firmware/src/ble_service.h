// BLE GATT server and the app<->radio ADAPTER.
//
// ===========================================================================
// WHAT THE ADAPTER IS, AND WHERE ITS BOUNDARY SITS
// ===========================================================================
//
//   phone  --BLE write (envelope)-->  [ADAPTER]  --> LoraLink --> radio
//   phone  <--BLE notify (envelope)-- [ADAPTER]  <-- LoraLink <-- radio
//
// ABOVE the adapter the app speaks its own envelope (envelope.h) and knows
// nothing about LoRa. BELOW it, the protocol layer speaks packets and knows
// nothing about the app. The adapter is the ONLY place both vocabularies are
// in scope, and it is deliberately thin: parse, extract, hand over; receive,
// rewrap, notify.
//
// What crosses the boundary going down:  type, lang, payload bytes.
// What does NOT:                         src, seq, crc  (regenerated on the
//                                        far side - agreed as advisory, since
//                                        seq/ACK/retry/dedup are owned by the
//                                        LoRa hop).
//
// A message reaching the far phone is therefore structurally identical to one
// that went phone-to-phone directly. The extenders are transparent at this
// layer, which is the whole point.
//
// THREADING: NimBLE callbacks run on the NimBLE host task, not on loop().
// Writes are copied into a FreeRTOS queue and drained by loop(); nothing
// touches LoraLink from the BLE task.

#pragma once

#include <freertos/FreeRTOS.h>
#include <freertos/queue.h>

#include <cstdint>

#include "envelope.h"
#include "link.h"

namespace lorax {

// Confirmed with the Android side. Hardcoded on purpose - these are a contract,
// not a configuration.
constexpr const char* BLE_SERVICE_UUID = "0000ff00-4fad-419e-8250-deda85bf3446";
constexpr const char* BLE_TX_UUID      = "0000ff01-4fad-419e-8250-deda85bf3446";
constexpr const char* BLE_RX_UUID      = "0000ff02-4fad-419e-8250-deda85bf3446";
constexpr const char* BLE_STATUS_UUID  = "0000ff03-4fad-419e-8250-deda85bf3446";

class BleService {
public:
    struct Counters {
        uint32_t writesReceived   = 0;
        uint32_t envelopesRelayed = 0;
        uint32_t envelopesNotified = 0;
        uint32_t droppedBadFrame  = 0;
        uint32_t droppedBadType   = 0;
        uint32_t droppedQueueFull = 0;
        uint32_t sendFailures     = 0;   // link would not accept the message
        uint32_t alertFailures    = 0;   // ...and it was an ALERT
    };

    bool begin(LoraLink& link, const char* deviceName = "iTantra-Relay");
    void loop(uint32_t nowMs);

    bool connected() const { return connected_; }
    const Counters& counters() const { return counters_; }

    // Called from the NimBLE task. Copies and returns immediately.
    void enqueueWrite(const uint8_t* data, size_t len);
    void setConnected(bool c) { connected_ = c; }

private:
    struct QueuedFrame {
        uint16_t len = 0;
        uint8_t  bytes[app::ENVELOPE_MAX_FRAME] = {};
    };

    void drainWrites(uint32_t nowMs);
    void publishIncoming(uint32_t nowMs);
    void publishStatus(uint32_t nowMs, bool force = false);

    LoraLink* link_ = nullptr;
    void*     rxChar_     = nullptr;  // NimBLECharacteristic*
    void*     statusChar_ = nullptr;
    QueueHandle_t writeQueue_ = nullptr;

    bool     connected_      = false;
    uint32_t lastStatusMs_   = 0;
    uint8_t  outSeq_         = 0;
    Counters counters_;
};

}  // namespace lorax
