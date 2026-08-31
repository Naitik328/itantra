// Packet codec tests: round-trip, boundaries, corruption, CRC gatekeeping.

#include <unity.h>

#include <cstdio>
#include <cstring>
#include <string>

#include "../corpus.h"
#include "crc16.h"
#include "packet.h"
#include "radio_config.h"

using namespace lorax;

void setUp() {}
void tearDown() {}

// --- helpers ---------------------------------------------------------------

static Packet makeDataPacket(const char* text, uint8_t lang = 3, uint8_t msgId = 42) {
    Packet p;
    p.type = PacketType::Data;
    p.langId = lang;
    p.msgId = msgId;
    p.fragIndex = 0;
    p.fragCount = 1;
    TEST_ASSERT_TRUE(p.setPayload(reinterpret_cast<const uint8_t*>(text), std::strlen(text)));
    return p;
}

// Recompute and patch the CRC trailer so a hand-edited frame is "valid" apart
// from the field we are deliberately testing.
static void refreshCrc(uint8_t* buf, size_t len) {
    const uint16_t c = crc16(buf, len - CRC_SIZE);
    buf[len - 2] = static_cast<uint8_t>(c >> 8);
    buf[len - 1] = static_cast<uint8_t>(c & 0xFF);
}

// --- CRC -------------------------------------------------------------------

// The published check value for CRC-16/CCITT-FALSE. If this passes, our
// implementation is the algorithm we claim it is.
static void test_crc_known_answer(void) {
    const char* s = "123456789";
    TEST_ASSERT_EQUAL_HEX16(0x29B1, crc16(reinterpret_cast<const uint8_t*>(s), 9));
}

static void test_crc_incremental_matches_oneshot(void) {
    const uint8_t data[] = {1, 2, 3, 4, 5, 6, 7, 8};
    const uint16_t once = crc16(data, 8);
    uint16_t running = crc16Update(CRC16_INIT, data, 3);
    running = crc16Update(running, data + 3, 5);
    TEST_ASSERT_EQUAL_HEX16(once, running);
}

static void test_crc_empty_input(void) {
    TEST_ASSERT_EQUAL_HEX16(CRC16_INIT, crc16(nullptr, 0));
}

// --- layout constants ------------------------------------------------------

static void test_overhead_is_seven_bytes(void) {
    TEST_ASSERT_EQUAL_UINT(5, HEADER_SIZE);
    TEST_ASSERT_EQUAL_UINT(2, CRC_SIZE);
    TEST_ASSERT_EQUAL_UINT(7, OVERHEAD);
    TEST_ASSERT_EQUAL_UINT(248, MAX_PAYLOAD);
    TEST_ASSERT_EQUAL_UINT(255, MAX_FRAME);
}

static void test_serialized_size_is_payload_plus_overhead(void) {
    uint8_t buf[MAX_FRAME];
    for (size_t n : {size_t(0), size_t(1), size_t(80), size_t(239), MAX_PAYLOAD}) {
        Packet p;
        std::string s(n, 'x');
        TEST_ASSERT_TRUE(p.setPayload(reinterpret_cast<const uint8_t*>(s.data()), n));
        TEST_ASSERT_EQUAL_UINT(n + OVERHEAD, serialize(p, buf, sizeof(buf)));
    }
}

// --- round trips -----------------------------------------------------------

static void test_roundtrip_basic(void) {
    Packet in = makeDataPacket("hello", 7, 200);
    in.setAlert(true);
    in.setCompressed(true);

    uint8_t buf[MAX_FRAME];
    const size_t n = serialize(in, buf, sizeof(buf));
    TEST_ASSERT_GREATER_THAN_UINT(0, n);

    Packet out;
    TEST_ASSERT_EQUAL(DecodeResult::Ok, deserialize(buf, n, out));
    TEST_ASSERT_EQUAL_UINT8(in.version, out.version);
    TEST_ASSERT_EQUAL_UINT8(static_cast<uint8_t>(in.type), static_cast<uint8_t>(out.type));
    TEST_ASSERT_EQUAL_UINT8(7, out.langId);
    TEST_ASSERT_EQUAL_UINT8(200, out.msgId);
    TEST_ASSERT_EQUAL_UINT8(0, out.fragIndex);
    TEST_ASSERT_EQUAL_UINT8(1, out.fragCount);
    TEST_ASSERT_TRUE(out.isAlert());
    TEST_ASSERT_TRUE(out.isCompressed());
    TEST_ASSERT_EQUAL_UINT8(5, out.payloadLen);
    TEST_ASSERT_EQUAL_MEMORY("hello", out.payload, 5);
}

