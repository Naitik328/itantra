#include "packet_log.h"

#include <cstdio>
#include <cstring>

namespace lorax {

const char* logResultName(LogResult r) {
    switch (r) {
        case LogResult::Ok:         return "ok";
        case LogResult::CrcFail:    return "crc_fail";
        case LogResult::RadioError: return "radio_err";
        case LogResult::Timeout:    return "timeout";
        case LogResult::Refused:    return "refused";
        case LogResult::Dropped:    return "dropped";
    }
    return "unknown";
}

const char* logDirectionName(LogDirection d) {
    switch (d) {
        case LogDirection::Tx:   return "tx";
        case LogDirection::Rx:   return "rx";
        case LogDirection::Mark: return "mark";
    }
    return "?";
}

size_t writeCsvField(const char* text, char* out, size_t cap) {
    if (out == nullptr || cap == 0) return 0;
    if (text == nullptr || text[0] == '\0') {
        out[0] = '\0';
        return 0;
    }
    bool needsQuote = false;
    for (const char* p = text; *p != '\0'; ++p) {
        if (*p == ',' || *p == '"' || *p == '\n' || *p == '\r') {
            needsQuote = true;
            break;
        }
    }
    size_t n = 0;
    if (!needsQuote) {
        while (text[n] != '\0' && n + 1 < cap) {
            out[n] = text[n];
            ++n;
        }
        out[n] = '\0';
        return n;
    }
    if (cap < 3) {
        out[0] = '\0';
        return 0;
    }
    out[n++] = '"';
    for (const char* p = text; *p != '\0' && n + 2 < cap; ++p) {
        if (*p == '"') {
            if (n + 3 >= cap) break;
            out[n++] = '"';
        }
        out[n++] = *p;
    }
    out[n++] = '"';
    out[n] = '\0';
    return n;
}

size_t formatCsvHeader(char* out, size_t cap) {
    const int n = std::snprintf(
        out, cap,
        "timestamp_ms,direction,rung,sf,bw_hz,result,rssi_dbm,snr_db,peer_snr_db,"
        "payload_bytes,airtime_ms,frag_index,frag_count,msg_id,retries,compressed,note");
    return (n < 0 || static_cast<size_t>(n) >= cap) ? 0 : static_cast<size_t>(n);
}

size_t formatCsvRow(const LogEvent& e, char* out, size_t cap) {
    char note[80];
    writeCsvField(e.note, note, sizeof(note));
    const RungConfig& c = rungConfig(e.rung);

    // Marker rows carry only a timestamp, the rung in force, and the note. The
    // numeric columns are left EMPTY rather than zero, so a spreadsheet does not
    // average a fake -0 dBm into the RSSI column.
    if (e.direction == LogDirection::Mark) {
        const int n = std::snprintf(out, cap, "%lu,mark,%s,%u,%lu,,,,,,,,,,,,%s",
                                    static_cast<unsigned long>(e.timestampMs),
                                    rungName(e.rung), static_cast<unsigned>(c.sf),
                                    static_cast<unsigned long>(c.bandwidthHz), note);
        return (n < 0 || static_cast<size_t>(n) >= cap) ? 0 : static_cast<size_t>(n);
    }

    char rssi[12] = "";
    char snr[12] = "";
    char peer[12] = "";
    if (e.hasRadioInfo) {
        std::snprintf(rssi, sizeof(rssi), "%.1f", static_cast<double>(e.rssiDbm));
        std::snprintf(snr, sizeof(snr), "%.1f", static_cast<double>(e.snrDb));
    }
    std::snprintf(peer, sizeof(peer), "%.1f", static_cast<double>(e.peerSnrDb));

    const int n = std::snprintf(
        out, cap, "%lu,%s,%s,%u,%lu,%s,%s,%s,%s,%u,%.1f,%u,%u,%u,%u,%c,%s",
        static_cast<unsigned long>(e.timestampMs), logDirectionName(e.direction),
        rungName(e.rung), static_cast<unsigned>(c.sf),
        static_cast<unsigned long>(c.bandwidthHz), logResultName(e.result), rssi,
        snr, peer, static_cast<unsigned>(e.payloadBytes),
        static_cast<double>(e.airtimeMs), static_cast<unsigned>(e.fragIndex),
        static_cast<unsigned>(e.fragCount), static_cast<unsigned>(e.msgId),
        static_cast<unsigned>(e.retries), e.compressed ? 'y' : 'n', note);
    return (n < 0 || static_cast<size_t>(n) >= cap) ? 0 : static_cast<size_t>(n);
}

size_t formatHuman(const LogEvent& e, char* out, size_t cap) {
    if (e.direction == LogDirection::Mark) {
        const int n = std::snprintf(out, cap, "[mark %lu ms @%s] %s",
                                    static_cast<unsigned long>(e.timestampMs),
                                    rungName(e.rung), e.note ? e.note : "");
        return (n < 0 || static_cast<size_t>(n) >= cap) ? 0 : static_cast<size_t>(n);
    }
    char radio[48] = "";
    if (e.hasRadioInfo) {
        std::snprintf(radio, sizeof(radio), " RSSI %.0f SNR %.1f",
                      static_cast<double>(e.rssiDbm), static_cast<double>(e.snrDb));
    }
    const int n = std::snprintf(
        out, cap, "[%s %-6s] %-6s %3u B  frag %u/%u  msg %3u  %.0f ms%s%s",
        logDirectionName(e.direction), logResultName(e.result), rungName(e.rung),
        static_cast<unsigned>(e.payloadBytes),
        static_cast<unsigned>(e.fragIndex + 1), static_cast<unsigned>(e.fragCount),
        static_cast<unsigned>(e.msgId), static_cast<double>(e.airtimeMs), radio,
        e.compressed ? " [z]" : "");
    return (n < 0 || static_cast<size_t>(n) >= cap) ? 0 : static_cast<size_t>(n);
}

}  // namespace lorax
