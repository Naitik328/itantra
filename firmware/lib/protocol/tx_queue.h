// Outbound message queue with ALERT priority.
//
// Exists because of a real defect: `sendText()` used to refuse any message
// while one was in flight, and the BLE adapter logged and dropped it. An ALERT
// raised during a NORMAL transmission simply vanished - the same silent-loss
// failure class the CRC strategy exists to prevent, except it happened before
// the radio was involved so none of those defences could catch it.
//
// PURE LOGIC, no Arduino, no radio. The scheduling rule is the part that must
// not regress, so it lives where a test can reach it.
//
// ===========================================================================
// PREEMPTION HAPPENS AT FRAGMENT BOUNDARIES
// ===========================================================================
//
// peek() re-decides which slot to serve before every fragment, so an ALERT
// admitted mid-message takes over at the next fragment boundary and the NORMAL
// resumes afterwards. Nothing is aborted and nothing is destroyed.
//
// Worst-case ALERT delay, full-size message, 2200 ms per-fragment budget:
//
//              queue behind      preempt at        preempt
//              whole message     frag boundary     mid-fragment
//   FAST            200 ms           200 ms           ~0 ms
//   MEDIUM         1250 ms          1250 ms           ~0 ms
//   FAR            2503 ms          2173 ms           ~0 ms
//   MAX           14475 ms          2138 ms           ~0 ms
//
// Fragment-boundary preemption is bounded by the per-fragment airtime budget
// at EVERY rung, because that budget is what sized the fragments in the first
// place. Aborting mid-fragment would buy at most ~2.2 s more, and would cost
// the in-flight message entirely - there is no ARQ to recover it.
//
// Interleaving is safe by construction: the reassembler is keyed by msgId,
// tolerates out-of-order arrival, and holds three slots. Fragments of two
// messages arriving interleaved is exactly what it was built for.

#pragma once

#include <cstdint>

#include "packet.h"

namespace lorax {

class TxQueue {
public:
    // One in flight plus one waiting. A third concurrent message is refused,
    // and that refusal is reported rather than swallowed.
    static constexpr uint8_t SLOTS = 2;

    enum class AdmitResult : uint8_t {
        Accepted = 0,
        EvictedQueued,  // an ALERT displaced a NORMAL that had not started
        Full,           // caller MUST surface this; never drop silently
    };

    static const char* admitResultName(AdmitResult r);

    // Takes an already-fragmented message. Copies it - the caller's array can
    // be reused immediately.
    AdmitResult admit(const Packet* frags, uint8_t count, bool alert);

    // Next fragment to transmit, or nullptr when idle. Re-decided on every
    // call, which is what produces fragment-boundary preemption.
    const Packet* peek() const;

    // Call once the fragment from peek() has been handed to the radio.
    void advance();

    bool    empty() const;
    bool    alertPending() const;
    uint8_t queuedMessages() const;
    // True once any slot has transmitted at least one fragment.
    bool    inFlight() const;
    void    reset();

private:
    struct Slot {
        Packet   frags[MAX_FRAGMENTS];
        uint8_t  count = 0;
        uint8_t  next  = 0;
        bool     alert = false;
        bool     busy  = false;
        uint32_t seq   = 0;
    };

    // ALERT slots first, then oldest admitted. Returns SLOTS when idle.
    uint8_t pick() const;

    Slot     slots_[SLOTS];
    uint32_t nextSeq_ = 1;
};

}  // namespace lorax