static void test_roundtrip_empty_payload(void) {
    Packet in;
    in.payloadLen = 0;
    uint8_t buf[MAX_FRAME];
    const size_t n = serialize(in, buf, sizeof(buf));
    TEST_ASSERT_EQUAL_UINT(OVERHEAD, n);

    Packet out;
    TEST_ASSERT_EQUAL(DecodeResult::Ok, deserialize(buf, n, out));
    TEST_ASSERT_EQUAL_UINT8(0, out.payloadLen);
}

static void test_roundtrip_max_payload(void) {
    Packet in;
    uint8_t big[MAX_PAYLOAD];
    for (size_t i = 0; i < MAX_PAYLOAD; ++i) {
        big[i] = static_cast<uint8_t>(i * 7 + 13);
    }
    TEST_ASSERT_TRUE(in.setPayload(big, MAX_PAYLOAD));

    uint8_t buf[MAX_FRAME];
    const size_t n = serialize(in, buf, sizeof(buf));
    TEST_ASSERT_EQUAL_UINT(MAX_FRAME, n);

    Packet out;
    TEST_ASSERT_EQUAL(DecodeResult::Ok, deserialize(buf, n, out));
    TEST_ASSERT_EQUAL_UINT8(MAX_PAYLOAD, out.payloadLen);
    TEST_ASSERT_EQUAL_MEMORY(big, out.payload, MAX_PAYLOAD);
}

static void test_payload_over_max_is_rejected(void) {
    Packet p;
    uint8_t big[MAX_PAYLOAD + 1] = {};
    TEST_ASSERT_FALSE(p.setPayload(big, MAX_PAYLOAD + 1));
}

static void test_all_language_ids_roundtrip(void) {
    uint8_t buf[MAX_FRAME];
    for (uint8_t lang = 0; lang <= MAX_LANGUAGE_ID; ++lang) {
        Packet in = makeDataPacket("x", lang);
        const size_t n = serialize(in, buf, sizeof(buf));
        TEST_ASSERT_GREATER_THAN_UINT(0, n);
        Packet out;
        TEST_ASSERT_EQUAL(DecodeResult::Ok, deserialize(buf, n, out));
        TEST_ASSERT_EQUAL_UINT8(lang, out.langId);
    }
}

static void test_all_packet_types_roundtrip(void) {
    uint8_t buf[MAX_FRAME];
    const PacketType types[] = {PacketType::Data, PacketType::Ack,
                                PacketType::Nack, PacketType::Beacon};
    for (PacketType t : types) {
        Packet in;
        in.type = t;
        const size_t n = serialize(in, buf, sizeof(buf));
        TEST_ASSERT_GREATER_THAN_UINT(0, n);
        Packet out;
        TEST_ASSERT_EQUAL(DecodeResult::Ok, deserialize(buf, n, out));
        TEST_ASSERT_EQUAL_UINT8(static_cast<uint8_t>(t), static_cast<uint8_t>(out.type));
    }
}

static void test_all_flag_combinations_roundtrip(void) {
    uint8_t buf[MAX_FRAME];
    for (uint8_t f = 0; f < 4; ++f) {  // only bits 0 and 1 are defined
        Packet in;
        in.flags = f;
        const size_t n = serialize(in, buf, sizeof(buf));
        TEST_ASSERT_GREATER_THAN_UINT(0, n);
        Packet out;
        TEST_ASSERT_EQUAL(DecodeResult::Ok, deserialize(buf, n, out));
        TEST_ASSERT_EQUAL_UINT8(f, out.flags);
        TEST_ASSERT_EQUAL(((f & FLAG_COMPRESSED) != 0), out.isCompressed());
        TEST_ASSERT_EQUAL(((f & FLAG_ALERT) != 0), out.isAlert());
    }
}

