// Canonical Huffman codec — TRAINING half. Tools only, never compiled into
// firmware: it allocates freely and knows nothing the runtime needs.

#pragma once

#include <cstdint>
#include <map>
#include <string>
#include <vector>

#include "huffman_codec.h"

namespace lorax::codec::train {

struct TrainedTable {
    std::vector<uint32_t> symbols;      // canonical order (length, then value)
    std::vector<uint8_t>  lengths;
    std::vector<uint16_t> codes;
    std::vector<uint16_t> sortedIdx;    // into symbols[], ascending by value
    std::vector<uint16_t> countPerLen;  // [maxLength + 1]
    std::vector<uint16_t> firstCode;
    std::vector<uint16_t> firstIndex;
    uint8_t maxLength = 0;
    bool    lengthLimitApplied = false;  // true if any code had to be shortened

    HuffTable view() const;
    size_t    flashBytes() const;
};

// Builds a table from codepoint frequencies. SYM_ESCAPE and SYM_EOS are always
// added, so the result can encode any Unicode input and is self-terminating.
TrainedTable train(const std::map<uint32_t, uint64_t>& freqs);

// Emits the table as a C++ definition suitable for flash storage.
std::string emitCpp(const TrainedTable& t, const std::string& name);

}  // namespace lorax::codec::train
