// Range-test log format.
//
// The log is the evidence for the presentation, so these tests treat "pastes
// cleanly into a spreadsheet" as the requirement it is: constant column count,
// correct RFC 4180 quoting, and no fake zeros in columns that will be averaged.

#include <unity.h>

#include <cstring>
#include <string>

#include "packet_log.h"
#include "rate_control.h"

using namespace lorax;

void setUp() {}
void tearDown() {}

// Counts CSV fields the way a spreadsheet does: commas inside quotes are data,
// not separators.
static size_t countFields(const char* row) {
    size_t fields = 1;
    bool inQuotes = false;
    for (const char* p = row; *p != '\0'; ++p) {
        if (*p == '"') {
            if (inQuotes && *(p + 1) == '"') { ++p; continue; }  // escaped quote
            inQuotes = !inQuotes;
        } else if (*p == ',' && !inQuotes) {
            ++fields;
        }
    }
    return fields;
}

static LogEvent sampleRx() {
    LogEvent e;
    e.timestampMs  = 123456;
    e.direction    = LogDirection::Rx;
    e.rung         = Rung::Far;
    e.result       = LogResult::Ok;
    e.rssiDbm      = -97.5f;
    e.snrDb        = -8.25f;
    e.peerSnrDb    = -6.0f;
    e.payloadBytes = 168;
    e.airtimeMs    = 1640.0f;
    e.fragIndex    = 1;
    e.fragCount    = 2;
    e.msgId        = 42;
    e.compressed   = true;
    return e;
}

static void test_header_has_the_declared_column_count(void) {
    char h[LOG_LINE_MAX];
    TEST_ASSERT_GREATER_THAN_UINT(0, formatCsvHeader(h, sizeof(h)));
    TEST_ASSERT_EQUAL_UINT(LOG_COLUMN_COUNT, countFields(h));
}

// A header that drifts from its rows silently misaligns every column.
static void test_rows_match_the_header_width(void) {
    char h[LOG_LINE_MAX];
    char r[LOG_LINE_MAX];
    formatCsvHeader(h, sizeof(h));
    formatCsvRow(sampleRx(), r, sizeof(r));
    TEST_ASSERT_EQUAL_UINT(countFields(h), countFields(r));
}

static void test_tx_rx_and_mark_rows_are_all_the_same_width(void) {
    char buf[LOG_LINE_MAX];

    LogEvent rx = sampleRx();
    formatCsvRow(rx, buf, sizeof(buf));
    TEST_ASSERT_EQUAL_UINT(LOG_COLUMN_COUNT, countFields(buf));

    LogEvent tx = sampleRx();
    tx.direction = LogDirection::Tx;
    tx.hasRadioInfo = false;
    formatCsvRow(tx, buf, sizeof(buf));
    TEST_ASSERT_EQUAL_UINT(LOG_COLUMN_COUNT, countFields(buf));

    LogEvent mark;
    mark.direction = LogDirection::Mark;
    mark.note = "500m line of sight";
    formatCsvRow(mark, buf, sizeof(buf));
    TEST_ASSERT_EQUAL_UINT(LOG_COLUMN_COUNT, countFields(buf));
}

// "log mark 500m, line of sight" is the natural thing to type. If the comma
// leaked through it would shift every column to its right by one.
static void test_marker_with_commas_does_not_shift_columns(void) {
    char buf[LOG_LINE_MAX];
    LogEvent mark;
    mark.direction = LogDirection::Mark;
    mark.note = "500m, line of sight, behind the hedge";
    TEST_ASSERT_GREATER_THAN_UINT(0, formatCsvRow(mark, buf, sizeof(buf)));
    TEST_ASSERT_EQUAL_UINT(LOG_COLUMN_COUNT, countFields(buf));
    TEST_ASSERT_NOT_NULL(std::strstr(buf, "\"500m, line of sight, behind the hedge\""));
}

static void test_marker_quotes_are_escaped(void) {
    char field[64];
    TEST_ASSERT_GREATER_THAN_UINT(0, writeCsvField("the \"far\" corner", field,
                                                   sizeof(field)));
    TEST_ASSERT_EQUAL_STRING("\"the \"\"far\"\" corner\"", field);

    char buf[LOG_LINE_MAX];
    LogEvent mark;
    mark.direction = LogDirection::Mark;
    mark.note = "the \"far\" corner";
    formatCsvRow(mark, buf, sizeof(buf));
    TEST_ASSERT_EQUAL_UINT(LOG_COLUMN_COUNT, countFields(buf));
}

static void test_plain_fields_are_not_quoted(void) {
    char field[64];
    writeCsvField("500m LOS", field, sizeof(field));
    TEST_ASSERT_EQUAL_STRING("500m LOS", field);
    writeCsvField("", field, sizeof(field));
    TEST_ASSERT_EQUAL_STRING("", field);
    writeCsvField(nullptr, field, sizeof(field));
    TEST_ASSERT_EQUAL_STRING("", field);
}

