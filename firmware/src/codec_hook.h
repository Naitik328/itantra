// The compression seam.
//
// ===========================================================================
// CONTRACT — compression applies to PAYLOAD BYTES ONLY
// ===========================================================================
//
// Compression operates on the plain message bytes handed down from the app,
// BEFORE serialize() builds any frame. It NEVER touches:
//
//   * a serialized frame          (the CRC would then cover compressed header
//                                  bytes, and the receiver could not read the
//                                  COMPRESSED flag without first decompressing)
//   * any header field            (headers are fixed-width and already minimal;
//                                  56 bits has nothing to squeeze)
//   * an individual fragment      (compression runs on the WHOLE message before
//                                  fragmentation - see below)
//
// WHY WHOLE-MESSAGE, NOT PER-FRAGMENT
//   A codec's dictionary and adjacency modelling need the full text. Compressing
//   each fragment separately restarts that state per fragment and throws away
//   cross-fragment redundancy. It also breaks the size comparison below, because
//   fragment boundaries depend on the compressed size, which depends on the
//   fragmentation. Compress once, then fragment the result.
//
// WHY THE FLAG IS READABLE WITHOUT DECOMPRESSING
//   FLAG_COMPRESSED lives in header byte 1, outside the payload, and is covered
//   by the CRC. deserialize() checks the CRC first and only then extracts flags,
//   so by the time a receiver reads the flag the byte is both verified and
//   plaintext. The receiver decides whether to decompress; it never has to
//   decompress to find out.
//
// POLICY — NEVER SEND A BIGGER PAYLOAD THAN RAW
//   Measured in tools/: held-out Huffman EXPANDED English to 0.93x. Any codec
//   can inflate. compressMessage() therefore returns false unless the result is
//   strictly smaller, and the caller sends raw with the flag CLEAR. That makes
//   compression a strict improvement with no downside case.
//
// STATUS: no codec is shipped. compressMessage() always declines, so the
// uncompressed path is the working path and FLAG_COMPRESSED is never set on
// transmit. Unishox2 is the measured choice (2.41x) - see CLAUDE.md. Dropping
// it in means implementing these two functions and nothing else.

#pragma once

#include <cstddef>
#include <cstdint>

namespace lorax {

// Compresses the whole message. Returns true ONLY if the compressed form is
// strictly smaller than the input; on false the caller must send `in` verbatim
// with FLAG_COMPRESSED clear.
bool compressMessage(const uint8_t* in, size_t inLen,
                     uint8_t* out, size_t outCap, size_t& outLen);

// Reverses compressMessage(). Called only when a fully reassembled message
// arrived with FLAG_COMPRESSED set. Returns false if no codec is available or
// the stream is undecodable - the caller must then discard the message rather
// than hand up bytes it cannot vouch for.
bool decompressMessage(const uint8_t* in, size_t inLen,
                       uint8_t* out, size_t outCap, size_t& outLen);

// True when a codec is actually linked in. Lets the bring-up sketch and logs
// state plainly which path is live.
bool codecAvailable();

}  // namespace lorax
