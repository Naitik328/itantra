// App-envelope codec and the full adapter round-trip.
//
// The adapter's contract is that a message arriving at phone B is structurally
// identical to one sent directly phone-to-phone: same type, same lang, same
// payload bytes. Only src/seq/crc are regenerated, which the app treats as
// advisory. These tests hold that line.

#include <unity.h>

#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#include "../corpus.h"
#include "crc16.h"
#include "envelope.h"
#include "fragmenter.h"
#include "packet.h"
#include "reassembler.h"

using namespace lorax;
using namespace lorax::app;

static Reassembler g_rx;

void setUp() { g_rx.reset(); }
void tearDown() {}

// --- golden vector ---------------------------------------------------------
//
// Built by hand in Naitik's exact byte layout, with the CRC computed by an
// independent reimplementation of his Crc16.kt. If our codec and his ever drift
// apart, this is the test that fails.
static const uint8_t GOLDEN_HDR[6] = {0x11, 0x07, 0xFF, 0x04, 0x2A, 0x51};
static constexpr uint16_t GOLDEN_CRC = 0xC784;

static std::vector<uint8_t> goldenFrame() {
    std::vector<uint8_t> f(GOLDEN_HDR, GOLDEN_HDR + 6);
    f.insert(f.end(), corpus::EN, corpus::EN + corpus::EN_LEN);
    f.push_back(static_cast<uint8_t>(GOLDEN_CRC >> 8));
    f.push_back(static_cast<uint8_t>(GOLDEN_CRC & 0xFF));
    return f;
}

static void test_golden_envelope_parses(void) {
    const auto f = goldenFrame();
    TEST_ASSERT_EQUAL_UINT(89, f.size());

    Envelope e;
    TEST_ASSERT_EQUAL(EnvelopeResult::Ok, parseEnvelope(f.data(), f.size(), e));
    TEST_ASSERT_EQUAL_UINT8(1, e.version);
    TEST_ASSERT_EQUAL_UINT8(static_cast<uint8_t>(AppType::Alert), e.type);
    TEST_ASSERT_EQUAL_UINT8(0x07, e.src);
    TEST_ASSERT_EQUAL_UINT8(0xFF, e.dst);
    TEST_ASSERT_EQUAL_UINT8(4, e.lang);
    TEST_ASSERT_EQUAL_UINT8(0x2A, e.seq);
    TEST_ASSERT_EQUAL_UINT(corpus::EN_LEN, e.payloadLen);
    TEST_ASSERT_EQUAL_MEMORY(corpus::EN, e.payload, corpus::EN_LEN);
    TEST_ASSERT_TRUE(e.isAlert());
}

// Our CRC and his are the same algorithm; this pins that fact.
static void test_our_crc_matches_the_kotlin_implementation(void) {
    const auto f = goldenFrame();
    TEST_ASSERT_EQUAL_HEX16(GOLDEN_CRC, crc16(f.data(), f.size() - 2));
    TEST_ASSERT_EQUAL_HEX16(0x29B1,
                            crc16(reinterpret_cast<const uint8_t*>("123456789"), 9));
}

static void test_build_reproduces_the_golden_bytes(void) {
    Envelope e;
    e.type = static_cast<uint8_t>(AppType::Alert);
    e.src = 0x07;
    e.dst = 0xFF;
    e.lang = 4;
    e.seq = 0x2A;
    TEST_ASSERT_TRUE(e.setPayload(reinterpret_cast<const uint8_t*>(corpus::EN),
                                  corpus::EN_LEN));

    uint8_t out[ENVELOPE_MAX_FRAME];
    const size_t n = buildEnvelope(e, out, sizeof(out));
    const auto f = goldenFrame();
    TEST_ASSERT_EQUAL_UINT(f.size(), n);
    TEST_ASSERT_EQUAL_MEMORY(f.data(), out, n);
}

// --- sizing ----------------------------------------------------------------

