// Canonical Huffman codec — RUNTIME half.
//
// Plain C++17, no Arduino, no allocation, no exceptions. This file and a
// generated table header are all the firmware would need; nothing here knows
// how tables are trained. Copy both into lib/protocol/ to ship it.
//
// WHY CANONICAL HUFFMAN
//   A canonical code is fully described by the code LENGTH of each symbol -
//   the actual bit patterns are derivable. So the flash table stores lengths
//   and symbols, never a tree of pointers. Decoding is a loop over bit lengths
//   with two small lookup arrays, not a pointer chase.
//
// STREAM FORMAT
//   [huffman codes...] [EOS code] [zero padding to byte boundary]
//
//   Self-terminating via an EOS symbol rather than a length prefix. Bit padding
//   at the end could otherwise decode as a spurious character, and a length
//   prefix can disagree with the content it describes. EOS costs a few bits
//   once per message and cannot be inconsistent.
//
// UNKNOWN CHARACTERS
//   A table trained on any finite corpus will meet characters it has never
//   seen. ESCAPE + RAW_CODEPOINT_BITS raw bits encodes any Unicode scalar.
//   This is a CORRECTNESS mechanism, not an optimisation: an unencodable
//   character must never corrupt the output, because downstream TTS degrades
//   silently and would simply speak the wrong thing.

#pragma once

#include <cstddef>
#include <cstdint>

namespace lorax::codec {

// Sentinels placed above the Unicode maximum (0x10FFFF) so they can never
// collide with a real codepoint.
constexpr uint32_t SYM_ESCAPE = 0x110000;
constexpr uint32_t SYM_EOS    = 0x110001;

// 21 bits covers U+0000..U+10FFFF exactly.
constexpr uint8_t RAW_CODEPOINT_BITS = 21;

// Canonical codes are held in a uint16_t, so this is the hard ceiling.
constexpr uint8_t MAX_CODE_BITS = 15;

// All arrays live in flash. `symbols` is in canonical order (by code length,
// then by symbol value); `sortedIdx` indexes it in ascending symbol order so
// the encoder can binary-search.
struct HuffTable {
    const uint32_t* symbols;
    const uint8_t*  lengths;
    const uint16_t* codes;
    const uint16_t* sortedIdx;
    const uint16_t* countPerLen;  // [maxLength + 1], index 0 unused
    const uint16_t* firstCode;    // [maxLength + 1]
    const uint16_t* firstIndex;   // [maxLength + 1]
    uint16_t        symbolCount;
    uint8_t         maxLength;
};

enum class CodecResult : uint8_t {
    Ok = 0,
    OutputTooSmall,
    TruncatedInput,   // ran out of bits before EOS
    BadCode,          // bit pattern matches no symbol
    BadEscape,        // escaped codepoint is out of Unicode range
    InvalidTable,
};

const char* codecResultName(CodecResult r);

// Bytes this table needs in flash, including all its arrays.
size_t tableFlashBytes(const HuffTable& t);

// Encodes `n` codepoints. Writes to `out`, sets `outLen`.
CodecResult encode(const HuffTable& t, const uint32_t* cps, size_t n,
                   uint8_t* out, size_t outCap, size_t& outLen);

// Decodes until EOS. Writes at most `outCap` codepoints, sets `outLen`.
CodecResult decode(const HuffTable& t, const uint8_t* in, size_t inLen,
                   uint32_t* out, size_t outCap, size_t& outLen);

}  // namespace lorax::codec
