// Fragmentation and reassembly: out-of-order, duplicates, timeouts, and the
// full wire path (fragment -> serialize -> deserialize -> reassemble).

#include <unity.h>

#include <cstring>
#include <string>
#include <vector>

#include "../corpus.h"
#include "fragmenter.h"
#include "packet.h"
#include "reassembler.h"

using namespace lorax;

// ~16 KB: a static, never a stack local. Same rule applies on-target.
static Reassembler g_rx;

void setUp() {
    g_rx.setTimeoutMs(Reassembler::DEFAULT_TIMEOUT_MS);
    g_rx.reset();
}
void tearDown() {}

// --- helpers ---------------------------------------------------------------

static size_t splitCorpus(const char* text, size_t len, size_t chunk,
                          Packet* out, size_t cap, uint8_t msgId = 1,
                          uint8_t lang = 4, uint8_t flags = 0) {
    FragmentOptions opt;
    opt.msgId = msgId;
    opt.langId = lang;
    opt.flags = flags;
    size_t count = 0;
    const FragmentResult r =
        fragment(reinterpret_cast<const uint8_t*>(text), len, chunk, opt, out, cap, count);
    TEST_ASSERT_EQUAL_MESSAGE(FragmentResult::Ok, r, fragmentResultName(r));
    return count;
}

// Pushes a packet through serialize/deserialize before handing it to the
// reassembler, so these tests exercise the real byte path, not just structs.
static Reassembler::Result offerOverWire(Reassembler& rx, const Packet& p, uint32_t nowMs) {
    uint8_t buf[MAX_FRAME];
    const size_t n = serialize(p, buf, sizeof(buf));
    TEST_ASSERT_GREATER_THAN_UINT(0, n);
    Packet decoded;
    TEST_ASSERT_EQUAL(DecodeResult::Ok, deserialize(buf, n, decoded));
    return rx.offer(decoded, nowMs);
}

// --- fragment maths --------------------------------------------------------

static void test_fragment_count_math(void) {
    TEST_ASSERT_EQUAL_UINT(1, fragmentCount(0, 100));    // empty is still one
    TEST_ASSERT_EQUAL_UINT(1, fragmentCount(1, 100));
    TEST_ASSERT_EQUAL_UINT(1, fragmentCount(100, 100));  // exact fit
    TEST_ASSERT_EQUAL_UINT(2, fragmentCount(101, 100));
    TEST_ASSERT_EQUAL_UINT(2, fragmentCount(200, 100));
    TEST_ASSERT_EQUAL_UINT(3, fragmentCount(201, 100));
    TEST_ASSERT_EQUAL_UINT(0, fragmentCount(50, 0));
}

static void test_max_fragment_payload_helper(void) {
    TEST_ASSERT_EQUAL_UINT(248, maxFragmentPayload(255));
    TEST_ASSERT_EQUAL_UINT(161, maxFragmentPayload(168));
    TEST_ASSERT_EQUAL_UINT(0, maxFragmentPayload(7));
    TEST_ASSERT_EQUAL_UINT(0, maxFragmentPayload(3));
}

// --- splitting -------------------------------------------------------------

static void test_tamil_splits_and_covers_every_byte(void) {
    Packet frags[MAX_FRAGMENTS];
    for (size_t chunk : {size_t(200), size_t(168), size_t(64), size_t(32), size_t(16)}) {
        const size_t count = splitCorpus(corpus::TA, corpus::TA_LEN, chunk,
                                         frags, MAX_FRAGMENTS);
        TEST_ASSERT_EQUAL_UINT(fragmentCount(corpus::TA_LEN, chunk), count);

        std::string rebuilt;
        for (size_t i = 0; i < count; ++i) {
            TEST_ASSERT_EQUAL_UINT8(i, frags[i].fragIndex);
            TEST_ASSERT_EQUAL_UINT8(count, frags[i].fragCount);
            TEST_ASSERT_EQUAL_UINT8(4, frags[i].langId);
            TEST_ASSERT_LESS_OR_EQUAL_UINT(chunk, frags[i].payloadLen);
            if (i + 1 < count) {
                // every fragment but the last is full
                TEST_ASSERT_EQUAL_UINT(chunk, frags[i].payloadLen);
            }
            rebuilt.append(reinterpret_cast<const char*>(frags[i].payload),
                           frags[i].payloadLen);
        }
        TEST_ASSERT_EQUAL_UINT(corpus::TA_LEN, rebuilt.size());
        TEST_ASSERT_EQUAL_MEMORY(corpus::TA, rebuilt.data(), corpus::TA_LEN);
    }
}

