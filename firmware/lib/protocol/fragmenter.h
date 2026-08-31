// Splitting a message into LoRa-sized fragments.
//
// Fragments are cut on raw byte boundaries, NOT on UTF-8 character boundaries.
// That is safe and deliberate: the reassembler only ever emits a message once
// every fragment has arrived, so the reconstructed byte stream is identical to
// the input and multi-byte codepoints are never observed split. Aligning cuts
// to codepoints would only matter if we wanted to render partial text - and we
// cannot, because TTS needs the complete string before it can synthesise.

#pragma once

#include "packet.h"

namespace lorax {

enum class FragmentResult : uint8_t {
    Ok = 0,
    InvalidArgument,   // null message with non-zero length
    InvalidChunkSize,  // chunk size 0 or > MAX_PAYLOAD
    TooManyFragments,  // message needs more than MAX_FRAGMENTS chunks
    OutputTooSmall,    // caller's Packet array cannot hold the fragments
};

const char* fragmentResultName(FragmentResult r);

struct FragmentOptions {
    uint8_t    msgId   = 0;
    uint8_t    langId  = 0;
    uint8_t    flags   = 0;  // FLAG_COMPRESSED / FLAG_ALERT
    uint8_t    appType = 0;  // app envelope type, carried opaquely
    PacketType type    = PacketType::Data;
};

// Payload bytes available per fragment once protocol overhead is paid.
constexpr size_t maxFragmentPayload(size_t maxFrameBytes) {
    return maxFrameBytes > OVERHEAD ? maxFrameBytes - OVERHEAD : 0;
}

// Fragments a message would need. An empty message is still one fragment.
size_t fragmentCount(size_t msgLen, size_t chunkSize);

// Splits `msg` into `out[0..outCount)`. Every fragment except the last is
// exactly `chunkSize` bytes; each carries the same msgId, langId and flags.
FragmentResult fragment(const uint8_t* msg, size_t msgLen, size_t chunkSize,
                        const FragmentOptions& opt, Packet* out,
                        size_t outCapacity, size_t& outCount);

}  // namespace lorax
