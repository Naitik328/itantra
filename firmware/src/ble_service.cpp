#include "ble_service.h"

#include <Arduino.h>
#include <NimBLEDevice.h>

#include <cstdio>
#include <cstring>

namespace lorax {

namespace {

constexpr uint32_t STATUS_INTERVAL_MS = 2000;
constexpr size_t   WRITE_QUEUE_DEPTH  = 4;

// The peer is considered "up" if we have decoded a frame from it recently.
constexpr uint32_t LINK_ALIVE_MS = 30000;

BleService* g_service = nullptr;

class TxCallbacks : public NimBLECharacteristicCallbacks {
    void onWrite(NimBLECharacteristic* c, NimBLEConnInfo&) override {
        // NimBLE host task. Copy out and return - no protocol work here.
        if (g_service == nullptr) return;
        const NimBLEAttValue v = c->getValue();
        g_service->enqueueWrite(v.data(), v.size());
    }
};

class ServerCallbacks : public NimBLEServerCallbacks {
    void onConnect(NimBLEServer*, NimBLEConnInfo&) override {
        if (g_service != nullptr) g_service->setConnected(true);
    }
    void onDisconnect(NimBLEServer*, NimBLEConnInfo&, int) override {
        if (g_service != nullptr) g_service->setConnected(false);
        NimBLEDevice::startAdvertising();
    }
};

TxCallbacks     g_txCallbacks;
ServerCallbacks g_serverCallbacks;

}  // namespace

bool BleService::begin(LoraLink& link, const char* deviceName) {
    link_ = &link;
    g_service = this;

    writeQueue_ = xQueueCreate(WRITE_QUEUE_DEPTH, sizeof(QueuedFrame));
    if (writeQueue_ == nullptr) {
        Serial.println("[ble] queue alloc failed");
        return false;
    }

    NimBLEDevice::init(deviceName);
    // The phone asks for 512; 517 is the ATT-header-inclusive equivalent, so a
    // full 255-byte envelope lands in a single write with no reassembly.
    NimBLEDevice::setMTU(517);

    NimBLEServer* server = NimBLEDevice::createServer();
    server->setCallbacks(&g_serverCallbacks);

    NimBLEService* svc = server->createService(BLE_SERVICE_UUID);

    NimBLECharacteristic* tx =
        svc->createCharacteristic(BLE_TX_UUID,
                                  NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::WRITE_NR);
    tx->setCallbacks(&g_txCallbacks);

    rxChar_ = svc->createCharacteristic(BLE_RX_UUID,
                                        NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::NOTIFY);
    statusChar_ = svc->createCharacteristic(BLE_STATUS_UUID,
                                            NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::NOTIFY);
    svc->start();

    NimBLEAdvertising* adv = NimBLEDevice::getAdvertising();
    adv->addServiceUUID(BLE_SERVICE_UUID);
    adv->start();

    Serial.printf("[ble] advertising as \"%s\"\n", deviceName);
    Serial.printf("[ble]   service %s\n", BLE_SERVICE_UUID);
    return true;
}

void BleService::enqueueWrite(const uint8_t* data, size_t len) {
    if (writeQueue_ == nullptr || data == nullptr) return;
    if (len == 0 || len > app::ENVELOPE_MAX_FRAME) {
        ++counters_.droppedBadFrame;
        return;
    }
    QueuedFrame f;
    f.len = static_cast<uint16_t>(len);
    std::memcpy(f.bytes, data, len);
    if (xQueueSend(writeQueue_, &f, 0) != pdTRUE) {
        ++counters_.droppedQueueFull;
    }
}

// --- phone -> radio ---------------------------------------------------------
void BleService::drainWrites(uint32_t nowMs) {
    QueuedFrame f;
    while (xQueueReceive(writeQueue_, &f, 0) == pdTRUE) {
        ++counters_.writesReceived;

        app::Envelope env;
        const app::EnvelopeResult er = app::parseEnvelope(f.bytes, f.len, env);
        if (er != app::EnvelopeResult::Ok) {
            // UnsupportedType lands here too: types 3-15 (the Wi-Fi Direct
            // handshake values and anything future) never reach the radio.
            if (er == app::EnvelopeResult::UnsupportedType) {
                ++counters_.droppedBadType;
                Serial.printf("[ble] dropped envelope: unsupported type %u\n",
                              static_cast<unsigned>(f.bytes[0] & 0x0F));
            } else {
                ++counters_.droppedBadFrame;
                Serial.printf("[ble] dropped envelope: %s (%u B)\n",
                              app::envelopeResultName(er),
                              static_cast<unsigned>(f.len));
            }
            continue;
        }

        // Our lang field is 4 bits. The envelope allows 0-255 but the project
        // ships 10 languages; anything wider cannot cross and must fail loudly
        // rather than be silently renumbered into the wrong language.
        if (env.lang > MAX_LANGUAGE_ID) {
            ++counters_.droppedBadFrame;
            Serial.printf("[ble] dropped envelope: lang %u exceeds 4-bit field\n",
                          static_cast<unsigned>(env.lang));
            continue;
        }

        // Boundary crossing: only type, lang and payload go down.
        const LoraLink::SendResult sr =
            link_->sendText(env.payload, env.payloadLen, env.lang, env.isAlert(),
                            env.type, nowMs);

        if (!LoraLink::accepted(sr)) {
            // NEVER log-and-continue. A message the radio would not take is a
            // message the user believes was sent. It has to surface.
            ++counters_.sendFailures;
            if (env.isAlert()) ++counters_.alertFailures;
            Serial.printf("[ble] %s NOT SENT: %s\n",
                          env.isAlert() ? "*** ALERT ***" : "message",
                          LoraLink::sendResultName(sr));
            // Push status immediately rather than waiting for the next tick, so
            // the app learns about it while the user is still looking.
            publishStatus(nowMs, true);
            continue;
        }
        ++counters_.envelopesRelayed;
        if (sr != LoraLink::SendResult::Ok) {
            Serial.printf("[ble] %s accepted (%s)\n",
                          env.isAlert() ? "ALERT" : "message",
                          LoraLink::sendResultName(sr));
        }
    }
}

// --- radio -> phone ---------------------------------------------------------
void BleService::publishIncoming(uint32_t nowMs) {
    (void)nowMs;
    LoraLink::Incoming msg;
    while (link_->takeMessage(msg)) {
        // Rebuild an envelope that is structurally what the app would have
        // received directly. src/seq are ours to regenerate; crc is recomputed
        // over the new bytes by buildEnvelope().
        app::Envelope out;
        out.version = app::ENVELOPE_VERSION;
        out.type    = msg.appType;
        out.src     = NETWORK_ID;
        out.dst     = app::ENVELOPE_BROADCAST;
        out.lang    = msg.langId;
        out.seq     = outSeq_++;
        if (!out.setPayload(msg.text, msg.len)) {
            Serial.printf("[ble] message too large to rewrap: %zu B\n", msg.len);
            continue;
        }

        uint8_t frame[app::ENVELOPE_MAX_FRAME];
        const size_t n = app::buildEnvelope(out, frame, sizeof(frame));
        if (n == 0) continue;

        if (connected_ && rxChar_ != nullptr) {
            auto* c = static_cast<NimBLECharacteristic*>(rxChar_);
            c->setValue(frame, n);
            c->notify();
            ++counters_.envelopesNotified;
        }
    }
}

void BleService::publishStatus(uint32_t nowMs, bool force) {
    if (!force && static_cast<uint32_t>(nowMs - lastStatusMs_) < STATUS_INTERVAL_MS) {
        return;
    }
    lastStatusMs_ = nowMs;
    if (!connected_ || statusChar_ == nullptr) return;

    const auto& rx = link_->radio().lastRx();
    const auto& c  = link_->counters();

    const bool alive = rx.timestampMs != 0 &&
                       static_cast<uint32_t>(nowMs - rx.timestampMs) < LINK_ALIVE_MS;

    char json[192];
    // "retrying" is structurally 0 until ARQ exists; the field is present so
    // the app's parser is stable when it does.
    // Extra fields are additive, so an app parser written against the original
    // shape keeps working. `send_failed` and `alert_failed` are what make a
    // refused message visible to the user instead of vanishing.
    std::snprintf(json, sizeof(json),
                  "{\"link\":%d,\"rssi\":%d,\"snr\":%.1f,"
                  "\"delivered\":%lu,\"retrying\":%d,\"lost\":%lu,"
                  "\"queued\":%d,\"send_failed\":%lu,\"alert_failed\":%lu}",
                  alive ? 1 : 0, static_cast<int>(rx.rssiDbm),
                  static_cast<double>(rx.snrDb),
                  static_cast<unsigned long>(c.messagesReceived), 0,
                  static_cast<unsigned long>(c.framesDropped),
                  link_->sendBusy() ? 1 : 0,
                  static_cast<unsigned long>(counters_.sendFailures),
                  static_cast<unsigned long>(counters_.alertFailures));

    auto* s = static_cast<NimBLECharacteristic*>(statusChar_);
    s->setValue(reinterpret_cast<const uint8_t*>(json), std::strlen(json));
    s->notify();
}

void BleService::loop(uint32_t nowMs) {
    if (link_ == nullptr) return;
    drainWrites(nowMs);
    publishIncoming(nowMs);
    publishStatus(nowMs);
}

}  // namespace lorax
