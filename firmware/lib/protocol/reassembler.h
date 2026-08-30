// Reassembly of fragmented messages.
//
// Handles out-of-order arrival, duplicate fragments, duplicate *messages*
// (a peer retransmitting because our ACK was lost), msgId reuse, and
// abandonment of half-received messages via a timeout.
//
// Contract: a message is emitted only when EVERY fragment has arrived. Partial
// text is never handed upward, because downstream TTS cannot stream and would
// happily speak a truncated sentence without complaint.
//
// MEMORY: this object holds a fixed fragment store and is ~16 KB. Declare it
// as a global/static or a member of one - never on a stack.
//   SLOTS * MAX_FRAGMENTS * MAX_PAYLOAD  = 3 * 16 * 248 = 11904 B
//   assembly buffer                      =     16 * 248 =  3968 B

#pragma once

#include "packet.h"

namespace lorax {

class Reassembler {
public:
    // Concurrent partial messages tracked. Two half-duplex nodes rarely have
    // more than one in flight; 3 covers a retransmit overlapping a new message.
    static constexpr size_t   SLOTS              = 3;
    static constexpr size_t   MAX_MESSAGE_BYTES  = MAX_FRAGMENTS * MAX_PAYLOAD;
    static constexpr size_t   RECENT_HISTORY     = 8;

    // 30 s. Worst realistic case is Tamil (239 B) at SF10 in 2 fragments
    // (~3.2 s) plus retries; this leaves roughly 3x margin.
    static constexpr uint32_t DEFAULT_TIMEOUT_MS = 30000;

    enum class Status : uint8_t {
        Incomplete,  // stored, still waiting on other fragments
        Complete,    // message finished; `data`/`len` are valid
        Duplicate,   // fragment (or whole message) already seen; ignored
        Rejected,    // malformed or not a data packet
    };

    struct Result {
        Status         status = Status::Incomplete;
        uint8_t        msgId  = 0;
        uint8_t        langId = 0;
        uint8_t        flags  = 0;
        uint8_t        appType = 0;
        const uint8_t* data   = nullptr;  // valid until the next completing offer()
        size_t         len    = 0;

        bool compressed() const { return (flags & FLAG_COMPRESSED) != 0; }
        bool alert()      const { return (flags & FLAG_ALERT) != 0; }
    };

    explicit Reassembler(uint32_t timeoutMs = DEFAULT_TIMEOUT_MS);

    // Feed one received data fragment. `nowMs` is a monotonic millisecond clock
    // (millis() on-target); it drives the timeout only.
    Result offer(const Packet& p, uint32_t nowMs);

    // Drop partial messages that have gone quiet. Returns how many were freed.
    // Call periodically; offer() also evicts lazily.
    size_t evictExpired(uint32_t nowMs);

    void reset();

    size_t activeSlots() const;
    uint32_t timeoutMs() const { return timeoutMs_; }
    void setTimeoutMs(uint32_t ms) { timeoutMs_ = ms; }

private:
    struct Slot {
        bool     inUse = false;
        uint8_t  msgId = 0;
        uint8_t  langId = 0;
        uint8_t  flags = 0;
        uint8_t  appType = 0;
        uint8_t  fragCount = 0;
        uint16_t receivedMask = 0;  // one bit per fragment; MAX_FRAGMENTS == 16
        uint32_t lastActivityMs = 0;
        uint8_t  fragLen[MAX_FRAGMENTS] = {};
        uint8_t  frag[MAX_FRAGMENTS][MAX_PAYLOAD] = {};
    };

    struct RecentEntry {
        bool     valid = false;
        uint8_t  msgId = 0;
        uint32_t completedMs = 0;
    };

    Slot* findSlot(uint8_t msgId);
    Slot* claimSlot(uint8_t msgId, uint32_t nowMs);
    bool  recentlyCompleted(uint8_t msgId, uint32_t nowMs) const;
    void  markCompleted(uint8_t msgId, uint32_t nowMs);
    static bool expired(uint32_t lastMs, uint32_t nowMs, uint32_t timeoutMs);

    uint32_t    timeoutMs_;
    Slot        slots_[SLOTS];
    RecentEntry recent_[RECENT_HISTORY];
    size_t      recentNext_ = 0;
    uint8_t     assembled_[MAX_MESSAGE_BYTES] = {};
};

}  // namespace lorax