// Exercises the (fragIndex, fragCount-1) nibble packing across its whole range.
static void test_all_fragment_pairs_roundtrip(void) {
    uint8_t buf[MAX_FRAME];
    for (uint8_t count = 1; count <= MAX_FRAGMENTS; ++count) {
        for (uint8_t idx = 0; idx < count; ++idx) {
            Packet in;
            in.fragIndex = idx;
            in.fragCount = count;
            const size_t n = serialize(in, buf, sizeof(buf));
            TEST_ASSERT_GREATER_THAN_UINT(0, n);
            Packet out;
            TEST_ASSERT_EQUAL(DecodeResult::Ok, deserialize(buf, n, out));
            TEST_ASSERT_EQUAL_UINT8(idx, out.fragIndex);
            TEST_ASSERT_EQUAL_UINT8(count, out.fragCount);
        }
    }
}

static void test_msgid_full_range_roundtrip(void) {
    uint8_t buf[MAX_FRAME];
    for (int id = 0; id <= 255; ++id) {
        Packet in;
        in.msgId = static_cast<uint8_t>(id);
        const size_t n = serialize(in, buf, sizeof(buf));
        Packet out;
        TEST_ASSERT_EQUAL(DecodeResult::Ok, deserialize(buf, n, out));
        TEST_ASSERT_EQUAL_UINT8(id, out.msgId);
    }
}

static void test_control_packet_roundtrip(void) {
    Packet ack = makeControl(PacketType::Ack, 99, 2, 5);
    uint8_t buf[MAX_FRAME];
    const size_t n = serialize(ack, buf, sizeof(buf));
    TEST_ASSERT_EQUAL_UINT(OVERHEAD, n);  // an ACK is exactly 7 bytes on air

    Packet out;
    TEST_ASSERT_EQUAL(DecodeResult::Ok, deserialize(buf, n, out));
    TEST_ASSERT_TRUE(out.isControl());
    TEST_ASSERT_EQUAL_UINT8(99, out.msgId);
    TEST_ASSERT_EQUAL_UINT8(2, out.fragIndex);
    TEST_ASSERT_EQUAL_UINT8(5, out.fragCount);
}

// --- real UTF-8 ------------------------------------------------------------

static void test_corpus_matches_measured_lengths(void) {
    TEST_ASSERT_EQUAL_UINT(corpus::MEASURED_HI, corpus::HI_LEN);
    TEST_ASSERT_EQUAL_UINT(corpus::MEASURED_BN, corpus::BN_LEN);
    TEST_ASSERT_EQUAL_UINT(corpus::MEASURED_GU, corpus::GU_LEN);
    TEST_ASSERT_EQUAL_UINT(corpus::MEASURED_KN, corpus::KN_LEN);
    TEST_ASSERT_EQUAL_UINT(corpus::MEASURED_TA, corpus::TA_LEN);
    TEST_ASSERT_EQUAL_UINT(corpus::MEASURED_EN, corpus::EN_LEN);
    // Tamil is the worst case: 2.95x English.
    TEST_ASSERT_GREATER_THAN_UINT(corpus::EN_LEN * 2, corpus::TA_LEN);
}

static void test_utf8_sentences_roundtrip_byte_exact(void) {
    struct Item { const char* text; size_t len; uint8_t lang; };
    const Item items[] = {
        {corpus::HI, corpus::HI_LEN, 0}, {corpus::BN, corpus::BN_LEN, 1},
        {corpus::GU, corpus::GU_LEN, 2}, {corpus::KN, corpus::KN_LEN, 3},
        {corpus::TA, corpus::TA_LEN, 4}, {corpus::EN, corpus::EN_LEN, 5},
    };
    uint8_t buf[MAX_FRAME];
    for (const Item& it : items) {
        Packet in;
        in.langId = it.lang;
        TEST_ASSERT_TRUE(in.setPayload(reinterpret_cast<const uint8_t*>(it.text), it.len));
        const size_t n = serialize(in, buf, sizeof(buf));
        TEST_ASSERT_EQUAL_UINT(it.len + OVERHEAD, n);

        Packet out;
        TEST_ASSERT_EQUAL(DecodeResult::Ok, deserialize(buf, n, out));
        TEST_ASSERT_EQUAL_UINT8(it.lang, out.langId);
        TEST_ASSERT_EQUAL_UINT(it.len, out.payloadLen);
        TEST_ASSERT_EQUAL_MEMORY(it.text, out.payload, it.len);
    }
}

// --- corruption / CRC gatekeeping -----------------------------------------