static void test_fragment_empty_message(void) {
    Packet frags[MAX_FRAGMENTS];
    const size_t count = splitCorpus("", 0, 50, frags, MAX_FRAGMENTS);
    TEST_ASSERT_EQUAL_UINT(1, count);
    TEST_ASSERT_EQUAL_UINT8(0, frags[0].payloadLen);
    TEST_ASSERT_EQUAL_UINT8(1, frags[0].fragCount);
}

static void test_fragment_exactly_at_boundaries(void) {
    Packet frags[MAX_FRAGMENTS];
    std::string s(240, 'z');
    // 240 / 60 == 4 exactly: no short tail fragment.
    const size_t count = splitCorpus(s.data(), s.size(), 60, frags, MAX_FRAGMENTS);
    TEST_ASSERT_EQUAL_UINT(4, count);
    for (size_t i = 0; i < count; ++i) {
        TEST_ASSERT_EQUAL_UINT8(60, frags[i].payloadLen);
    }
}

// 239 B at 15 B/fragment is exactly 16 fragments - the ceiling the 4-bit
// nibble allows. One byte smaller per fragment and it must fail loudly.
static void test_fragment_ceiling_is_enforced(void) {
    Packet frags[MAX_FRAGMENTS];
    const size_t count = splitCorpus(corpus::TA, corpus::TA_LEN, 15, frags, MAX_FRAGMENTS);
    TEST_ASSERT_EQUAL_UINT(16, count);

    FragmentOptions opt;
    size_t bad = 0;
    TEST_ASSERT_EQUAL(FragmentResult::TooManyFragments,
                      fragment(reinterpret_cast<const uint8_t*>(corpus::TA),
                               corpus::TA_LEN, 14, opt, frags, MAX_FRAGMENTS, bad));
    TEST_ASSERT_EQUAL_UINT(0, bad);
}

static void test_fragment_argument_validation(void) {
    Packet frags[MAX_FRAGMENTS];
    FragmentOptions opt;
    size_t count = 0;

    TEST_ASSERT_EQUAL(FragmentResult::InvalidChunkSize,
                      fragment(reinterpret_cast<const uint8_t*>("abc"), 3, 0, opt,
                               frags, MAX_FRAGMENTS, count));
    TEST_ASSERT_EQUAL(FragmentResult::InvalidChunkSize,
                      fragment(reinterpret_cast<const uint8_t*>("abc"), 3,
                               MAX_PAYLOAD + 1, opt, frags, MAX_FRAGMENTS, count));
    TEST_ASSERT_EQUAL(FragmentResult::InvalidArgument,
                      fragment(nullptr, 5, 10, opt, frags, MAX_FRAGMENTS, count));
    TEST_ASSERT_EQUAL(FragmentResult::OutputTooSmall,
                      fragment(reinterpret_cast<const uint8_t*>(corpus::TA),
                               corpus::TA_LEN, 32, opt, frags, 2, count));

    FragmentOptions badLang;
    badLang.langId = 200;
    TEST_ASSERT_EQUAL(FragmentResult::InvalidArgument,
                      fragment(reinterpret_cast<const uint8_t*>("abc"), 3, 10,
                               badLang, frags, MAX_FRAGMENTS, count));
}

// --- reassembly ------------------------------------------------------------

static void test_reassemble_in_order(void) {
    Packet frags[MAX_FRAGMENTS];
    const size_t count = splitCorpus(corpus::TA, corpus::TA_LEN, 64, frags, MAX_FRAGMENTS);
    TEST_ASSERT_EQUAL_UINT(4, count);

    for (size_t i = 0; i + 1 < count; ++i) {
        const auto r = offerOverWire(g_rx, frags[i], 1000);
        TEST_ASSERT_EQUAL(Reassembler::Status::Incomplete, r.status);
        TEST_ASSERT_NULL(r.data);  // never hand up partial text
    }
    const auto done = offerOverWire(g_rx, frags[count - 1], 1000);
    TEST_ASSERT_EQUAL(Reassembler::Status::Complete, done.status);
    TEST_ASSERT_EQUAL_UINT(corpus::TA_LEN, done.len);
    TEST_ASSERT_EQUAL_MEMORY(corpus::TA, done.data, corpus::TA_LEN);
    TEST_ASSERT_EQUAL_UINT8(4, done.langId);
    TEST_ASSERT_EQUAL_UINT(0, g_rx.activeSlots());
}

