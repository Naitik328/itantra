// SIH26173 LoRa extender - wire packet format.
//
// Plain C++17, no Arduino headers, so this compiles and tests on a laptop.
//
// ===========================================================================
// WIRE FORMAT  -  5-byte header + payload + 2-byte CRC trailer = 7 B overhead
// ===========================================================================
//
//   off  size  field
//   ---  ----  ---------------------------------------------------------
//    0    1    [ver:2][type:2][lang:4]
//    1    1    flags   bit0 COMPRESSED, bit1 ALERT, bits2-3 netId,
//                 bits4-7 reserved (0)
//    2    1    msgId   0..255, identifies one whole message
//    3    1    [fragIndex:4][fragCount-1:4]
//    4    1    payloadLen  0..248
//    5    N    payload (opaque bytes; UTF-8 text today)
//   5+N   2    CRC-16/CCITT-FALSE over bytes [0 .. 5+N-1], big-endian
//
// The CRC is a trailer, not a header field, so its input is one contiguous
// span. That removes the "zero the field, hash, write it back" dance that
// header-embedded checksums need, and with it a whole class of bugs.
//
// Multi-byte fields: the CRC is the only one, and it is big-endian (network
// order). Everything else is a single byte or packed bits, so the format has
// no other endianness exposure.
//
// Field widths are justified in CLAUDE.md; the short version is in comments
// beside each constant below.

#pragma once

#include <cstddef>
#include <cstdint>

namespace lorax {

// --- sizes -----------------------------------------------------------------

constexpr size_t HEADER_SIZE = 5;
constexpr size_t CRC_SIZE    = 2;
constexpr size_t OVERHEAD    = HEADER_SIZE + CRC_SIZE;  // 7 bytes per fragment

// SX1262 explicit-header mode tops out at a 255-byte physical payload.
constexpr size_t MAX_FRAME   = 255;
constexpr size_t MAX_PAYLOAD = MAX_FRAME - OVERHEAD;    // 248

// fragCount is stored as (count-1) in 4 bits, so 1..16 fragments.
constexpr uint8_t MAX_FRAGMENTS = 16;

// --- header fields ---------------------------------------------------------

// 2 bits. Lets a receiver reject a peer running mismatched firmware instead of
// silently misparsing it. Four revisions is plenty for a hackathon lifetime.
constexpr uint8_t PROTOCOL_VERSION = 1;

// 2 bits.
enum class PacketType : uint8_t {
    Data   = 0,
    Ack    = 1,
    Nack   = 2,
    Beacon = 3,  // reserved for link/RSSI probing; no code depends on it yet
};

// flags byte, bits 0-1. Packet::flags holds ONLY these two bits; the network id
// lives in its own struct field and is packed into bits 2-3 on the wire.
constexpr uint8_t FLAG_COMPRESSED = 0x01;  // payload ran through the codec
constexpr uint8_t FLAG_ALERT      = 0x02;  // priority: 0 normal, 1 alert
constexpr uint8_t FLAG_USER_MASK  = 0x03;  // bits a caller may set

// --- network id (flags bits 2-3) -------------------------------------------
//
// SECOND layer of defence, under the LoRa sync word in radio_config.h. The sync
// word filters at the PHY; this filters after the CRC, so it also catches a
// foreign frame that happened to share our sync word. Two bits = 4 networks,
// which is enough to separate our pair from a neighbouring pair at a venue.
//
// Compile-time: both boxes are flashed from the same build, so they agree by
// construction. Bump it if another team turns out to be on 0x26 as well.
constexpr uint8_t NETWORK_ID          = 0;
constexpr uint8_t MAX_NETWORK_ID      = 3;
constexpr uint8_t FLAG_NETWORK_SHIFT  = 2;
constexpr uint8_t FLAG_NETWORK_MASK   = 0x0C;  // bits 2-3

// --- app frame type (flags bits 4-5) ---------------------------------------
//
// The app envelope's `type` (NORMAL/ALERT/ACK) has to survive the radio hop so
// the far-side adapter can rebuild a byte-identical envelope. Two bits, taken
// from the reserved space that existed for exactly this kind of need.
//
// This is NOT the same field as PacketType. PacketType is ours (DATA/ACK/NACK/
// BEACON, the radio hop's own control plane); appType is the app's, carried
// opaquely. They are different layers that happen to both have a "type".
constexpr uint8_t MAX_APP_TYPE        = 3;
constexpr uint8_t FLAG_APPTYPE_SHIFT  = 4;
constexpr uint8_t FLAG_APPTYPE_MASK   = 0x30;  // bits 4-5
constexpr uint8_t FLAGS_RESERVED_MASK = 0xC0;  // bits 6-7, must be 0

static_assert(NETWORK_ID <= MAX_NETWORK_ID, "NETWORK_ID must fit in 2 bits");

// 4 bits: 10 languages today (0..9), 6 spare codes.
constexpr uint8_t MAX_LANGUAGE_ID = 15;

// --- decode outcomes -------------------------------------------------------

enum class DecodeResult : uint8_t {
    Ok = 0,
    TooShort,          // fewer than OVERHEAD bytes
    TooLong,           // more than MAX_FRAME bytes
    CrcMismatch,       // the gatekeeper: bytes are not trustworthy
    LengthMismatch,    // payloadLen disagrees with the buffer we were handed
    BadVersion,        // CRC-clean, but from a different protocol revision
    WrongNetwork,      // CRC-clean, but addressed to a different network id
    BadFragmentation,  // fragIndex >= fragCount
};

const char* decodeResultName(DecodeResult r);

// --- the packet ------------------------------------------------------------
//
// The payload buffer is inline rather than a pointer: no allocation, no
// lifetime questions, and the whole struct can live in a static. It costs
// ~256 bytes, so hold it in a member or a static, not deep on a stack.

struct Packet {
    uint8_t    version   = PROTOCOL_VERSION;
    PacketType type      = PacketType::Data;
    uint8_t    langId    = 0;
    uint8_t    flags     = 0;          // FLAG_COMPRESSED / FLAG_ALERT only
    uint8_t    networkId = NETWORK_ID;  // packed into flags bits 2-3 on the wire
    uint8_t    appType   = 0;           // packed into flags bits 4-5 on the wire
    uint8_t    msgId     = 0;
    uint8_t    fragIndex = 0;  // 0-based
    uint8_t    fragCount = 1;  // 1..MAX_FRAGMENTS
    uint8_t    payloadLen = 0;
    uint8_t    payload[MAX_PAYLOAD] = {};

    bool isCompressed() const { return (flags & FLAG_COMPRESSED) != 0; }
    bool isAlert()      const { return (flags & FLAG_ALERT) != 0; }
    bool isControl()    const { return type == PacketType::Ack || type == PacketType::Nack; }

    void setCompressed(bool on);
    void setAlert(bool on);
    bool setPayload(const uint8_t* data, size_t len);
};

// Returns bytes written to `out`, or 0 if the packet is malformed or `out` is
// too small. Never writes past outCapacity.
size_t serialize(const Packet& in, uint8_t* out, size_t outCapacity);

// Validates and decodes. `out` is only meaningful when the result is Ok.
DecodeResult deserialize(const uint8_t* in, size_t len, Packet& out);

// Convenience builder for a zero-payload control frame.
Packet makeControl(PacketType type, uint8_t msgId, uint8_t fragIndex, uint8_t fragCount);

}  // namespace lorax
