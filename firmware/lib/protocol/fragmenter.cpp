#include "fragmenter.h"

#include <cstring>

namespace lorax {

const char* fragmentResultName(FragmentResult r) {
    switch (r) {
        case FragmentResult::Ok:               return "Ok";
        case FragmentResult::InvalidArgument:  return "InvalidArgument";
        case FragmentResult::InvalidChunkSize: return "InvalidChunkSize";
        case FragmentResult::TooManyFragments: return "TooManyFragments";
        case FragmentResult::OutputTooSmall:   return "OutputTooSmall";
    }
    return "Unknown";
}

size_t fragmentCount(size_t msgLen, size_t chunkSize) {
    if (chunkSize == 0) {
        return 0;
    }
    if (msgLen == 0) {
        return 1;  // an empty message is still a message
    }
    return (msgLen + chunkSize - 1) / chunkSize;
}

FragmentResult fragment(const uint8_t* msg, size_t msgLen, size_t chunkSize,
                        const FragmentOptions& opt, Packet* out,
                        size_t outCapacity, size_t& outCount) {
    outCount = 0;

    if (chunkSize == 0 || chunkSize > MAX_PAYLOAD) {
        return FragmentResult::InvalidChunkSize;
    }
    if (msg == nullptr && msgLen > 0) {
        return FragmentResult::InvalidArgument;
    }
    if (out == nullptr) {
        return FragmentResult::InvalidArgument;
    }
    if (opt.langId > MAX_LANGUAGE_ID) {
        return FragmentResult::InvalidArgument;
    }

    const size_t count = fragmentCount(msgLen, chunkSize);
    // Fail loudly rather than silently truncating the message. A caller that
    // hits this must either raise the chunk size or split at a higher layer.
    if (count > MAX_FRAGMENTS) {
        return FragmentResult::TooManyFragments;
    }
    if (count > outCapacity) {
        return FragmentResult::OutputTooSmall;
    }

    size_t offset = 0;
    for (size_t i = 0; i < count; ++i) {
        const size_t remaining = msgLen - offset;
        const size_t take = remaining < chunkSize ? remaining : chunkSize;

        Packet& p = out[i];
        p = Packet{};
        p.version   = PROTOCOL_VERSION;
        p.type      = opt.type;
        p.langId    = opt.langId;
        p.flags     = opt.flags;
        p.appType   = opt.appType;
        p.msgId     = opt.msgId;
        p.fragIndex = static_cast<uint8_t>(i);
        p.fragCount = static_cast<uint8_t>(count);
        p.payloadLen = static_cast<uint8_t>(take);
        if (take > 0) {
            std::memcpy(p.payload, msg + offset, take);
        }
        offset += take;
    }

    outCount = count;
    return FragmentResult::Ok;
}

}  // namespace lorax
