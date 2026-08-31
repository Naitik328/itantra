// CRC-16/CCITT-FALSE  (poly 0x1021, init 0xFFFF, no reflection, no final XOR)
//
// Why this variant:
//   * Hamming distance 4 at our frame sizes (<= 255 B = 2040 bits), so every
//     1-, 2- and 3-bit error is caught with certainty.
//   * 2 bytes instead of CRC-32's 4. At these lengths CRC-32 also only gives
//     HD=4, so the extra 2 bytes per *fragment* would buy a better random
//     residual (2^-32 vs 2^-16) but no stronger guarantee. See CLAUDE.md.
//   * Published check value lets us prove the implementation is correct:
//     crc16("123456789") == 0x29B1.
//
// Bitwise, not table-driven: a table costs 512 B of flash to save ~2000 shift
// operations per packet. At 240 MHz and a handful of packets per minute that
// trade is not worth making.

#pragma once

#include <cstddef>
#include <cstdint>

namespace lorax {

constexpr uint16_t CRC16_INIT = 0xFFFF;

// Feed more bytes into a running CRC. Start from CRC16_INIT.
uint16_t crc16Update(uint16_t crc, const uint8_t* data, size_t len);

// One-shot helper for a single contiguous buffer.
uint16_t crc16(const uint8_t* data, size_t len);

}  // namespace lorax