static void test_max_payload_is_247_and_fills_255(void) {
    TEST_ASSERT_EQUAL_UINT(247, ENVELOPE_MAX_PAYLOAD);
    TEST_ASSERT_EQUAL_UINT(255, ENVELOPE_MAX_FRAME);
    TEST_ASSERT_EQUAL_UINT(8, ENVELOPE_OVERHEAD);

    Envelope e;
    std::string big(ENVELOPE_MAX_PAYLOAD, 'x');
    TEST_ASSERT_TRUE(e.setPayload(reinterpret_cast<const uint8_t*>(big.data()),
                                  big.size()));
    uint8_t out[ENVELOPE_MAX_FRAME];
    TEST_ASSERT_EQUAL_UINT(255, buildEnvelope(e, out, sizeof(out)));

    Envelope back;
    TEST_ASSERT_EQUAL(EnvelopeResult::Ok, parseEnvelope(out, 255, back));
    TEST_ASSERT_EQUAL_UINT(247, back.payloadLen);
}

// A 247-byte app payload becomes a 254-byte LoRa frame: it fits inside the
// SX1262's 255-byte limit with one byte to spare.
static void test_max_app_payload_fits_one_lora_frame(void) {
    TEST_ASSERT_EQUAL_UINT(254, ENVELOPE_MAX_PAYLOAD + OVERHEAD);
    TEST_ASSERT_LESS_OR_EQUAL_UINT(MAX_FRAME, ENVELOPE_MAX_PAYLOAD + OVERHEAD);
    TEST_ASSERT_LESS_OR_EQUAL_UINT(MAX_PAYLOAD, ENVELOPE_MAX_PAYLOAD);
}

static void test_oversize_payload_rejected(void) {
    Envelope e;
    std::string big(ENVELOPE_MAX_PAYLOAD + 1, 'x');
    TEST_ASSERT_FALSE(e.setPayload(reinterpret_cast<const uint8_t*>(big.data()),
                                   big.size()));
}

// --- defensive type handling ----------------------------------------------

// 3=HELLO, 4=ACCEPT, 5=DECLINE are Wi-Fi Direct handshake values. They are
// confirmed unable to reach BLE structurally - we drop them regardless.
static void test_handshake_and_unknown_types_are_dropped(void) {
    for (uint8_t type = 3; type <= 15; ++type) {
        uint8_t frame[ENVELOPE_OVERHEAD + 4];
        frame[0] = static_cast<uint8_t>((ENVELOPE_VERSION << 4) | type);
        frame[1] = 1;
        frame[2] = ENVELOPE_BROADCAST;
        frame[3] = 0;
        frame[4] = 9;
        frame[5] = 4;
        std::memcpy(frame + 6, "helo", 4);
        const uint16_t c = crc16(frame, 10);
        frame[10] = static_cast<uint8_t>(c >> 8);
        frame[11] = static_cast<uint8_t>(c & 0xFF);

        Envelope e;
        const EnvelopeResult r = parseEnvelope(frame, sizeof(frame), e);
        char msg[64];
        std::snprintf(msg, sizeof(msg), "type %u should be dropped", type);
        TEST_ASSERT_EQUAL_MESSAGE(EnvelopeResult::UnsupportedType, r, msg);
    }
}

static void test_supported_types_accepted(void) {
    for (uint8_t type = 0; type <= 2; ++type) {
        Envelope e;
        e.type = type;
        TEST_ASSERT_TRUE(e.setPayload(reinterpret_cast<const uint8_t*>("hi"), 2));
        uint8_t out[64];
        const size_t n = buildEnvelope(e, out, sizeof(out));
        Envelope back;
        TEST_ASSERT_EQUAL(EnvelopeResult::Ok, parseEnvelope(out, n, back));
        TEST_ASSERT_EQUAL_UINT8(type, back.type);
    }
}

// --- corruption ------------------------------------------------------------

