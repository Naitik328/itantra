// Range-test packet log.
//
// This log IS the evidence for the final presentation, so its usability is a
// requirement rather than a debug convenience: one row per packet event, a
// constant column count, RFC 4180 quoting, and a header line that makes the
// whole stream paste into a spreadsheet with no cleanup.
//
// Pure formatting - no I/O, no Arduino. The caller owns the buffer and does the
// printing, which is what lets the row format be tested natively.
//
// Marker rows share the same columns as packet rows (direction=mark, note
// filled) rather than being free text. A stream whose column count changes
// mid-file does not paste into a sheet, and the markers are exactly what
// correlates distance to packets afterwards - they cannot be the part that
// breaks the import.

#pragma once

#include <cstddef>
#include <cstdint>

#include "rate_control.h"

namespace lorax {

enum class LogMode : uint8_t {
    Off = 0,
    Human,  // readable while walking with a phone terminal
    Csv,    // machine-readable; emits a header line when enabled
};

enum class LogDirection : uint8_t { Tx, Rx, Mark };

enum class LogResult : uint8_t {
    Ok = 0,
    CrcFail,      // our packet CRC rejected it
    RadioError,   // SX1262 hardware CRC / header failure - never reached us
    Timeout,      // expected response never arrived
    Refused,      // airtime guard or radio refused the transmit
    Dropped,      // decoded but rejected (wrong network, bad fragmentation...)
};

const char* logResultName(LogResult r);
const char* logDirectionName(LogDirection d);

struct LogEvent {
    uint32_t     timestampMs  = 0;
    LogDirection direction    = LogDirection::Rx;
    Rung         rung         = Rung::Far;
    LogResult    result       = LogResult::Ok;
    float        rssiDbm      = 0.0f;
    float        snrDb        = 0.0f;
    float        peerSnrDb    = 0.0f;
    uint16_t     payloadBytes = 0;
    float        airtimeMs    = 0.0f;
    uint8_t      fragIndex    = 0;
    uint8_t      fragCount    = 0;
    uint8_t      msgId        = 0;
    uint8_t      retries      = 0;   // always 0 until ARQ exists
    bool         compressed   = false;
    bool         hasRadioInfo = true;  // false for tx rows: no RSSI/SNR yet
    const char*  note         = nullptr;
};

// 17 columns. Keep formatCsvHeader and formatCsvRow in step - the test asserts
// they agree, because a header that drifts from its rows silently misaligns
// every column in the spreadsheet.
constexpr size_t LOG_COLUMN_COUNT = 17;
constexpr size_t LOG_LINE_MAX     = 220;

size_t formatCsvHeader(char* out, size_t cap);
size_t formatCsvRow(const LogEvent& e, char* out, size_t cap);
size_t formatHuman(const LogEvent& e, char* out, size_t cap);

// RFC 4180: quote when the field contains a comma, quote or newline, and double
// any embedded quotes. "log mark 500m, line of sight" must not shift columns.
size_t writeCsvField(const char* text, char* out, size_t cap);

}  // namespace lorax
