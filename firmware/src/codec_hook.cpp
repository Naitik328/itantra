#include "codec_hook.h"

namespace lorax {

bool codecAvailable() {
    return false;
}

bool compressMessage(const uint8_t* in, size_t inLen,
                     uint8_t* out, size_t outCap, size_t& outLen) {
    (void)in;
    (void)inLen;
    (void)out;
    (void)outCap;
    outLen = 0;
    // No codec linked. Declining here is what keeps FLAG_COMPRESSED clear and
    // the raw path working; it is a deliberate state, not a stub that forgot to
    // be finished. Wiring Unishox2 in means replacing this body with:
    //     const int n = unishox2_compress_simple(...);
    //     if (n <= 0 || (size_t)n >= inLen) return false;   // never expand
    //     outLen = n; return true;
    return false;
}

bool decompressMessage(const uint8_t* in, size_t inLen,
                       uint8_t* out, size_t outCap, size_t& outLen) {
    (void)in;
    (void)inLen;
    (void)out;
    (void)outCap;
    outLen = 0;
    // Reachable only if a peer set FLAG_COMPRESSED, which this build never
    // does. Returning false makes the caller drop the message loudly instead of
    // speaking compressed bytes as if they were text.
    return false;
}

}  // namespace lorax
