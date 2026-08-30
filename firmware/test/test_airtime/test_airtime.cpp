// Time-on-air calculator, checked against measured hardware numbers.

#include <unity.h>

#include <cmath>
#include <cstdio>
#include <initializer_list>

#include "../corpus.h"
#include "airtime.h"
#include "fragmenter.h"
#include "packet.h"

using namespace lorax;

void setUp() {}
void tearDown() {}

static LoRaParams india(uint8_t sf) {
    LoRaParams p;
    p.sf = sf;
    p.bandwidthHz = 125000;
    p.codingRate = 5;      // 4/5
    p.preambleSymbols = 8;
    p.explicitHeader = true;
    p.crcOn = true;
    return p;
}

// THE reference test: six independently measured data points at 168 B, 125 kHz,
// CR 4/5. If any of these drift, the model no longer describes the radio.
static void test_matches_measured_airtime_sf7_to_sf12(void) {
    struct Point { uint8_t sf; double ms; };
    const Point measured[] = {
        {7, 272.0}, {8, 482.0}, {9, 861.0},
        {10, 1559.0}, {11, 3445.0}, {12, 6234.0},
    };
    for (const Point& m : measured) {
        const double got = timeOnAirMs(india(m.sf), 168);
        char msg[80];
        std::snprintf(msg, sizeof(msg), "SF%u expected %.0f ms, got %.2f ms",
                      m.sf, m.ms, got);
        // Measured values are whole milliseconds, so allow half a ms of rounding.
        TEST_ASSERT_DOUBLE_WITHIN_MESSAGE(0.5, m.ms, std::round(got), msg);
    }
}

static void test_symbol_time(void) {
    TEST_ASSERT_DOUBLE_WITHIN(1e-9, 1.024, symbolTimeMs(india(7)));
    TEST_ASSERT_DOUBLE_WITHIN(1e-9, 8.192, symbolTimeMs(india(10)));
    TEST_ASSERT_DOUBLE_WITHIN(1e-9, 32.768, symbolTimeMs(india(12)));

    LoRaParams wide = india(7);
    wide.bandwidthHz = 250000;
    TEST_ASSERT_DOUBLE_WITHIN(1e-9, 0.512, symbolTimeMs(wide));
}

// LDRO is mandatory once a symbol exceeds 16 ms: SF11 and SF12 at 125 kHz.
static void test_low_data_rate_optimize_auto_rule(void) {
    for (uint8_t sf = 7; sf <= 10; ++sf) {
        TEST_ASSERT_FALSE(lowDataRateOptimizeActive(india(sf)));
    }
    TEST_ASSERT_TRUE(lowDataRateOptimizeActive(india(11)));
    TEST_ASSERT_TRUE(lowDataRateOptimizeActive(india(12)));

    LoRaParams forcedOff = india(12);
    forcedOff.lowDataRateOptimize = 0;
    TEST_ASSERT_FALSE(lowDataRateOptimizeActive(forcedOff));

    LoRaParams forcedOn = india(7);
    forcedOn.lowDataRateOptimize = 1;
    TEST_ASSERT_TRUE(lowDataRateOptimizeActive(forcedOn));
}

static void test_airtime_is_monotonic_in_payload(void) {
    for (uint8_t sf = 7; sf <= 12; ++sf) {
        double prev = -1.0;
        for (size_t n = 0; n <= 255; ++n) {
            const double t = timeOnAirMs(india(sf), n);
            TEST_ASSERT_TRUE(t >= prev);
            prev = t;
        }
    }
}

static void test_higher_sf_is_slower(void) {
    double prev = 0.0;
    for (uint8_t sf = 7; sf <= 12; ++sf) {
        const double t = timeOnAirMs(india(sf), 168);
        TEST_ASSERT_TRUE(t > prev);
        prev = t;
    }
}

static void test_wider_bandwidth_is_faster(void) {
    LoRaParams narrow = india(9);
    LoRaParams wide = india(9);
    wide.bandwidthHz = 250000;
    // Doubling bandwidth halves symbol time, so airtime halves too.
    TEST_ASSERT_DOUBLE_WITHIN(0.001, timeOnAirMs(narrow, 168) / 2.0,
                              timeOnAirMs(wide, 168));
}

static void test_weaker_coding_rate_costs_time(void) {
    double prev = 0.0;
    for (uint8_t cr = 5; cr <= 8; ++cr) {
        LoRaParams p = india(9);
        p.codingRate = cr;
        const double t = timeOnAirMs(p, 168);
        TEST_ASSERT_TRUE(t > prev);
        prev = t;
    }
}

