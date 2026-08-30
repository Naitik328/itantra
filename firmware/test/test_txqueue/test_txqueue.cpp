// Transmit queue and ALERT priority.
//
// The defect these exist to prevent: an ALERT raised while a NORMAL message was
// transmitting used to be REFUSED and dropped by the BLE adapter. Silent loss,
// before the radio was even involved, so none of the CRC machinery could catch
// it. That is the regression that must never come back.

#include <unity.h>

#include <cstdio>
#include <cstring>
#include <string>

#include "../corpus.h"
#include "fragmenter.h"
#include "packet.h"
#include "tx_queue.h"

using namespace lorax;

static TxQueue g_tx;   // ~8 KB: static, never on a stack

void setUp() { g_tx.reset(); }
void tearDown() {}

// Builds `count` fragments carrying a recognisable body.
static uint8_t makeFragments(Packet* out, const char* body, size_t len,
                             size_t chunk, uint8_t msgId, bool alert) {
    FragmentOptions opt;
    opt.msgId  = msgId;
    opt.langId = 4;
    opt.flags  = alert ? FLAG_ALERT : 0;
    size_t count = 0;
    const FragmentResult r =
        fragment(reinterpret_cast<const uint8_t*>(body), len, chunk, opt, out,
                 MAX_FRAGMENTS, count);
    TEST_ASSERT_EQUAL(FragmentResult::Ok, r);
    return static_cast<uint8_t>(count);
}

// ===========================================================================
// THE regression test.
// ===========================================================================
static void test_alert_during_in_flight_normal_is_delivered_not_dropped(void) {
    Packet normal[MAX_FRAGMENTS];
    Packet alert[MAX_FRAGMENTS];
    const uint8_t nCount = makeFragments(normal, corpus::TA, corpus::TA_LEN, 120, 1, false);
    const uint8_t aCount = makeFragments(alert, corpus::EN, corpus::EN_LEN, 120, 2, true);
    TEST_ASSERT_EQUAL_UINT8(2, nCount);   // a genuinely multi-fragment message
    TEST_ASSERT_EQUAL_UINT8(1, aCount);

    // The normal message starts transmitting.
    TEST_ASSERT_EQUAL(TxQueue::AdmitResult::Accepted, g_tx.admit(normal, nCount, false));
    const Packet* first = g_tx.peek();
    TEST_ASSERT_NOT_NULL(first);
    TEST_ASSERT_EQUAL_UINT8(1, first->msgId);
    TEST_ASSERT_EQUAL_UINT8(0, first->fragIndex);
    g_tx.advance();                       // fragment 0 is on the air
    TEST_ASSERT_TRUE(g_tx.inFlight());

    // The ALERT arrives mid-message. It MUST be accepted.
    const TxQueue::AdmitResult ar = g_tx.admit(alert, aCount, true);
    TEST_ASSERT_NOT_EQUAL_MESSAGE(TxQueue::AdmitResult::Full, ar,
                                  "ALERT was refused during an in-flight message");
    TEST_ASSERT_TRUE(g_tx.alertPending());

    // ...and it goes out at the very next fragment boundary, ahead of the
    // normal message's remaining fragment.
    const Packet* next = g_tx.peek();
    TEST_ASSERT_NOT_NULL(next);
    TEST_ASSERT_EQUAL_UINT8_MESSAGE(2, next->msgId, "ALERT did not preempt");
    TEST_ASSERT_TRUE(next->isAlert());
    g_tx.advance();

    // The normal message then RESUMES - nothing was destroyed.
    const Packet* resumed = g_tx.peek();
    TEST_ASSERT_NOT_NULL_MESSAGE(resumed, "normal message was lost, not resumed");
    TEST_ASSERT_EQUAL_UINT8(1, resumed->msgId);
    TEST_ASSERT_EQUAL_UINT8(1, resumed->fragIndex);
    g_tx.advance();

    TEST_ASSERT_TRUE(g_tx.empty());
    TEST_ASSERT_FALSE(g_tx.alertPending());
}

// Every fragment of both messages must reach the air exactly once.
static void test_preemption_loses_no_fragments(void) {
    Packet normal[MAX_FRAGMENTS];
    Packet alert[MAX_FRAGMENTS];
    const uint8_t nCount = makeFragments(normal, corpus::TA, corpus::TA_LEN, 40, 1, false);
    const uint8_t aCount = makeFragments(alert, corpus::KN, corpus::KN_LEN, 40, 2, true);
    TEST_ASSERT_GREATER_THAN_UINT8(3, nCount);

    g_tx.admit(normal, nCount, false);
    g_tx.advance();                       // one normal fragment out
    g_tx.admit(alert, aCount, true);

    bool seenNormal[MAX_FRAGMENTS] = {};
    bool seenAlert[MAX_FRAGMENTS] = {};
    seenNormal[0] = true;
    int guard = 0;
    while (!g_tx.empty() && guard++ < 64) {
        const Packet* p = g_tx.peek();
        TEST_ASSERT_NOT_NULL(p);
        if (p->msgId == 1) {
            TEST_ASSERT_FALSE(seenNormal[p->fragIndex]);   // never twice
            seenNormal[p->fragIndex] = true;
        } else {
            TEST_ASSERT_FALSE(seenAlert[p->fragIndex]);
            seenAlert[p->fragIndex] = true;
        }
        g_tx.advance();
    }
    for (uint8_t i = 0; i < nCount; ++i) TEST_ASSERT_TRUE(seenNormal[i]);
    for (uint8_t i = 0; i < aCount; ++i) TEST_ASSERT_TRUE(seenAlert[i]);
}