// Every single-bit error anywhere in the frame must be caught. CRC-16/CCITT
// has Hamming distance 4 at these lengths, so this is a guarantee, not luck.
static void test_every_single_bit_flip_is_detected(void) {
    Packet in;
    in.langId = 4;
    in.msgId = 77;
    TEST_ASSERT_TRUE(in.setPayload(reinterpret_cast<const uint8_t*>(corpus::TA),
                                   corpus::TA_LEN));
    uint8_t clean[MAX_FRAME];
    const size_t n = serialize(in, clean, sizeof(clean));
    TEST_ASSERT_GREATER_THAN_UINT(0, n);

    uint8_t buf[MAX_FRAME];
    for (size_t byteIdx = 0; byteIdx < n; ++byteIdx) {
        for (int bit = 0; bit < 8; ++bit) {
            std::memcpy(buf, clean, n);
            buf[byteIdx] = static_cast<uint8_t>(buf[byteIdx] ^ (1u << bit));
            Packet out;
            const DecodeResult r = deserialize(buf, n, out);
            if (r == DecodeResult::Ok) {
                char msg[96];
                std::snprintf(msg, sizeof(msg),
                              "undetected flip at byte %u bit %d",
                              static_cast<unsigned>(byteIdx), bit);
                TEST_FAIL_MESSAGE(msg);
            }
        }
    }
}

// The failure mode that motivates this whole design: a payload byte flips into
// a *different but still valid* UTF-8 sequence. TTS would happily speak the
// wrong word. The CRC is the only thing standing in the way.
static void test_corruption_into_valid_utf8_is_caught(void) {
    Packet in;
    TEST_ASSERT_TRUE(in.setPayload(reinterpret_cast<const uint8_t*>(corpus::HI),
                                   corpus::HI_LEN));
    uint8_t buf[MAX_FRAME];
    const size_t n = serialize(in, buf, sizeof(buf));

    // Tamil/Devanagari continuation bytes are 0x80..0xBF; nudging one keeps the
    // sequence structurally valid UTF-8 but changes the character.
    const size_t victim = HEADER_SIZE + 2;
    TEST_ASSERT_TRUE(buf[victim] >= 0x80 && buf[victim] <= 0xBF);
    buf[victim] = static_cast<uint8_t>(buf[victim] + 1);

    Packet out;
    TEST_ASSERT_EQUAL(DecodeResult::CrcMismatch, deserialize(buf, n, out));
}

static void test_burst_corruption_is_caught(void) {
    Packet in;
    TEST_ASSERT_TRUE(in.setPayload(reinterpret_cast<const uint8_t*>(corpus::KN),
                                   corpus::KN_LEN));
    uint8_t clean[MAX_FRAME];
    const size_t n = serialize(in, clean, sizeof(clean));

    uint8_t buf[MAX_FRAME];
    for (size_t start = 0; start + 4 <= n; start += 3) {
        std::memcpy(buf, clean, n);
        for (size_t k = 0; k < 4; ++k) {
            buf[start + k] = static_cast<uint8_t>(buf[start + k] ^ 0xA5);
        }
        Packet out;
        TEST_ASSERT_NOT_EQUAL(DecodeResult::Ok, deserialize(buf, n, out));
    }
}

static void test_truncated_frames_rejected(void) {
    Packet in = makeDataPacket("truncate me");
    uint8_t buf[MAX_FRAME];
    const size_t n = serialize(in, buf, sizeof(buf));

    for (size_t shorter = 0; shorter < n; ++shorter) {
        Packet out;
        TEST_ASSERT_NOT_EQUAL(DecodeResult::Ok, deserialize(buf, shorter, out));
    }
}

static void test_too_short_and_too_long(void) {
    uint8_t buf[MAX_FRAME + 8] = {};
    Packet out;
    for (size_t n = 0; n < OVERHEAD; ++n) {
        TEST_ASSERT_EQUAL(DecodeResult::TooShort, deserialize(buf, n, out));
    }
    TEST_ASSERT_EQUAL(DecodeResult::TooLong, deserialize(buf, MAX_FRAME + 1, out));
    TEST_ASSERT_EQUAL(DecodeResult::TooShort, deserialize(nullptr, 32, out));
}