static void test_envelope_crc_and_bounds(void) {
    const auto f = goldenFrame();
    Envelope e;

    std::vector<uint8_t> bad = f;
    bad[20] ^= 0x01;
    TEST_ASSERT_EQUAL(EnvelopeResult::CrcMismatch,
                      parseEnvelope(bad.data(), bad.size(), e));

    // CRC-clean but the declared length disagrees with the buffer.
    std::vector<uint8_t> wrongLen = f;
    wrongLen[5] = 80;
    const uint16_t c = crc16(wrongLen.data(), wrongLen.size() - 2);
    wrongLen[wrongLen.size() - 2] = static_cast<uint8_t>(c >> 8);
    wrongLen[wrongLen.size() - 1] = static_cast<uint8_t>(c & 0xFF);
    TEST_ASSERT_EQUAL(EnvelopeResult::LengthMismatch,
                      parseEnvelope(wrongLen.data(), wrongLen.size(), e));

    std::vector<uint8_t> badVer = f;
    badVer[0] = static_cast<uint8_t>((2u << 4) | 1u);
    const uint16_t c2 = crc16(badVer.data(), badVer.size() - 2);
    badVer[badVer.size() - 2] = static_cast<uint8_t>(c2 >> 8);
    badVer[badVer.size() - 1] = static_cast<uint8_t>(c2 & 0xFF);
    TEST_ASSERT_EQUAL(EnvelopeResult::BadVersion,
                      parseEnvelope(badVer.data(), badVer.size(), e));

    uint8_t tiny[4] = {};
    TEST_ASSERT_EQUAL(EnvelopeResult::TooShort, parseEnvelope(tiny, 4, e));

    std::vector<uint8_t> huge(ENVELOPE_MAX_FRAME + 1, 0);
    TEST_ASSERT_EQUAL(EnvelopeResult::TooLong,
                      parseEnvelope(huge.data(), huge.size(), e));
}

// --- the adapter round trip ------------------------------------------------
//
// Simulates the whole path with no radio: envelope in on side A, unwrapped,
// carried as LoRa packets, reassembled, rebuilt as an envelope on side B.
struct AdapterOut {
    bool     ok = false;
    Envelope envelope;
    size_t   fragments = 0;
};

static AdapterOut runAdapter(const uint8_t* inFrame, size_t inLen, size_t chunk,
                             uint8_t regeneratedSrc, uint8_t regeneratedSeq) {
    AdapterOut result;

    // --- side A: unwrap ---
    Envelope in;
    if (parseEnvelope(inFrame, inLen, in) != EnvelopeResult::Ok) return result;
    if (in.lang > MAX_LANGUAGE_ID) return result;

    FragmentOptions opt;
    opt.msgId   = 3;
    opt.langId  = in.lang;
    opt.flags   = in.isAlert() ? FLAG_ALERT : 0;
    opt.appType = in.type;

    Packet frags[MAX_FRAGMENTS];
    size_t count = 0;
    if (fragment(in.payload, in.payloadLen, chunk, opt, frags, MAX_FRAGMENTS,
                 count) != FragmentResult::Ok) {
        return result;
    }
    result.fragments = count;

    // --- the wire ---
    Reassembler::Result rr;
    for (size_t i = 0; i < count; ++i) {
        uint8_t wire[MAX_FRAME];
        const size_t n = serialize(frags[i], wire, sizeof(wire));
        if (n == 0) return result;
        Packet decoded;
        if (deserialize(wire, n, decoded) != DecodeResult::Ok) return result;
        rr = g_rx.offer(decoded, 1000);
    }
    if (rr.status != Reassembler::Status::Complete) return result;

    // --- side B: rewrap, regenerating src/seq/crc ---
    Envelope out;
    out.version = ENVELOPE_VERSION;
    out.type    = rr.appType;
    out.src     = regeneratedSrc;
    out.dst     = ENVELOPE_BROADCAST;
    out.lang    = rr.langId;
    out.seq     = regeneratedSeq;
    if (!out.setPayload(rr.data, rr.len)) return result;

    result.envelope = out;
    result.ok = true;
    return result;
}

