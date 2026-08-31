#include "utf8.h"

namespace lorax::text {

size_t utf8Length(uint32_t cp) {
    if (cp < 0x80) return 1;
    if (cp < 0x800) return 2;
    if (cp < 0x10000) return 3;
    return 4;
}

void appendUtf8(std::string& out, uint32_t cp) {
    if (cp < 0x80) {
        out.push_back(static_cast<char>(cp));
    } else if (cp < 0x800) {
        out.push_back(static_cast<char>(0xC0 | (cp >> 6)));
        out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
    } else if (cp < 0x10000) {
        out.push_back(static_cast<char>(0xE0 | (cp >> 12)));
        out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
        out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
    } else {
        out.push_back(static_cast<char>(0xF0 | (cp >> 18)));
        out.push_back(static_cast<char>(0x80 | ((cp >> 12) & 0x3F)));
        out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
        out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
    }
}

std::vector<uint32_t> toCodepoints(const std::string& s) {
    std::vector<uint32_t> out;
    out.reserve(s.size());
    size_t i = 0;
    const size_t n = s.size();
    while (i < n) {
        const uint8_t b0 = static_cast<uint8_t>(s[i]);
        uint32_t cp = REPLACEMENT;
        size_t extra = 0;
        if (b0 < 0x80) {
            cp = b0;
        } else if ((b0 & 0xE0) == 0xC0) {
            cp = b0 & 0x1F;
            extra = 1;
        } else if ((b0 & 0xF0) == 0xE0) {
            cp = b0 & 0x0F;
            extra = 2;
        } else if ((b0 & 0xF8) == 0xF0) {
            cp = b0 & 0x07;
            extra = 3;
        } else {
            out.push_back(REPLACEMENT);
            ++i;
            continue;
        }
        if (i + extra >= n) {
            out.push_back(REPLACEMENT);
            break;
        }
        bool ok = true;
        for (size_t k = 1; k <= extra; ++k) {
            const uint8_t bk = static_cast<uint8_t>(s[i + k]);
            if ((bk & 0xC0) != 0x80) {
                ok = false;
                break;
            }
            cp = (cp << 6) | (bk & 0x3F);
        }
        out.push_back(ok ? cp : REPLACEMENT);
        i += extra + 1;
    }
    return out;
}

std::string fromCodepoints(const std::vector<uint32_t>& cps) {
    std::string out;
    for (uint32_t cp : cps) appendUtf8(out, cp);
    return out;
}

bool isCombining(uint32_t cp) {
    if (cp >= 0x0300 && cp <= 0x036F) return true;   // generic diacriticals
    if (cp == 0x200D) return true;                   // ZWJ joins within a cluster
    // Devanagari
    if ((cp >= 0x0900 && cp <= 0x0903) || (cp >= 0x093A && cp <= 0x093C) ||
        (cp >= 0x093E && cp <= 0x094F) || (cp >= 0x0951 && cp <= 0x0957) ||
        (cp >= 0x0962 && cp <= 0x0963)) return true;
    // Bengali
    if ((cp >= 0x0981 && cp <= 0x0983) || cp == 0x09BC ||
        (cp >= 0x09BE && cp <= 0x09C4) || (cp >= 0x09C7 && cp <= 0x09C8) ||
        (cp >= 0x09CB && cp <= 0x09CD) || cp == 0x09D7 ||
        (cp >= 0x09E2 && cp <= 0x09E3)) return true;
    // Gujarati
    if ((cp >= 0x0A81 && cp <= 0x0A83) || cp == 0x0ABC ||
        (cp >= 0x0ABE && cp <= 0x0AC5) || (cp >= 0x0AC7 && cp <= 0x0AC9) ||
        (cp >= 0x0ACB && cp <= 0x0ACD) || (cp >= 0x0AE2 && cp <= 0x0AE3)) return true;
    // Tamil
    if (cp == 0x0B82 || (cp >= 0x0BBE && cp <= 0x0BC2) ||
        (cp >= 0x0BC6 && cp <= 0x0BC8) || (cp >= 0x0BCA && cp <= 0x0BCD) ||
        cp == 0x0BD7) return true;
    // Kannada
    if ((cp >= 0x0C81 && cp <= 0x0C83) || cp == 0x0CBC ||
        (cp >= 0x0CBE && cp <= 0x0CC4) || (cp >= 0x0CC6 && cp <= 0x0CC8) ||
        (cp >= 0x0CCA && cp <= 0x0CCD) || (cp >= 0x0CD5 && cp <= 0x0CD6) ||
        (cp >= 0x0CE2 && cp <= 0x0CE3)) return true;
    return false;
}

std::vector<std::vector<uint32_t>> toGraphemeClusters(const std::vector<uint32_t>& cps) {
    std::vector<std::vector<uint32_t>> out;
    for (size_t i = 0; i < cps.size();) {
        std::vector<uint32_t> cluster{cps[i]};
        size_t j = i + 1;
        while (j < cps.size() && isCombining(cps[j])) {
            cluster.push_back(cps[j]);
            ++j;
        }
        out.push_back(std::move(cluster));
        i = j;
    }
    return out;
}

}  // namespace lorax::text
