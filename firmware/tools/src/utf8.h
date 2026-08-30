// UTF-8 / Unicode helpers for corpus analysis and the codec.
//
// Plain C++17, no dependencies. The codepoint routines are firmware-safe; the
// grapheme-cluster routine is analysis-only (it carries a table of Indic
// combining ranges that the runtime codec does not need).

#pragma once

#include <cstdint>
#include <string>
#include <vector>

namespace lorax::text {

constexpr uint32_t REPLACEMENT = 0xFFFD;

// Decodes UTF-8 to codepoints. Malformed bytes become REPLACEMENT rather than
// throwing - the corpus is trusted, but a codec must never crash on bad input.
std::vector<uint32_t> toCodepoints(const std::string& s);

// Appends one codepoint as UTF-8.
void appendUtf8(std::string& out, uint32_t cp);

std::string fromCodepoints(const std::vector<uint32_t>& cps);

// Bytes this codepoint occupies in UTF-8.
size_t utf8Length(uint32_t cp);

// True for combining marks in the six scripts this project uses (matras,
// viramas, anusvara/visarga, nuktas) plus generic combining diacriticals and
// ZWJ. Approximates Unicode categories Mn/Mc for these blocks; it is not a full
// UAX #29 implementation and does not need to be.
bool isCombining(uint32_t cp);

// Splits into extended grapheme clusters: a base codepoint plus any combining
// marks that follow it. Does NOT join consonants across a virama - that would
// be akshara (orthographic syllable) segmentation, a coarser and much larger
// alphabet. See the analysis output for why that distinction matters.
std::vector<std::vector<uint32_t>> toGraphemeClusters(const std::vector<uint32_t>& cps);

}  // namespace lorax::text