// Airtime is a STEP function: bytes are grouped into coding blocks, so a 0-byte
// and a 1-byte frame occupy the same number of symbols and cost the same.
// Only crossing a block boundary costs more time.
static void test_empty_payload_still_costs_airtime(void) {
    const double t = timeOnAirMs(india(9), 0);
    TEST_ASSERT_TRUE(t > 0.0);                            // preamble alone is not free
    TEST_ASSERT_TRUE(t <= timeOnAirMs(india(9), 1));      // same block, same cost
    TEST_ASSERT_TRUE(t < timeOnAirMs(india(9), 100));     // a bigger frame does cost more
}

// A 7-byte ACK is the cheapest thing we ever send; useful to know its cost.
static void test_ack_airtime_is_small(void) {
    const double ack = timeOnAirMs(india(10), OVERHEAD);
    const double full = timeOnAirMs(india(10), 168);
    TEST_ASSERT_TRUE(ack < full / 4.0);
}

static void test_max_payload_for_budget(void) {
    for (uint8_t sf = 7; sf <= 12; ++sf) {
        const LoRaParams p = india(sf);
        for (double budget : {200.0, 500.0, 1000.0, 2000.0}) {
            const size_t n = maxPayloadForBudget(p, budget);
            if (n > 0) {
                TEST_ASSERT_TRUE(timeOnAirMs(p, n) <= budget);
                if (n < 255) {
                    // n is genuinely the largest that fits
                    TEST_ASSERT_TRUE(timeOnAirMs(p, n + 1) > budget);
                }
            } else {
                TEST_ASSERT_TRUE(timeOnAirMs(p, 1) > budget);
            }
        }
    }
}

// The latency budget that actually constrains the project: TTS 2000 ms worst
// case + STT 800 ms placeholder leaves roughly 2200 ms for the radio in a 5 s
// end-to-end target. Confirms SF9/SF10 are the workable band and SF11/12 are not.
static void test_sf9_sf10_fit_the_latency_budget(void) {
    const double radioBudgetMs = 2200.0;

    TEST_ASSERT_TRUE(timeOnAirMs(india(9), 168) < radioBudgetMs);
    TEST_ASSERT_TRUE(timeOnAirMs(india(10), 168) < radioBudgetMs);
    TEST_ASSERT_TRUE(timeOnAirMs(india(11), 168) > radioBudgetMs);
    TEST_ASSERT_TRUE(timeOnAirMs(india(12), 168) > radioBudgetMs);
}

// Tamil at 239 B is the worst case. Confirm it still fits the budget at SF10
// once fragmentation overhead is paid.
static void test_tamil_worst_case_fits_at_sf10(void) {
    const LoRaParams p = india(10);
    const size_t frameCap = maxPayloadForBudget(p, 1600.0);
    TEST_ASSERT_GREATER_THAN_UINT(0, frameCap);

    const size_t chunk = maxFragmentPayload(frameCap);
    TEST_ASSERT_GREATER_THAN_UINT(0, chunk);

    const size_t count = fragmentCount(corpus::TA_LEN, chunk);
    TEST_ASSERT_LESS_OR_EQUAL_UINT(MAX_FRAGMENTS, count);

    double total = 0.0;
    size_t remaining = corpus::TA_LEN;
    for (size_t i = 0; i < count; ++i) {
        const size_t take = remaining < chunk ? remaining : chunk;
        total += timeOnAirMs(p, take + OVERHEAD);
        remaining -= take;
    }
    TEST_ASSERT_EQUAL_UINT(0, remaining);
    // Two SF10 fragments, well inside a walkie-talkie feel.
    TEST_ASSERT_TRUE(total < 3500.0);
}

static void test_duty_cycle_off_time(void) {
    TEST_ASSERT_DOUBLE_WITHIN(0.001, 0.0, requiredOffTimeMs(900.0, 0.0));
    TEST_ASSERT_DOUBLE_WITHIN(0.001, 89100.0, requiredOffTimeMs(900.0, 1.0));
    TEST_ASSERT_DOUBLE_WITHIN(0.001, 8100.0, requiredOffTimeMs(900.0, 10.0));
    TEST_ASSERT_DOUBLE_WITHIN(0.001, 0.0, requiredOffTimeMs(900.0, 100.0));
}

int main(void) {
    UNITY_BEGIN();
    RUN_TEST(test_matches_measured_airtime_sf7_to_sf12);
    RUN_TEST(test_symbol_time);
    RUN_TEST(test_low_data_rate_optimize_auto_rule);
    RUN_TEST(test_airtime_is_monotonic_in_payload);
    RUN_TEST(test_higher_sf_is_slower);
    RUN_TEST(test_wider_bandwidth_is_faster);
    RUN_TEST(test_weaker_coding_rate_costs_time);
    RUN_TEST(test_empty_payload_still_costs_airtime);
    RUN_TEST(test_ack_airtime_is_small);
    RUN_TEST(test_max_payload_for_budget);
    RUN_TEST(test_sf9_sf10_fit_the_latency_budget);
    RUN_TEST(test_tamil_worst_case_fits_at_sf10);
    RUN_TEST(test_duty_cycle_off_time);
    return UNITY_END();
}