// A CRC-clean frame whose declared payloadLen disagrees with the buffer we were
// handed. Catches a caller passing a stale length.
static void test_length_mismatch_detected(void) {
    Packet in = makeDataPacket("0123456789");
    uint8_t buf[MAX_FRAME];
    const size_t n = serialize(in, buf, sizeof(buf));

    buf[4] = 9;  // lie about the payload length
    refreshCrc(buf, n);

    Packet out;
    TEST_ASSERT_EQUAL(DecodeResult::LengthMismatch, deserialize(buf, n, out));
}

static void test_bad_version_detected(void) {
    Packet in = makeDataPacket("hi");
    uint8_t buf[MAX_FRAME];
    const size_t n = serialize(in, buf, sizeof(buf));

    buf[0] = static_cast<uint8_t>((buf[0] & 0x3F) | (2u << 6));  // version 2
    refreshCrc(buf, n);

    Packet out;
    TEST_ASSERT_EQUAL(DecodeResult::BadVersion, deserialize(buf, n, out));
}

static void test_bad_fragmentation_detected(void) {
    Packet in = makeDataPacket("hi");
    uint8_t buf[MAX_FRAME];
    const size_t n = serialize(in, buf, sizeof(buf));

    buf[3] = static_cast<uint8_t>((5u << 4) | (3u - 1u));  // index 5 of 3
    refreshCrc(buf, n);

    Packet out;
    TEST_ASSERT_EQUAL(DecodeResult::BadFragmentation, deserialize(buf, n, out));
}

// --- serialize input validation -------------------------------------------

static void test_serialize_rejects_invalid_packets(void) {
    uint8_t buf[MAX_FRAME];

    Packet badLang;
    badLang.langId = 16;
    TEST_ASSERT_EQUAL_UINT(0, serialize(badLang, buf, sizeof(buf)));

    Packet zeroCount;
    zeroCount.fragCount = 0;
    TEST_ASSERT_EQUAL_UINT(0, serialize(zeroCount, buf, sizeof(buf)));

    Packet tooManyFrags;
    tooManyFrags.fragCount = MAX_FRAGMENTS + 1;
    TEST_ASSERT_EQUAL_UINT(0, serialize(tooManyFrags, buf, sizeof(buf)));

    Packet idxOutOfRange;
    idxOutOfRange.fragIndex = 3;
    idxOutOfRange.fragCount = 3;
    TEST_ASSERT_EQUAL_UINT(0, serialize(idxOutOfRange, buf, sizeof(buf)));

    Packet reservedBits;
    reservedBits.flags = 0x80;
    TEST_ASSERT_EQUAL_UINT(0, serialize(reservedBits, buf, sizeof(buf)));

    Packet badVersion;
    badVersion.version = 4;
    TEST_ASSERT_EQUAL_UINT(0, serialize(badVersion, buf, sizeof(buf)));

    Packet ok;
    TEST_ASSERT_EQUAL_UINT(0, serialize(ok, nullptr, 100));
}

static void test_serialize_respects_output_capacity(void) {
    Packet in = makeDataPacket("abcdefgh");
    uint8_t buf[MAX_FRAME];
    const size_t need = OVERHEAD + 8;
    TEST_ASSERT_EQUAL_UINT(0, serialize(in, buf, need - 1));
    TEST_ASSERT_EQUAL_UINT(need, serialize(in, buf, need));
}

// Sanity: nothing is written past the reported length.
static void test_serialize_does_not_overrun(void) {
    Packet in = makeDataPacket("guard");
    uint8_t buf[MAX_FRAME];
    std::memset(buf, 0xEE, sizeof(buf));
    const size_t n = serialize(in, buf, sizeof(buf));
    for (size_t i = n; i < sizeof(buf); ++i) {
        TEST_ASSERT_EQUAL_UINT8(0xEE, buf[i]);
    }
}

// --- network id / sync word ------------------------------------------------

// The sync word is the PHY-level filter; if it is left at a default we share a
// channel with every other team at the venue.
static void test_sync_word_is_not_a_default(void) {
    TEST_ASSERT_NOT_EQUAL(0x12, LORA_SYNC_WORD);  // SX126x private default
    TEST_ASSERT_NOT_EQUAL(0x34, LORA_SYNC_WORD);  // LoRaWAN public
    TEST_ASSERT_EQUAL_HEX8(0x26, LORA_SYNC_WORD);
}

