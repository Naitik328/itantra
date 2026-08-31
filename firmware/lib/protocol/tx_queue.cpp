#include "tx_queue.h"

namespace lorax {

const char* TxQueue::admitResultName(AdmitResult r) {
    switch (r) {
        case AdmitResult::Accepted:      return "Accepted";
        case AdmitResult::EvictedQueued: return "EvictedQueued";
        case AdmitResult::Full:          return "Full";
    }
    return "Unknown";
}

uint8_t TxQueue::pick() const {
    uint8_t best = SLOTS;
    // Pass 1: alerts, oldest first.
    for (uint8_t i = 0; i < SLOTS; ++i) {
        if (!slots_[i].busy || !slots_[i].alert) continue;
        if (best == SLOTS || slots_[i].seq < slots_[best].seq) best = i;
    }
    if (best != SLOTS) return best;
    // Pass 2: everything else, oldest first.
    for (uint8_t i = 0; i < SLOTS; ++i) {
        if (!slots_[i].busy) continue;
        if (best == SLOTS || slots_[i].seq < slots_[best].seq) best = i;
    }
    return best;
}

TxQueue::AdmitResult TxQueue::admit(const Packet* frags, uint8_t count, bool alert) {
    if (frags == nullptr || count == 0 || count > MAX_FRAGMENTS) {
        return AdmitResult::Full;
    }

    uint8_t target = SLOTS;
    AdmitResult result = AdmitResult::Accepted;

    for (uint8_t i = 0; i < SLOTS; ++i) {
        if (!slots_[i].busy) {
            target = i;
            break;
        }
    }

    if (target == SLOTS && alert) {
        // No room, but an ALERT must not be lost. Displace a NORMAL that has
        // not yet put a fragment on the air - evicting one that HAS started
        // would strand fragments the peer is already holding.
        for (uint8_t i = 0; i < SLOTS; ++i) {
            if (slots_[i].busy && !slots_[i].alert && slots_[i].next == 0) {
                target = i;
                result = AdmitResult::EvictedQueued;
                break;
            }
        }
    }

    if (target == SLOTS) return AdmitResult::Full;

    Slot& s = slots_[target];
    for (uint8_t i = 0; i < count; ++i) s.frags[i] = frags[i];
    s.count = count;
    s.next  = 0;
    s.alert = alert;
    s.busy  = true;
    s.seq   = nextSeq_++;
    return result;
}

const Packet* TxQueue::peek() const {
    const uint8_t i = pick();
    if (i == SLOTS) return nullptr;
    return &slots_[i].frags[slots_[i].next];
}

void TxQueue::advance() {
    const uint8_t i = pick();
    if (i == SLOTS) return;
    Slot& s = slots_[i];
    if (++s.next >= s.count) {
        s.busy = false;
        s.next = 0;
        s.count = 0;
    }
}

bool TxQueue::empty() const { return pick() == SLOTS; }

bool TxQueue::alertPending() const {
    for (uint8_t i = 0; i < SLOTS; ++i) {
        if (slots_[i].busy && slots_[i].alert) return true;
    }
    return false;
}

uint8_t TxQueue::queuedMessages() const {
    uint8_t n = 0;
    for (uint8_t i = 0; i < SLOTS; ++i) {
        if (slots_[i].busy) ++n;
    }
    return n;
}

bool TxQueue::inFlight() const {
    for (uint8_t i = 0; i < SLOTS; ++i) {
        if (slots_[i].busy && slots_[i].next > 0) return true;
    }
    return false;
}

void TxQueue::reset() {
    for (uint8_t i = 0; i < SLOTS; ++i) {
        slots_[i].busy = false;
        slots_[i].next = 0;
        slots_[i].count = 0;
    }
    nextSeq_ = 1;
}

}  // namespace lorax