// The alert's fragments must not be interleaved once it has started - it runs
// to completion, then the normal message resumes.
static void test_alert_completes_before_normal_resumes(void) {
    Packet normal[MAX_FRAGMENTS];
    Packet alert[MAX_FRAGMENTS];
    const uint8_t nCount = makeFragments(normal, corpus::TA, corpus::TA_LEN, 60, 1, false);
    const uint8_t aCount = makeFragments(alert, corpus::TA, corpus::TA_LEN, 60, 2, true);
    TEST_ASSERT_GREATER_THAN_UINT8(1, aCount);

    g_tx.admit(normal, nCount, false);
    g_tx.advance();
    g_tx.admit(alert, aCount, true);

    for (uint8_t i = 0; i < aCount; ++i) {
        const Packet* p = g_tx.peek();
        TEST_ASSERT_EQUAL_UINT8(2, p->msgId);
        TEST_ASSERT_EQUAL_UINT8(i, p->fragIndex);
        g_tx.advance();
    }
    TEST_ASSERT_EQUAL_UINT8(1, g_tx.peek()->msgId);
}

// --- priority --------------------------------------------------------------

static void test_alert_jumps_a_queued_normal(void) {
    Packet a[MAX_FRAGMENTS], b[MAX_FRAGMENTS];
    const uint8_t aCount = makeFragments(a, corpus::EN, corpus::EN_LEN, 200, 1, false);
    const uint8_t bCount = makeFragments(b, corpus::EN, corpus::EN_LEN, 200, 2, true);

    g_tx.admit(a, aCount, false);
    g_tx.admit(b, bCount, true);
    // Nothing has started, so the alert goes first outright.
    TEST_ASSERT_EQUAL_UINT8(2, g_tx.peek()->msgId);
}

// An ALERT with no free slot displaces a NORMAL that has not started, rather
// than being refused.
static void test_alert_evicts_a_waiting_normal_when_full(void) {
    Packet first[MAX_FRAGMENTS], second[MAX_FRAGMENTS], alert[MAX_FRAGMENTS];
    // The first message must be MULTI-fragment, or one advance() completes it
    // and there is nothing in flight to fill the slot.
    const uint8_t fCount = makeFragments(first, corpus::TA, corpus::TA_LEN, 60, 1, false);
    const uint8_t sCount = makeFragments(second, corpus::EN, corpus::EN_LEN, 200, 2, false);
    const uint8_t aCount = makeFragments(alert, corpus::EN, corpus::EN_LEN, 200, 3, true);
    TEST_ASSERT_GREATER_THAN_UINT8(1, fCount);

    TEST_ASSERT_EQUAL(TxQueue::AdmitResult::Accepted, g_tx.admit(first, fCount, false));
    g_tx.advance();                       // message 1 is now genuinely in flight
    TEST_ASSERT_TRUE(g_tx.inFlight());
    TEST_ASSERT_EQUAL(TxQueue::AdmitResult::Accepted, g_tx.admit(second, sCount, false));
    TEST_ASSERT_EQUAL_UINT8(2, g_tx.queuedMessages());
    const uint8_t count = aCount;

    // Queue full, but an ALERT must still get in.
    TEST_ASSERT_EQUAL(TxQueue::AdmitResult::EvictedQueued,
                      g_tx.admit(alert, count, true));
    TEST_ASSERT_TRUE(g_tx.alertPending());
    TEST_ASSERT_EQUAL_UINT8(3, g_tx.peek()->msgId);
}