static void test_adapter_roundtrip_is_lossless(void) {
    const auto f = goldenFrame();
    const AdapterOut r = runAdapter(f.data(), f.size(), 200, 0x42, 0x99);

    TEST_ASSERT_TRUE(r.ok);
    TEST_ASSERT_EQUAL_UINT(1, r.fragments);
    // Semantic content preserved exactly.
    TEST_ASSERT_EQUAL_UINT8(static_cast<uint8_t>(AppType::Alert), r.envelope.type);
    TEST_ASSERT_EQUAL_UINT8(4, r.envelope.lang);
    TEST_ASSERT_EQUAL_UINT(corpus::EN_LEN, r.envelope.payloadLen);
    TEST_ASSERT_EQUAL_MEMORY(corpus::EN, r.envelope.payload, corpus::EN_LEN);
    // src/seq regenerated, as agreed.
    TEST_ASSERT_EQUAL_UINT8(0x42, r.envelope.src);
    TEST_ASSERT_EQUAL_UINT8(0x99, r.envelope.seq);

    // And the rebuilt frame is a structurally valid envelope.
    uint8_t rebuilt[ENVELOPE_MAX_FRAME];
    const size_t n = buildEnvelope(r.envelope, rebuilt, sizeof(rebuilt));
    Envelope check;
    TEST_ASSERT_EQUAL(EnvelopeResult::Ok, parseEnvelope(rebuilt, n, check));
}

static void test_adapter_preserves_all_types_and_languages(void) {
    for (uint8_t type = 0; type <= 2; ++type) {
        for (uint8_t lang = 0; lang <= 9; ++lang) {
            g_rx.reset();
            Envelope e;
            e.type = type;
            e.lang = lang;
            e.setPayload(reinterpret_cast<const uint8_t*>(corpus::TA), corpus::TA_LEN);
            uint8_t frame[ENVELOPE_MAX_FRAME];
            const size_t n = buildEnvelope(e, frame, sizeof(frame));

            const AdapterOut r = runAdapter(frame, n, 200, 1, 1);
            TEST_ASSERT_TRUE(r.ok);
            TEST_ASSERT_EQUAL_UINT8(type, r.envelope.type);
            TEST_ASSERT_EQUAL_UINT8(lang, r.envelope.lang);
            TEST_ASSERT_EQUAL_MEMORY(corpus::TA, r.envelope.payload, corpus::TA_LEN);
        }
    }
}

// With MAX_PAYLOAD at 247 most real messages are now single-fragment, so the
// fragmentation path could rot untested. This forces it with concatenated
// sentences, and checks the reassembled bytes are identical.
static void test_forced_fragmentation_still_lossless(void) {
    std::string big;
    big.append(corpus::TA, corpus::TA_LEN);
    big.append(" ");
    big.append(corpus::HI, corpus::HI_LEN);
    big.append(" ");
    big.append(corpus::EN, corpus::EN_LEN);
    TEST_ASSERT_GREATER_THAN_UINT(ENVELOPE_MAX_PAYLOAD, big.size());

    // Too big for one envelope, so the app would have split it - but the
    // fragmentation path below is what carries anything over the chunk size.
    const std::string clipped = big.substr(0, ENVELOPE_MAX_PAYLOAD);
    Envelope e;
    e.lang = 4;
    TEST_ASSERT_TRUE(e.setPayload(
        reinterpret_cast<const uint8_t*>(clipped.data()), clipped.size()));
    uint8_t frame[ENVELOPE_MAX_FRAME];
    const size_t n = buildEnvelope(e, frame, sizeof(frame));

    for (size_t chunk : {size_t(64), size_t(40), size_t(24), size_t(17)}) {
        g_rx.reset();
        const AdapterOut r = runAdapter(frame, n, chunk, 1, 1);
        char msg[64];
        std::snprintf(msg, sizeof(msg), "chunk %zu", chunk);
        TEST_ASSERT_TRUE_MESSAGE(r.ok, msg);
        TEST_ASSERT_GREATER_THAN_UINT_MESSAGE(1, r.fragments, msg);
        TEST_ASSERT_EQUAL_UINT_MESSAGE(clipped.size(), r.envelope.payloadLen, msg);
        TEST_ASSERT_EQUAL_MEMORY_MESSAGE(clipped.data(), r.envelope.payload,
                                         clipped.size(), msg);
    }
}