// Our own transmissions have no RSSI/SNR. Emitting 0 would poison any average
// taken over the column; the field must be genuinely empty.
static void test_tx_rows_leave_radio_columns_empty(void) {
    char buf[LOG_LINE_MAX];
    LogEvent tx = sampleRx();
    tx.direction = LogDirection::Tx;
    tx.hasRadioInfo = false;
    formatCsvRow(tx, buf, sizeof(buf));
    TEST_ASSERT_NULL(std::strstr(buf, "-97.5"));
    TEST_ASSERT_NOT_NULL(std::strstr(buf, ",tx,"));
    // and the peer SNR, which we do know, is still present
    TEST_ASSERT_NOT_NULL(std::strstr(buf, "-6.0"));
}

static void test_mark_rows_leave_numeric_columns_empty(void) {
    char buf[LOG_LINE_MAX];
    LogEvent mark;
    mark.timestampMs = 9999;
    mark.direction = LogDirection::Mark;
    mark.rung = Rung::Medium;
    mark.note = "start";
    formatCsvRow(mark, buf, sizeof(buf));
    TEST_ASSERT_NOT_NULL(std::strstr(buf, "9999,mark,MEDIUM,9,125000,"));
    // No stray zeros between the rung columns and the note.
    TEST_ASSERT_NOT_NULL(std::strstr(buf, ",,,,,,,,,,,,start"));
}

static void test_row_contents_are_readable(void) {
    char buf[LOG_LINE_MAX];
    formatCsvRow(sampleRx(), buf, sizeof(buf));
    const std::string row(buf);
    TEST_ASSERT_TRUE(row.find("123456,rx,FAR,10,125000,ok,") == 0);
    TEST_ASSERT_TRUE(row.find("-97.5") != std::string::npos);
    TEST_ASSERT_TRUE(row.find(",168,") != std::string::npos);
    TEST_ASSERT_TRUE(row.find(",y,") != std::string::npos);   // compressed
}

static void test_all_results_and_rungs_render(void) {
    char buf[LOG_LINE_MAX];
    const LogResult results[] = {LogResult::Ok, LogResult::CrcFail,
                                 LogResult::RadioError, LogResult::Timeout,
                                 LogResult::Refused, LogResult::Dropped};
    for (LogResult r : results) {
        for (uint8_t i = 0; i < RUNG_COUNT; ++i) {
            LogEvent e = sampleRx();
            e.result = r;
            e.rung = static_cast<Rung>(i);
            TEST_ASSERT_GREATER_THAN_UINT(0, formatCsvRow(e, buf, sizeof(buf)));
            TEST_ASSERT_EQUAL_UINT(LOG_COLUMN_COUNT, countFields(buf));
            TEST_ASSERT_GREATER_THAN_UINT(0, formatHuman(e, buf, sizeof(buf)));
        }
    }
}

// A truncated row would corrupt the sheet silently; refusing is the only safe
// failure.
static void test_undersized_buffers_refuse_rather_than_truncate(void) {
    char tiny[16];
    TEST_ASSERT_EQUAL_UINT(0, formatCsvRow(sampleRx(), tiny, sizeof(tiny)));
    TEST_ASSERT_EQUAL_UINT(0, formatCsvHeader(tiny, sizeof(tiny)));
}

static void test_line_buffer_is_big_enough_for_the_worst_row(void) {
    char buf[LOG_LINE_MAX];
    LogEvent worst;
    worst.timestampMs = 4294967295u;
    worst.direction = LogDirection::Rx;
    worst.rung = Rung::Max;
    worst.result = LogResult::RadioError;
    worst.rssiDbm = -137.5f;
    worst.snrDb = -20.25f;
    worst.peerSnrDb = -20.25f;
    worst.payloadBytes = 255;
    worst.airtimeMs = 9019.0f;
    worst.fragIndex = 15;
    worst.fragCount = 16;
    worst.msgId = 255;
    worst.retries = 255;
    worst.note = "deserialize rejected: BadFragmentation";
    TEST_ASSERT_GREATER_THAN_UINT(0, formatCsvRow(worst, buf, sizeof(buf)));
    TEST_ASSERT_EQUAL_UINT(LOG_COLUMN_COUNT, countFields(buf));
}

int main(void) {
    UNITY_BEGIN();
    RUN_TEST(test_header_has_the_declared_column_count);
    RUN_TEST(test_rows_match_the_header_width);
    RUN_TEST(test_tx_rx_and_mark_rows_are_all_the_same_width);
    RUN_TEST(test_marker_with_commas_does_not_shift_columns);
    RUN_TEST(test_marker_quotes_are_escaped);
    RUN_TEST(test_plain_fields_are_not_quoted);
    RUN_TEST(test_tx_rows_leave_radio_columns_empty);
    RUN_TEST(test_mark_rows_leave_numeric_columns_empty);
    RUN_TEST(test_row_contents_are_readable);
    RUN_TEST(test_all_results_and_rungs_render);
    RUN_TEST(test_undersized_buffers_refuse_rather_than_truncate);
    RUN_TEST(test_line_buffer_is_big_enough_for_the_worst_row);
    return UNITY_END();
}