static void test_network_id_roundtrips_and_leaves_flags_clean(void) {
    Packet in = makeDataPacket("net");
    in.setCompressed(true);
    in.setAlert(true);
    uint8_t buf[MAX_FRAME];
    const size_t n = serialize(in, buf, sizeof(buf));
    TEST_ASSERT_GREATER_THAN_UINT(0, n);

    // network id occupies flags bits 2-3 on the wire
    TEST_ASSERT_EQUAL_UINT8(NETWORK_ID,
                            (buf[1] & FLAG_NETWORK_MASK) >> FLAG_NETWORK_SHIFT);

    Packet out;
    TEST_ASSERT_EQUAL(DecodeResult::Ok, deserialize(buf, n, out));
    TEST_ASSERT_EQUAL_UINT8(NETWORK_ID, out.networkId);
    // flags must come back carrying only the two user bits
    TEST_ASSERT_EQUAL_UINT8(FLAG_COMPRESSED | FLAG_ALERT, out.flags);
    TEST_ASSERT_EQUAL_UINT8(0, out.flags & FLAG_NETWORK_MASK);
}

// A neighbouring pair that happens to share our sync word must still be
// rejected - this is the second layer doing its job.
static void test_foreign_network_is_rejected(void) {
    Packet in = makeDataPacket("not for us");
    uint8_t buf[MAX_FRAME];
    const size_t n = serialize(in, buf, sizeof(buf));

    for (uint8_t other = 0; other <= MAX_NETWORK_ID; ++other) {
        if (other == NETWORK_ID) continue;
        uint8_t tampered[MAX_FRAME];
        std::memcpy(tampered, buf, n);
        tampered[1] = static_cast<uint8_t>((tampered[1] & ~FLAG_NETWORK_MASK) |
                                           (other << FLAG_NETWORK_SHIFT));
        refreshCrc(tampered, n);  // a genuinely valid frame from another network
        Packet out;
        TEST_ASSERT_EQUAL(DecodeResult::WrongNetwork, deserialize(tampered, n, out));
    }
}

static void test_serialize_rejects_out_of_range_network_id(void) {
    uint8_t buf[MAX_FRAME];
    Packet p;
    p.networkId = MAX_NETWORK_ID + 1;
    TEST_ASSERT_EQUAL_UINT(0, serialize(p, buf, sizeof(buf)));
}

int main(void) {
    UNITY_BEGIN();
    RUN_TEST(test_crc_known_answer);
    RUN_TEST(test_crc_incremental_matches_oneshot);
    RUN_TEST(test_crc_empty_input);
    RUN_TEST(test_overhead_is_seven_bytes);
    RUN_TEST(test_serialized_size_is_payload_plus_overhead);
    RUN_TEST(test_roundtrip_basic);
    RUN_TEST(test_roundtrip_empty_payload);
    RUN_TEST(test_roundtrip_max_payload);
    RUN_TEST(test_payload_over_max_is_rejected);
    RUN_TEST(test_all_language_ids_roundtrip);
    RUN_TEST(test_all_packet_types_roundtrip);
    RUN_TEST(test_all_flag_combinations_roundtrip);
    RUN_TEST(test_all_fragment_pairs_roundtrip);
    RUN_TEST(test_msgid_full_range_roundtrip);
    RUN_TEST(test_control_packet_roundtrip);
    RUN_TEST(test_corpus_matches_measured_lengths);
    RUN_TEST(test_utf8_sentences_roundtrip_byte_exact);
    RUN_TEST(test_every_single_bit_flip_is_detected);
    RUN_TEST(test_corruption_into_valid_utf8_is_caught);
    RUN_TEST(test_burst_corruption_is_caught);
    RUN_TEST(test_truncated_frames_rejected);
    RUN_TEST(test_too_short_and_too_long);
    RUN_TEST(test_length_mismatch_detected);
    RUN_TEST(test_bad_version_detected);
    RUN_TEST(test_bad_fragmentation_detected);
    RUN_TEST(test_serialize_rejects_invalid_packets);
    RUN_TEST(test_serialize_respects_output_capacity);
    RUN_TEST(test_serialize_does_not_overrun);
    RUN_TEST(test_sync_word_is_not_a_default);
    RUN_TEST(test_network_id_roundtrips_and_leaves_flags_clean);
    RUN_TEST(test_foreign_network_is_rejected);
    RUN_TEST(test_serialize_rejects_out_of_range_network_id);
    return UNITY_END();
}