static void test_reassemble_reverse_order(void) {
    Packet frags[MAX_FRAGMENTS];
    const size_t count = splitCorpus(corpus::TA, corpus::TA_LEN, 32, frags, MAX_FRAGMENTS);
    TEST_ASSERT_EQUAL_UINT(8, count);

    Reassembler::Result last;
    for (size_t k = count; k > 0; --k) {
        last = offerOverWire(g_rx, frags[k - 1], 500);
    }
    TEST_ASSERT_EQUAL(Reassembler::Status::Complete, last.status);
    TEST_ASSERT_EQUAL_UINT(corpus::TA_LEN, last.len);
    TEST_ASSERT_EQUAL_MEMORY(corpus::TA, last.data, corpus::TA_LEN);
}

static void test_reassemble_scrambled_order(void) {
    Packet frags[MAX_FRAGMENTS];
    const size_t count = splitCorpus(corpus::TA, corpus::TA_LEN, 40, frags, MAX_FRAGMENTS);
    TEST_ASSERT_EQUAL_UINT(6, count);

    const size_t order[] = {3, 0, 5, 1, 4, 2};
    Reassembler::Result last;
    for (size_t i = 0; i < count; ++i) {
        last = offerOverWire(g_rx, frags[order[i]], 900);
        if (i + 1 < count) {
            TEST_ASSERT_EQUAL(Reassembler::Status::Incomplete, last.status);
        }
    }
    TEST_ASSERT_EQUAL(Reassembler::Status::Complete, last.status);
    TEST_ASSERT_EQUAL_MEMORY(corpus::TA, last.data, corpus::TA_LEN);
}

static void test_duplicate_fragments_ignored(void) {
    Packet frags[MAX_FRAGMENTS];
    const size_t count = splitCorpus(corpus::KN, corpus::KN_LEN, 64, frags, MAX_FRAGMENTS);
    TEST_ASSERT_EQUAL_UINT(3, count);

    TEST_ASSERT_EQUAL(Reassembler::Status::Incomplete, offerOverWire(g_rx, frags[0], 10).status);
    // same fragment three more times
    for (int i = 0; i < 3; ++i) {
        TEST_ASSERT_EQUAL(Reassembler::Status::Duplicate, offerOverWire(g_rx, frags[0], 20).status);
    }
    TEST_ASSERT_EQUAL(Reassembler::Status::Incomplete, offerOverWire(g_rx, frags[1], 30).status);
    TEST_ASSERT_EQUAL(Reassembler::Status::Duplicate, offerOverWire(g_rx, frags[1], 31).status);

    const auto done = offerOverWire(g_rx, frags[2], 40);
    TEST_ASSERT_EQUAL(Reassembler::Status::Complete, done.status);
    TEST_ASSERT_EQUAL_MEMORY(corpus::KN, done.data, corpus::KN_LEN);
}

// The peer retransmits the whole message because our ACK was lost. Speaking an
// emergency sentence twice is a real failure, so this must be absorbed.
static void test_completed_message_not_emitted_twice(void) {
    Packet frags[MAX_FRAGMENTS];
    const size_t count = splitCorpus(corpus::EN, corpus::EN_LEN, 30, frags, MAX_FRAGMENTS, 55);

    Reassembler::Result last;
    for (size_t i = 0; i < count; ++i) {
        last = offerOverWire(g_rx, frags[i], 100);
    }
    TEST_ASSERT_EQUAL(Reassembler::Status::Complete, last.status);

    for (size_t i = 0; i < count; ++i) {
        TEST_ASSERT_EQUAL(Reassembler::Status::Duplicate,
                          offerOverWire(g_rx, frags[i], 200).status);
    }
    TEST_ASSERT_EQUAL_UINT(0, g_rx.activeSlots());
}

static void test_missing_fragment_never_completes(void) {
    Packet frags[MAX_FRAGMENTS];
    const size_t count = splitCorpus(corpus::BN, corpus::BN_LEN, 40, frags, MAX_FRAGMENTS);
    TEST_ASSERT_GREATER_THAN_UINT(2, count);

    // deliver everything except fragment 2, repeatedly
    for (int pass = 0; pass < 3; ++pass) {
        for (size_t i = 0; i < count; ++i) {
            if (i == 2) continue;
            const auto r = offerOverWire(g_rx, frags[i], 100 + pass);
            TEST_ASSERT_NOT_EQUAL(Reassembler::Status::Complete, r.status);
        }
    }
    TEST_ASSERT_EQUAL_UINT(1, g_rx.activeSlots());
}

