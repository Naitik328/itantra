// The APP-LEVEL envelope — Naitik's frame, defined in the Android repo
// (itantra, protocol/WireFrame.kt). This is the phone-facing contract.
//
// ===========================================================================
// LAYER BOUNDARY — read this before changing anything here
// ===========================================================================
//
//   Phone A --BLE--> ESP32-A ==LoRa==> ESP32-B --BLE--> Phone B
//           ^                                          ^
//        envelope                                   envelope
//        (this file)                                (this file)
//                     \________________________/
//                        packet.h - OUR format,
//                        the app never sees it
//
// The envelope is what the app speaks, byte-identical whether a message goes
// phone-to-phone directly or through both extenders. Our boxes are transparent
// at this layer: the adapter unwraps an envelope on one side and rebuilds an
// equivalent one on the other. Nothing about the radio hop leaks upward.
//
// This file therefore owns NOTHING about LoRa. No fragmentation, no
// compression, no netId. It is a pure codec for someone else's format, kept
// here (rather than in src/) only so it can be tested natively.
//
// ===========================================================================
// WIRE FORMAT (big-endian) — 6-byte header + payload + 2-byte CRC
// ===========================================================================
//
//   [0]      version (hi 4b) | type (lo 4b)
//   [1]      src
//   [2]      dst      0xFF = broadcast
//   [3]      lang     0..255 in the field; 0..9 in practice
//   [4]      seq
//   [5]      len      payload length
//   [6..]    payload  UTF-8, len bytes
//   last 2   crc16    CCITT-FALSE over bytes [0 .. 5+len]
//
// MAX_PAYLOAD is 247, so a full envelope is exactly 6+247+2 = 255 bytes.

#pragma once

#include <cstddef>
#include <cstdint>

namespace lorax::app {

constexpr size_t  ENVELOPE_HEADER_SIZE = 6;
constexpr size_t  ENVELOPE_CRC_SIZE    = 2;
constexpr size_t  ENVELOPE_OVERHEAD    = ENVELOPE_HEADER_SIZE + ENVELOPE_CRC_SIZE;  // 8
constexpr size_t  ENVELOPE_MAX_PAYLOAD = 247;
constexpr size_t  ENVELOPE_MAX_FRAME   = ENVELOPE_OVERHEAD + ENVELOPE_MAX_PAYLOAD;  // 255
constexpr uint8_t ENVELOPE_VERSION     = 1;
constexpr uint8_t ENVELOPE_BROADCAST   = 0xFF;

// Only 0, 1 and 2 may cross this boundary. 3-5 are the Wi-Fi Direct handshake
// values (HELLO / ACCEPT / DECLINE) which are consumed by the phone's own
// transport readLoop and structurally cannot reach BLE - but we reject them
// anyway. Belt and suspenders: a type we do not understand is a type we must
// not forward, whatever the sender's transport claims to guarantee.
enum class AppType : uint8_t {
    Normal = 0,
    Alert  = 1,
    Ack    = 2,
};
constexpr uint8_t APP_TYPE_MAX_SUPPORTED = 2;

enum class EnvelopeResult : uint8_t {
    Ok = 0,
    TooShort,
    TooLong,
    CrcMismatch,
    LengthMismatch,
    BadVersion,
    UnsupportedType,   // 3..15 - dropped deliberately
};

const char* envelopeResultName(EnvelopeResult r);

struct Envelope {
    uint8_t version = ENVELOPE_VERSION;
    uint8_t type    = static_cast<uint8_t>(AppType::Normal);
    uint8_t src     = 0;
    uint8_t dst     = ENVELOPE_BROADCAST;
    uint8_t lang    = 0;
    uint8_t seq     = 0;
    uint8_t payloadLen = 0;
    uint8_t payload[ENVELOPE_MAX_PAYLOAD] = {};

    bool isAlert() const { return type == static_cast<uint8_t>(AppType::Alert); }
    bool setPayload(const uint8_t* data, size_t len);
};

// Validates in this order: length bounds, CRC, declared length, version, type.
// As with packet.h, no field is trusted before the CRC passes.
EnvelopeResult parseEnvelope(const uint8_t* in, size_t len, Envelope& out);

// Returns bytes written, or 0 on error.
size_t buildEnvelope(const Envelope& in, uint8_t* out, size_t outCapacity);

}  // namespace lorax::app
