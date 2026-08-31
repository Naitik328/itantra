#include "envelope.h"

#include <cstring>

#include "crc16.h"

namespace lorax::app {

const char* envelopeResultName(EnvelopeResult r) {
    switch (r) {
        case EnvelopeResult::Ok:              return "Ok";
        case EnvelopeResult::TooShort:        return "TooShort";
        case EnvelopeResult::TooLong:         return "TooLong";
        case EnvelopeResult::CrcMismatch:     return "CrcMismatch";
        case EnvelopeResult::LengthMismatch:  return "LengthMismatch";
        case EnvelopeResult::BadVersion:      return "BadVersion";
        case EnvelopeResult::UnsupportedType: return "UnsupportedType";
    }
    return "Unknown";
}

bool Envelope::setPayload(const uint8_t* data, size_t len) {
    if (len > ENVELOPE_MAX_PAYLOAD) return false;
    if (len > 0) {
        if (data == nullptr) return false;
        std::memcpy(payload, data, len);
    }
    payloadLen = static_cast<uint8_t>(len);
    return true;
}

EnvelopeResult parseEnvelope(const uint8_t* in, size_t len, Envelope& out) {
    if (in == nullptr || len < ENVELOPE_OVERHEAD) return EnvelopeResult::TooShort;
    if (len > ENVELOPE_MAX_FRAME)                 return EnvelopeResult::TooLong;

    // CRC first. Identical algorithm to our own packet CRC - CRC-16/CCITT-FALSE,
    // big-endian trailer - so the two layers already agree bit for bit.
    const size_t covered = len - ENVELOPE_CRC_SIZE;
    const uint16_t want =
        static_cast<uint16_t>((in[covered] << 8) | in[covered + 1]);
    if (crc16(in, covered) != want) return EnvelopeResult::CrcMismatch;

    const uint8_t declaredLen = in[5];
    if (static_cast<size_t>(declaredLen) + ENVELOPE_OVERHEAD != len) {
        return EnvelopeResult::LengthMismatch;
    }

    const uint8_t version = static_cast<uint8_t>((in[0] >> 4) & 0x0F);
    if (version != ENVELOPE_VERSION) return EnvelopeResult::BadVersion;

    const uint8_t type = static_cast<uint8_t>(in[0] & 0x0F);
    if (type > APP_TYPE_MAX_SUPPORTED) return EnvelopeResult::UnsupportedType;

    out.version    = version;
    out.type       = type;
    out.src        = in[1];
    out.dst        = in[2];
    out.lang       = in[3];
    out.seq        = in[4];
    out.payloadLen = declaredLen;
    if (declaredLen > 0) {
        std::memcpy(out.payload, in + ENVELOPE_HEADER_SIZE, declaredLen);
    }
    return EnvelopeResult::Ok;
}

size_t buildEnvelope(const Envelope& in, uint8_t* out, size_t outCapacity) {
    if (out == nullptr) return 0;
    if (in.version > 0x0F || in.type > 0x0F) return 0;
    if (in.payloadLen > ENVELOPE_MAX_PAYLOAD) return 0;

    const size_t total = ENVELOPE_OVERHEAD + in.payloadLen;
    if (outCapacity < total) return 0;

    out[0] = static_cast<uint8_t>(((in.version & 0x0F) << 4) | (in.type & 0x0F));
    out[1] = in.src;
    out[2] = in.dst;
    out[3] = in.lang;
    out[4] = in.seq;
    out[5] = in.payloadLen;
    if (in.payloadLen > 0) {
        std::memcpy(out + ENVELOPE_HEADER_SIZE, in.payload, in.payloadLen);
    }

    const uint16_t c = crc16(out, ENVELOPE_HEADER_SIZE + in.payloadLen);
    out[ENVELOPE_HEADER_SIZE + in.payloadLen]     = static_cast<uint8_t>(c >> 8);
    out[ENVELOPE_HEADER_SIZE + in.payloadLen + 1] = static_cast<uint8_t>(c & 0xFF);
    return total;
}

}  // namespace lorax::app
