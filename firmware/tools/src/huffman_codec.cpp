#include "huffman_codec.h"

namespace lorax::codec {

namespace {

class BitWriter {
public:
    BitWriter(uint8_t* out, size_t cap) : out_(out), cap_(cap) {}

    bool put(uint32_t value, uint8_t bits) {
        for (int b = bits - 1; b >= 0; --b) {
            if (bitPos_ == 0) {
                if (bytePos_ >= cap_) return false;
                out_[bytePos_] = 0;
            }
            if ((value >> b) & 1u) {
                out_[bytePos_] = static_cast<uint8_t>(out_[bytePos_] | (0x80u >> bitPos_));
            }
            if (++bitPos_ == 8) {
                bitPos_ = 0;
                ++bytePos_;
            }
        }
        return true;
    }

    // Trailing bits are already zero; zero padding can only decode as a
    // prefix of some code, and EOS has already been emitted, so it is ignored.
    size_t finish() const { return bitPos_ == 0 ? bytePos_ : bytePos_ + 1; }

private:
    uint8_t* out_;
    size_t   cap_;
    size_t   bytePos_ = 0;
    uint8_t  bitPos_  = 0;
};

class BitReader {
public:
    BitReader(const uint8_t* in, size_t len) : in_(in), len_(len) {}

    bool get(uint8_t& bit) {
        if (bytePos_ >= len_) return false;
        bit = static_cast<uint8_t>((in_[bytePos_] >> (7 - bitPos_)) & 1u);
        if (++bitPos_ == 8) {
            bitPos_ = 0;
            ++bytePos_;
        }
        return true;
    }

    bool get(uint32_t& value, uint8_t bits) {
        value = 0;
        for (uint8_t i = 0; i < bits; ++i) {
            uint8_t b = 0;
            if (!get(b)) return false;
            value = (value << 1) | b;
        }
        return true;
    }

private:
    const uint8_t* in_;
    size_t         len_;
    size_t         bytePos_ = 0;
    uint8_t        bitPos_  = 0;
};

// Binary search over sortedIdx, which orders symbols[] ascending.
bool findSymbol(const HuffTable& t, uint32_t sym, uint16_t& canonicalIndex) {
    uint16_t lo = 0;
    uint16_t hi = t.symbolCount;
    while (lo < hi) {
        const uint16_t mid = static_cast<uint16_t>(lo + (hi - lo) / 2);
        const uint16_t idx = t.sortedIdx[mid];
        const uint32_t v = t.symbols[idx];
        if (v == sym) {
            canonicalIndex = idx;
            return true;
        }
        if (v < sym) {
            lo = static_cast<uint16_t>(mid + 1);
        } else {
            hi = mid;
        }
    }
    return false;
}

bool emit(const HuffTable& t, BitWriter& w, uint16_t idx) {
    return w.put(t.codes[idx], t.lengths[idx]);
}

}  // namespace

const char* codecResultName(CodecResult r) {
    switch (r) {
        case CodecResult::Ok:             return "Ok";
        case CodecResult::OutputTooSmall: return "OutputTooSmall";
        case CodecResult::TruncatedInput: return "TruncatedInput";
        case CodecResult::BadCode:        return "BadCode";
        case CodecResult::BadEscape:      return "BadEscape";
        case CodecResult::InvalidTable:   return "InvalidTable";
    }
    return "Unknown";
}

size_t tableFlashBytes(const HuffTable& t) {
    const size_t n = t.symbolCount;
    const size_t lens = static_cast<size_t>(t.maxLength) + 1;
    return n * sizeof(uint32_t)      // symbols
         + n * sizeof(uint8_t)       // lengths
         + n * sizeof(uint16_t)      // codes
         + n * sizeof(uint16_t)      // sortedIdx
         + lens * sizeof(uint16_t) * 3  // countPerLen, firstCode, firstIndex
         + sizeof(HuffTable);
}

CodecResult encode(const HuffTable& t, const uint32_t* cps, size_t n,
                   uint8_t* out, size_t outCap, size_t& outLen) {
    outLen = 0;
    if (t.symbols == nullptr || t.symbolCount == 0 || t.maxLength == 0) {
        return CodecResult::InvalidTable;
    }
    if (out == nullptr || (cps == nullptr && n > 0)) {
        return CodecResult::InvalidTable;
    }

    uint16_t escapeIdx = 0;
    uint16_t eosIdx = 0;
    if (!findSymbol(t, SYM_ESCAPE, escapeIdx) || !findSymbol(t, SYM_EOS, eosIdx)) {
        // Every table must carry both sentinels or unknown input is unencodable.
        return CodecResult::InvalidTable;
    }

    BitWriter w(out, outCap);
    for (size_t i = 0; i < n; ++i) {
        const uint32_t cp = cps[i];
        if (cp > 0x10FFFF) {
            return CodecResult::BadEscape;
        }
        uint16_t idx = 0;
        if (findSymbol(t, cp, idx)) {
            if (!emit(t, w, idx)) return CodecResult::OutputTooSmall;
        } else {
            if (!emit(t, w, escapeIdx)) return CodecResult::OutputTooSmall;
            if (!w.put(cp, RAW_CODEPOINT_BITS)) return CodecResult::OutputTooSmall;
        }
    }
    if (!emit(t, w, eosIdx)) return CodecResult::OutputTooSmall;

    outLen = w.finish();
    return CodecResult::Ok;
}

CodecResult decode(const HuffTable& t, const uint8_t* in, size_t inLen,
                   uint32_t* out, size_t outCap, size_t& outLen) {
    outLen = 0;
    if (t.symbols == nullptr || t.symbolCount == 0 || t.maxLength == 0) {
        return CodecResult::InvalidTable;
    }
    if (in == nullptr || out == nullptr) {
        return CodecResult::InvalidTable;
    }

    BitReader r(in, inLen);
    for (;;) {
        // Canonical decode: walk bit lengths, comparing against the first code
        // of each length. No tree traversal, two array reads per length.
        uint32_t code = 0;
        uint32_t symbol = 0;
        bool matched = false;
        for (uint8_t len = 1; len <= t.maxLength; ++len) {
            uint8_t bit = 0;
            if (!r.get(bit)) return CodecResult::TruncatedInput;
            code = (code << 1) | bit;
            const uint16_t count = t.countPerLen[len];
            if (count > 0 && code >= t.firstCode[len] &&
                code - t.firstCode[len] < count) {
                symbol = t.symbols[t.firstIndex[len] + (code - t.firstCode[len])];
                matched = true;
                break;
            }
        }
        if (!matched) return CodecResult::BadCode;

        if (symbol == SYM_EOS) {
            return CodecResult::Ok;
        }
        if (symbol == SYM_ESCAPE) {
            uint32_t raw = 0;
            if (!r.get(raw, RAW_CODEPOINT_BITS)) return CodecResult::TruncatedInput;
            if (raw > 0x10FFFF) return CodecResult::BadEscape;
            symbol = raw;
        }
        if (outLen >= outCap) return CodecResult::OutputTooSmall;
        out[outLen++] = symbol;
    }
}

}  // namespace lorax::codec