// After the timeout the partial message is discarded, so a late final fragment
// must NOT resurrect it into a truncated message.
static void test_timeout_discards_partial_message(void) {
    Packet frags[MAX_FRAGMENTS];
    const size_t count = splitCorpus(corpus::TA, corpus::TA_LEN, 80, frags, MAX_FRAGMENTS);
    TEST_ASSERT_EQUAL_UINT(3, count);

    TEST_ASSERT_EQUAL(Reassembler::Status::Incomplete, g_rx.offer(frags[0], 1000).status);
    TEST_ASSERT_EQUAL(Reassembler::Status::Incomplete, g_rx.offer(frags[1], 1000).status);
    TEST_ASSERT_EQUAL_UINT(1, g_rx.activeSlots());

    TEST_ASSERT_EQUAL_UINT(0, g_rx.evictExpired(1000 + Reassembler::DEFAULT_TIMEOUT_MS - 1));
    TEST_ASSERT_EQUAL_UINT(1, g_rx.evictExpired(1000 + Reassembler::DEFAULT_TIMEOUT_MS));
    TEST_ASSERT_EQUAL_UINT(0, g_rx.activeSlots());

    const auto late = g_rx.offer(frags[2], 1000 + Reassembler::DEFAULT_TIMEOUT_MS + 5);
    TEST_ASSERT_EQUAL(Reassembler::Status::Incomplete, late.status);
    TEST_ASSERT_NULL(late.data);
}

// offer() also evicts lazily, so a caller that never runs the periodic tick
// still cannot accumulate stale state or emit a spliced message.
static void test_offer_evicts_lazily(void) {
    Packet frags[MAX_FRAGMENTS];
    const size_t count = splitCorpus(corpus::TA, corpus::TA_LEN, 80, frags, MAX_FRAGMENTS);
    TEST_ASSERT_EQUAL_UINT(3, count);

    g_rx.offer(frags[0], 0);
    g_rx.offer(frags[1], 0);
    const auto late = g_rx.offer(frags[2], 100000);
    TEST_ASSERT_EQUAL(Reassembler::Status::Incomplete, late.status);
}

// A completed message can be resent later with the same id once the duplicate
// window has passed - that must work, not be suppressed forever.
static void test_same_msgid_accepted_after_window(void) {
    Packet frags[MAX_FRAGMENTS];
    const size_t count = splitCorpus(corpus::EN, corpus::EN_LEN, 40, frags, MAX_FRAGMENTS, 7);

    Reassembler::Result last;
    for (size_t i = 0; i < count; ++i) last = g_rx.offer(frags[i], 1000);
    TEST_ASSERT_EQUAL(Reassembler::Status::Complete, last.status);

    const uint32_t later = 1000 + Reassembler::DEFAULT_TIMEOUT_MS + 1;
    for (size_t i = 0; i < count; ++i) last = g_rx.offer(frags[i], later);
    TEST_ASSERT_EQUAL(Reassembler::Status::Complete, last.status);
    TEST_ASSERT_EQUAL_MEMORY(corpus::EN, last.data, corpus::EN_LEN);
}

// Same msgId but a different fragment count means the id was reused. The old
// partial state must be dropped, never blended into the new message.
static void test_msgid_reuse_with_different_count(void) {
    Packet a[MAX_FRAGMENTS], b[MAX_FRAGMENTS];
    splitCorpus(corpus::TA, corpus::TA_LEN, 60, a, MAX_FRAGMENTS, 9, 4);
    const size_t bCount = splitCorpus(corpus::EN, corpus::EN_LEN, 30, b, MAX_FRAGMENTS, 9, 5);

    g_rx.offer(a[0], 100);
    g_rx.offer(a[1], 100);

    Reassembler::Result last;
    for (size_t i = 0; i < bCount; ++i) last = g_rx.offer(b[i], 150);
    TEST_ASSERT_EQUAL(Reassembler::Status::Complete, last.status);
    TEST_ASSERT_EQUAL_UINT(corpus::EN_LEN, last.len);
    TEST_ASSERT_EQUAL_MEMORY(corpus::EN, last.data, corpus::EN_LEN);
    // metadata must come from the NEW message, not the discarded one
    TEST_ASSERT_EQUAL_UINT8(5, last.langId);
}