// ...but never one that has already put fragments on the air, or the peer is
// left holding fragments whose remainder never arrives.
static void test_alert_never_evicts_a_started_message(void) {
    Packet a[MAX_FRAGMENTS], b[MAX_FRAGMENTS], alert[MAX_FRAGMENTS];
    const uint8_t aCount = makeFragments(a, corpus::TA, corpus::TA_LEN, 60, 1, false);
    const uint8_t bCount = makeFragments(b, corpus::TA, corpus::TA_LEN, 60, 2, false);
    const uint8_t alCount = makeFragments(alert, corpus::TA, corpus::TA_LEN, 60, 3, true);
    TEST_ASSERT_GREATER_THAN_UINT8(1, aCount);

    g_tx.admit(a, aCount, false);
    g_tx.advance();                       // message 1 started
    g_tx.admit(b, bCount, false);
    g_tx.advance();                       // FIFO: still message 1
    TEST_ASSERT_EQUAL(TxQueue::AdmitResult::EvictedQueued,
                      g_tx.admit(alert, alCount, true));

    // Message 1 (started) survived; message 2 (not started) was displaced.
    bool sawOne = false, sawTwo = false;
    int guard = 0;
    while (!g_tx.empty() && guard++ < 64) {
        const uint8_t id = g_tx.peek()->msgId;
        if (id == 1) sawOne = true;
        if (id == 2) sawTwo = true;
        g_tx.advance();
    }
    TEST_ASSERT_TRUE_MESSAGE(sawOne, "an in-flight message was evicted");
    TEST_ASSERT_FALSE(sawTwo);
}

// When it genuinely cannot fit, say so. Silence is what caused the original bug.
static void test_full_queue_reports_rather_than_swallows(void) {
    Packet m[MAX_FRAGMENTS];
    const uint8_t count = makeFragments(m, corpus::TA, corpus::TA_LEN, 60, 1, true);

    TEST_ASSERT_EQUAL(TxQueue::AdmitResult::Accepted, g_tx.admit(m, count, true));
    g_tx.advance();
    TEST_ASSERT_EQUAL(TxQueue::AdmitResult::Accepted, g_tx.admit(m, count, true));
    g_tx.advance();
    // Two alerts, both started: a third has nowhere safe to go.
    TEST_ASSERT_EQUAL(TxQueue::AdmitResult::Full, g_tx.admit(m, count, true));
}

static void test_fifo_among_equal_priority(void) {
    Packet a[MAX_FRAGMENTS], b[MAX_FRAGMENTS];
    const uint8_t aCount = makeFragments(a, corpus::EN, corpus::EN_LEN, 200, 7, false);
    const uint8_t bCount = makeFragments(b, corpus::EN, corpus::EN_LEN, 200, 8, false);
    g_tx.admit(a, aCount, false);
    g_tx.admit(b, bCount, false);
    TEST_ASSERT_EQUAL_UINT8(7, g_tx.peek()->msgId);
    g_tx.advance();
    TEST_ASSERT_EQUAL_UINT8(8, g_tx.peek()->msgId);
}

static void test_empty_queue_is_safe(void) {
    TEST_ASSERT_TRUE(g_tx.empty());
    TEST_ASSERT_NULL(g_tx.peek());
    g_tx.advance();   // must not fault
    TEST_ASSERT_TRUE(g_tx.empty());
    TEST_ASSERT_FALSE(g_tx.alertPending());
    TEST_ASSERT_FALSE(g_tx.inFlight());
    TEST_ASSERT_EQUAL_UINT8(0, g_tx.queuedMessages());
}

static void test_admit_rejects_nonsense(void) {
    Packet m[MAX_FRAGMENTS];
    const uint8_t count = makeFragments(m, corpus::EN, corpus::EN_LEN, 200, 1, false);
    TEST_ASSERT_EQUAL(TxQueue::AdmitResult::Full, g_tx.admit(nullptr, count, false));
    TEST_ASSERT_EQUAL(TxQueue::AdmitResult::Full, g_tx.admit(m, 0, false));
    TEST_ASSERT_EQUAL(TxQueue::AdmitResult::Full,
                      g_tx.admit(m, MAX_FRAGMENTS + 1, false));
}

static void test_reset_clears_everything(void) {
    Packet m[MAX_FRAGMENTS];
    const uint8_t count = makeFragments(m, corpus::TA, corpus::TA_LEN, 60, 1, true);
    g_tx.admit(m, count, true);
    g_tx.advance();
    TEST_ASSERT_FALSE(g_tx.empty());
    g_tx.reset();
    TEST_ASSERT_TRUE(g_tx.empty());
    TEST_ASSERT_FALSE(g_tx.inFlight());
}

int main(void) {
    UNITY_BEGIN();
    RUN_TEST(test_alert_during_in_flight_normal_is_delivered_not_dropped);
    RUN_TEST(test_preemption_loses_no_fragments);
    RUN_TEST(test_alert_completes_before_normal_resumes);
    RUN_TEST(test_alert_jumps_a_queued_normal);
    RUN_TEST(test_alert_evicts_a_waiting_normal_when_full);
    RUN_TEST(test_alert_never_evicts_a_started_message);
    RUN_TEST(test_full_queue_reports_rather_than_swallows);
    RUN_TEST(test_fifo_among_equal_priority);
    RUN_TEST(test_empty_queue_is_safe);
    RUN_TEST(test_admit_rejects_nonsense);
    RUN_TEST(test_reset_clears_everything);
    return UNITY_END();
}
