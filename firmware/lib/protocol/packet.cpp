#include "packet.h"

#include <cstring>

#include "crc16.h"

namespace lorax {

const char* decodeResultName(DecodeResult r) {
    switch (r) {
        case DecodeResult::Ok:               return "Ok";
        case DecodeResult::TooShort:         return "TooShort";
        case DecodeResult::TooLong:          return "TooLong";
        case DecodeResult::CrcMismatch:      return "CrcMismatch";
        case DecodeResult::LengthMismatch:   return "LengthMismatch";
        case DecodeResult::BadVersion:       return "BadVersion";
        case DecodeResult::WrongNetwork:     return "WrongNetwork";
        case DecodeResult::BadFragmentation: return "BadFragmentation";
    }
    return "Unknown";
}

void Packet::setCompressed(bool on) {
    if (on) {
        flags = static_cast<uint8_t>(flags | FLAG_COMPRESSED);
    } else {
        flags = static_cast<uint8_t>(flags & ~FLAG_COMPRESSED);
    }
}

void Packet::setAlert(bool on) {
    if (on) {
        flags = static_cast<uint8_t>(flags | FLAG_ALERT);
    } else {
        flags = static_cast<uint8_t>(flags & ~FLAG_ALERT);
    }
}

bool Packet::setPayload(const uint8_t* data, size_t len) {
    if (len > MAX_PAYLOAD) {
        return false;
    }
    if (len > 0) {
        if (data == nullptr) {
            return false;
        }
        std::memcpy(payload, data, len);
    }
    payloadLen = static_cast<uint8_t>(len);
    return true;
}

size_t serialize(const Packet& in, uint8_t* out, size_t outCapacity) {
    // Reject rather than silently truncate or mask. Every one of these is a
    // caller bug, and a bug that reaches the air is a bug that corrupts speech.
    if (out == nullptr) {
        return 0;
    }
    if (in.version > 3) {
        return 0;
    }
    if (in.langId > MAX_LANGUAGE_ID) {
        return 0;
    }
    if (in.fragCount < 1 || in.fragCount > MAX_FRAGMENTS) {
        return 0;
    }
    if (in.fragIndex >= in.fragCount) {
        return 0;
    }
    if (in.payloadLen > MAX_PAYLOAD) {
        return 0;
    }
    // flags carries only the two user bits; the network id has its own field.
    if ((in.flags & ~FLAG_USER_MASK) != 0) {
        return 0;
    }
    if (in.networkId > MAX_NETWORK_ID) {
        return 0;
    }
    if (in.appType > MAX_APP_TYPE) {
        return 0;
    }

    const size_t total = OVERHEAD + in.payloadLen;
    if (outCapacity < total) {
        return 0;
    }

    out[0] = static_cast<uint8_t>((in.version << 6) |
                                  (static_cast<uint8_t>(in.type) << 4) |
                                  (in.langId & 0x0F));
    out[1] = static_cast<uint8_t>((in.flags & FLAG_USER_MASK) |
                                 ((in.networkId << FLAG_NETWORK_SHIFT) & FLAG_NETWORK_MASK) |
                                 ((in.appType << FLAG_APPTYPE_SHIFT) & FLAG_APPTYPE_MASK));
    out[2] = in.msgId;
    out[3] = static_cast<uint8_t>((in.fragIndex << 4) |
                                  ((in.fragCount - 1) & 0x0F));
    out[4] = in.payloadLen;

    if (in.payloadLen > 0) {
        std::memcpy(out + HEADER_SIZE, in.payload, in.payloadLen);
    }

    // CRC covers the header *and* the payload, over one contiguous span.
    const uint16_t c = crc16(out, HEADER_SIZE + in.payloadLen);
    out[HEADER_SIZE + in.payloadLen]     = static_cast<uint8_t>(c >> 8);
    out[HEADER_SIZE + in.payloadLen + 1] = static_cast<uint8_t>(c & 0xFF);

    return total;
}

DecodeResult deserialize(const uint8_t* in, size_t len, Packet& out) {
    if (in == nullptr || len < OVERHEAD) {
        return DecodeResult::TooShort;
    }
    if (len > MAX_FRAME) {
        return DecodeResult::TooLong;
    }

    // Check the CRC before interpreting a single field. Until it passes we do
    // not know that any byte in this buffer means what it claims to mean.
    const size_t covered = len - CRC_SIZE;
    const uint16_t want = static_cast<uint16_t>((in[covered] << 8) | in[covered + 1]);
    if (crc16(in, covered) != want) {
        return DecodeResult::CrcMismatch;
    }

    const uint8_t payloadLen = in[4];
    if (static_cast<size_t>(payloadLen) + OVERHEAD != len) {
        return DecodeResult::LengthMismatch;
    }

    const uint8_t version = static_cast<uint8_t>(in[0] >> 6);
    if (version != PROTOCOL_VERSION) {
        return DecodeResult::BadVersion;
    }

    // Ours? The sync word already filtered most foreign traffic at the PHY;
    // this catches anything that shared it.
    const uint8_t networkId =
        static_cast<uint8_t>((in[1] & FLAG_NETWORK_MASK) >> FLAG_NETWORK_SHIFT);
    if (networkId != NETWORK_ID) {
        return DecodeResult::WrongNetwork;
    }

    const uint8_t fragIndex = static_cast<uint8_t>(in[3] >> 4);
    const uint8_t fragCount = static_cast<uint8_t>((in[3] & 0x0F) + 1);
    if (fragIndex >= fragCount) {
        return DecodeResult::BadFragmentation;
    }

    out.version    = version;
    out.type       = static_cast<PacketType>((in[0] >> 4) & 0x03);
    out.langId     = static_cast<uint8_t>(in[0] & 0x0F);
    out.flags      = static_cast<uint8_t>(in[1] & FLAG_USER_MASK);
    out.networkId  = networkId;
    out.appType    = static_cast<uint8_t>((in[1] & FLAG_APPTYPE_MASK) >> FLAG_APPTYPE_SHIFT);
    out.msgId      = in[2];
    out.fragIndex  = fragIndex;
    out.fragCount  = fragCount;
    out.payloadLen = payloadLen;
    if (payloadLen > 0) {
        std::memcpy(out.payload, in + HEADER_SIZE, payloadLen);
    }
    return DecodeResult::Ok;
}

Packet makeControl(PacketType type, uint8_t msgId, uint8_t fragIndex, uint8_t fragCount) {
    Packet p;
    p.type       = type;
    p.msgId      = msgId;
    p.fragIndex  = fragIndex;
    p.fragCount  = fragCount;
    p.payloadLen = 0;
    return p;
}

}  // namespace lorax