static void test_interleaved_messages(void) {
    Packet a[MAX_FRAGMENTS], b[MAX_FRAGMENTS];
    const size_t ca = splitCorpus(corpus::HI, corpus::HI_LEN, 60, a, MAX_FRAGMENTS, 1, 0);
    const size_t cb = splitCorpus(corpus::GU, corpus::GU_LEN, 60, b, MAX_FRAGMENTS, 2, 2);
    TEST_ASSERT_EQUAL_UINT(3, ca);
    TEST_ASSERT_EQUAL_UINT(3, cb);

    g_rx.offer(a[0], 10);
    g_rx.offer(b[2], 11);
    g_rx.offer(a[2], 12);
    g_rx.offer(b[0], 13);
    TEST_ASSERT_EQUAL_UINT(2, g_rx.activeSlots());

    const auto doneA = g_rx.offer(a[1], 14);
    TEST_ASSERT_EQUAL(Reassembler::Status::Complete, doneA.status);
    TEST_ASSERT_EQUAL_UINT8(0, doneA.langId);
    TEST_ASSERT_EQUAL_MEMORY(corpus::HI, doneA.data, corpus::HI_LEN);

    const auto doneB = g_rx.offer(b[1], 15);
    TEST_ASSERT_EQUAL(Reassembler::Status::Complete, doneB.status);
    TEST_ASSERT_EQUAL_UINT8(2, doneB.langId);
    TEST_ASSERT_EQUAL_MEMORY(corpus::GU, doneB.data, corpus::GU_LEN);
}

static void test_reassembler_rejects_control_and_malformed(void) {
    const Packet ack = makeControl(PacketType::Ack, 3, 0, 1);
    TEST_ASSERT_EQUAL(Reassembler::Status::Rejected, g_rx.offer(ack, 0).status);

    Packet bad;
    bad.type = PacketType::Data;
    bad.fragIndex = 4;
    bad.fragCount = 2;
    TEST_ASSERT_EQUAL(Reassembler::Status::Rejected, g_rx.offer(bad, 0).status);

    Packet zero;
    zero.fragCount = 0;
    TEST_ASSERT_EQUAL(Reassembler::Status::Rejected, g_rx.offer(zero, 0).status);
}

static void test_flags_survive_reassembly(void) {
    Packet frags[MAX_FRAGMENTS];
    const size_t count = splitCorpus(corpus::GU, corpus::GU_LEN, 50, frags,
                                     MAX_FRAGMENTS, 12, 2,
                                     FLAG_COMPRESSED | FLAG_ALERT);
    Reassembler::Result last;
    for (size_t i = 0; i < count; ++i) last = offerOverWire(g_rx, frags[i], 5);
    TEST_ASSERT_EQUAL(Reassembler::Status::Complete, last.status);
    TEST_ASSERT_TRUE(last.compressed());
    TEST_ASSERT_TRUE(last.alert());
}

static void test_sixteen_fragment_message(void) {
    Packet frags[MAX_FRAGMENTS];
    const size_t count = splitCorpus(corpus::TA, corpus::TA_LEN, 15, frags, MAX_FRAGMENTS);
    TEST_ASSERT_EQUAL_UINT(16, count);

    // scrambled delivery of all 16, plus a duplicate of every third
    Reassembler::Result last;
    const size_t order[16] = {15, 0, 7, 3, 11, 1, 9, 5, 13, 2, 8, 4, 12, 6, 14, 10};
    for (size_t i = 0; i < 16; ++i) {
        last = offerOverWire(g_rx, frags[order[i]], 100);
        if (i % 3 == 0) {
            offerOverWire(g_rx, frags[order[i]], 100);
        }
    }
    TEST_ASSERT_EQUAL(Reassembler::Status::Complete, last.status);
    TEST_ASSERT_EQUAL_UINT(corpus::TA_LEN, last.len);
    TEST_ASSERT_EQUAL_MEMORY(corpus::TA, last.data, corpus::TA_LEN);
}