static void test_forced_fragmentation_out_of_order(void) {
    Envelope e;
    e.lang = 4;
    e.type = static_cast<uint8_t>(AppType::Alert);
    std::string body;
    body.append(corpus::TA, corpus::TA_LEN);
    body.append(corpus::GU, corpus::GU_LEN);
    const std::string clipped = body.substr(0, ENVELOPE_MAX_PAYLOAD);
    e.setPayload(reinterpret_cast<const uint8_t*>(clipped.data()), clipped.size());

    FragmentOptions opt;
    opt.msgId = 11;
    opt.langId = e.lang;
    opt.flags = FLAG_ALERT;
    opt.appType = e.type;

    Packet frags[MAX_FRAGMENTS];
    size_t count = 0;
    TEST_ASSERT_EQUAL(FragmentResult::Ok,
                      fragment(e.payload, e.payloadLen, 32, opt, frags,
                               MAX_FRAGMENTS, count));
    TEST_ASSERT_GREATER_THAN_UINT(4, count);

    const size_t order[] = {5, 2, 0, 7, 1, 4, 3, 6};
    Reassembler::Result rr;
    for (size_t k = 0; k < count; ++k) {
        const size_t idx = order[k % 8] < count ? order[k % 8] : k;
        uint8_t wire[MAX_FRAME];
        const size_t n = serialize(frags[idx], wire, sizeof(wire));
        Packet d;
        TEST_ASSERT_EQUAL(DecodeResult::Ok, deserialize(wire, n, d));
        rr = g_rx.offer(d, 500);
    }
    // Any fragments the scramble missed, deliver now.
    for (size_t i = 0; i < count; ++i) {
        uint8_t wire[MAX_FRAME];
        const size_t n = serialize(frags[i], wire, sizeof(wire));
        Packet d;
        deserialize(wire, n, d);
        const auto r2 = g_rx.offer(d, 500);
        if (r2.status == Reassembler::Status::Complete) rr = r2;
    }
    TEST_ASSERT_EQUAL(Reassembler::Status::Complete, rr.status);
    TEST_ASSERT_EQUAL_UINT8(static_cast<uint8_t>(AppType::Alert), rr.appType);
    TEST_ASSERT_TRUE(rr.alert());
    TEST_ASSERT_EQUAL_UINT(clipped.size(), rr.len);
    TEST_ASSERT_EQUAL_MEMORY(clipped.data(), rr.data, clipped.size());
}

int main(void) {
    UNITY_BEGIN();
    RUN_TEST(test_golden_envelope_parses);
    RUN_TEST(test_our_crc_matches_the_kotlin_implementation);
    RUN_TEST(test_build_reproduces_the_golden_bytes);
    RUN_TEST(test_max_payload_is_247_and_fills_255);
    RUN_TEST(test_max_app_payload_fits_one_lora_frame);
    RUN_TEST(test_oversize_payload_rejected);
    RUN_TEST(test_handshake_and_unknown_types_are_dropped);
    RUN_TEST(test_supported_types_accepted);
    RUN_TEST(test_envelope_crc_and_bounds);
    RUN_TEST(test_adapter_roundtrip_is_lossless);
    RUN_TEST(test_adapter_preserves_all_types_and_languages);
    RUN_TEST(test_forced_fragmentation_still_lossless);
    RUN_TEST(test_forced_fragmentation_out_of_order);
    return UNITY_END();
}