// Full path for every language at a realistic SF10 fragment size.
static void test_all_languages_over_the_wire(void) {
    struct Item { const char* text; size_t len; uint8_t lang; };
    const Item items[] = {
        {corpus::HI, corpus::HI_LEN, 0}, {corpus::BN, corpus::BN_LEN, 1},
        {corpus::GU, corpus::GU_LEN, 2}, {corpus::KN, corpus::KN_LEN, 3},
        {corpus::TA, corpus::TA_LEN, 4}, {corpus::EN, corpus::EN_LEN, 5},
    };
    uint8_t msgId = 100;
    for (const Item& it : items) {
        g_rx.reset();
        Packet frags[MAX_FRAGMENTS];
        const size_t count = splitCorpus(it.text, it.len, maxFragmentPayload(168),
                                         frags, MAX_FRAGMENTS, msgId++, it.lang);
        Reassembler::Result last;
        for (size_t i = 0; i < count; ++i) {
            last = offerOverWire(g_rx, frags[i], 1000);
        }
        TEST_ASSERT_EQUAL(Reassembler::Status::Complete, last.status);
        TEST_ASSERT_EQUAL_UINT(it.len, last.len);
        TEST_ASSERT_EQUAL_MEMORY(it.text, last.data, it.len);
        TEST_ASSERT_EQUAL_UINT8(it.lang, last.langId);
    }
}

// A fragment corrupted in flight is rejected by the codec and never reaches
// the reassembler, so the message simply stays incomplete.
static void test_corrupted_fragment_is_dropped_before_reassembly(void) {
    Packet frags[MAX_FRAGMENTS];
    const size_t count = splitCorpus(corpus::TA, corpus::TA_LEN, 80, frags, MAX_FRAGMENTS);

    for (size_t i = 0; i < count; ++i) {
        uint8_t buf[MAX_FRAME];
        const size_t n = serialize(frags[i], buf, sizeof(buf));
        if (i == 1) {
            buf[HEADER_SIZE + 4] ^= 0x20;  // corrupt one payload byte
        }
        Packet decoded;
        const DecodeResult dr = deserialize(buf, n, decoded);
        if (i == 1) {
            TEST_ASSERT_EQUAL(DecodeResult::CrcMismatch, dr);
            continue;  // radio layer would drop it and wait for a retransmit
        }
        TEST_ASSERT_EQUAL(DecodeResult::Ok, dr);
        TEST_ASSERT_NOT_EQUAL(Reassembler::Status::Complete, g_rx.offer(decoded, 1).status);
    }
    TEST_ASSERT_EQUAL_UINT(1, g_rx.activeSlots());
}

static void test_reset_clears_everything(void) {
    Packet frags[MAX_FRAGMENTS];
    splitCorpus(corpus::TA, corpus::TA_LEN, 60, frags, MAX_FRAGMENTS);
    g_rx.offer(frags[0], 1);
    TEST_ASSERT_EQUAL_UINT(1, g_rx.activeSlots());
    g_rx.reset();
    TEST_ASSERT_EQUAL_UINT(0, g_rx.activeSlots());
}

int main(void) {
    UNITY_BEGIN();
    RUN_TEST(test_fragment_count_math);
    RUN_TEST(test_max_fragment_payload_helper);
    RUN_TEST(test_tamil_splits_and_covers_every_byte);
    RUN_TEST(test_fragment_empty_message);
    RUN_TEST(test_fragment_exactly_at_boundaries);
    RUN_TEST(test_fragment_ceiling_is_enforced);
    RUN_TEST(test_fragment_argument_validation);
    RUN_TEST(test_reassemble_in_order);
    RUN_TEST(test_reassemble_reverse_order);
    RUN_TEST(test_reassemble_scrambled_order);
    RUN_TEST(test_duplicate_fragments_ignored);
    RUN_TEST(test_completed_message_not_emitted_twice);
    RUN_TEST(test_missing_fragment_never_completes);
    RUN_TEST(test_timeout_discards_partial_message);
    RUN_TEST(test_offer_evicts_lazily);
    RUN_TEST(test_same_msgid_accepted_after_window);
    RUN_TEST(test_msgid_reuse_with_different_count);
    RUN_TEST(test_interleaved_messages);
    RUN_TEST(test_reassembler_rejects_control_and_malformed);
    RUN_TEST(test_flags_survive_reassembly);
    RUN_TEST(test_sixteen_fragment_message);
    RUN_TEST(test_all_languages_over_the_wire);
    RUN_TEST(test_corrupted_fragment_is_dropped_before_reassembly);
    RUN_TEST(test_reset_clears_everything);
    return UNITY_END();
}
