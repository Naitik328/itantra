#include "reassembler.h"

#include <cstring>

namespace lorax {

Reassembler::Reassembler(uint32_t timeoutMs) : timeoutMs_(timeoutMs) {}

// Unsigned subtraction, so this stays correct across the ~49.7 day wrap of
// Arduino's millis(). Never compare timestamps with `<` directly.
bool Reassembler::expired(uint32_t lastMs, uint32_t nowMs, uint32_t timeoutMs) {
    return static_cast<uint32_t>(nowMs - lastMs) >= timeoutMs;
}

void Reassembler::reset() {
    for (auto& s : slots_) {
        s.inUse = false;
        s.receivedMask = 0;
    }
    for (auto& r : recent_) {
        r.valid = false;
    }
    recentNext_ = 0;
}

size_t Reassembler::activeSlots() const {
    size_t n = 0;
    for (const auto& s : slots_) {
        if (s.inUse) {
            ++n;
        }
    }
    return n;
}

size_t Reassembler::evictExpired(uint32_t nowMs) {
    size_t freed = 0;
    for (auto& s : slots_) {
        if (s.inUse && expired(s.lastActivityMs, nowMs, timeoutMs_)) {
            s.inUse = false;
            s.receivedMask = 0;
            ++freed;
        }
    }
    return freed;
}

Reassembler::Slot* Reassembler::findSlot(uint8_t msgId) {
    for (auto& s : slots_) {
        if (s.inUse && s.msgId == msgId) {
            return &s;
        }
    }
    return nullptr;
}

Reassembler::Slot* Reassembler::claimSlot(uint8_t msgId, uint32_t nowMs) {
    for (auto& s : slots_) {
        if (!s.inUse) {
            s.inUse = true;
            s.msgId = msgId;
            s.receivedMask = 0;
            s.lastActivityMs = nowMs;
            return &s;
        }
    }
    // All slots busy: evict the least recently touched. It is the one most
    // likely to have been abandoned, and dropping the *incoming* fragment
    // instead would stall the newest message indefinitely.
    Slot* oldest = &slots_[0];
    for (auto& s : slots_) {
        if (static_cast<uint32_t>(nowMs - s.lastActivityMs) >
            static_cast<uint32_t>(nowMs - oldest->lastActivityMs)) {
            oldest = &s;
        }
    }
    oldest->inUse = true;
    oldest->msgId = msgId;
    oldest->receivedMask = 0;
    oldest->lastActivityMs = nowMs;
    return oldest;
}

bool Reassembler::recentlyCompleted(uint8_t msgId, uint32_t nowMs) const {
    for (const auto& r : recent_) {
        if (r.valid && r.msgId == msgId &&
            !expired(r.completedMs, nowMs, timeoutMs_)) {
            return true;
        }
    }
    return false;
}

void Reassembler::markCompleted(uint8_t msgId, uint32_t nowMs) {
    RecentEntry& e = recent_[recentNext_];
    e.valid = true;
    e.msgId = msgId;
    e.completedMs = nowMs;
    recentNext_ = (recentNext_ + 1) % RECENT_HISTORY;
}

Reassembler::Result Reassembler::offer(const Packet& p, uint32_t nowMs) {
    Result r;

    // Control frames carry no text and are handled by the ARQ layer, not here.
    if (p.type != PacketType::Data) {
        r.status = Status::Rejected;
        return r;
    }
    if (p.fragCount == 0 || p.fragCount > MAX_FRAGMENTS ||
        p.fragIndex >= p.fragCount || p.payloadLen > MAX_PAYLOAD) {
        r.status = Status::Rejected;
        return r;
    }

    r.msgId = p.msgId;

    // Free anything stale first, so a long-abandoned message cannot hold a slot
    // hostage or collide with a reused msgId.
    evictExpired(nowMs);

    // The peer retransmits when it misses our ACK. Absorb those silently:
    // speaking the same emergency sentence twice is a real failure mode.
    if (recentlyCompleted(p.msgId, nowMs)) {
        r.status = Status::Duplicate;
        return r;
    }

    Slot* slot = findSlot(p.msgId);
    if (slot != nullptr && slot->fragCount != p.fragCount) {
        // Same msgId, different fragment count: the id has been reused for a
        // new message. Throw away what we had rather than blend two messages.
        slot->receivedMask = 0;
        slot->fragCount = p.fragCount;
        slot->langId = p.langId;
        slot->flags = p.flags;
        slot->appType = p.appType;
    }
    if (slot == nullptr) {
        slot = claimSlot(p.msgId, nowMs);
        slot->fragCount = p.fragCount;
        slot->langId = p.langId;
        slot->flags = p.flags;
        slot->appType = p.appType;
    }

    const uint16_t bit = static_cast<uint16_t>(1u << p.fragIndex);
    if ((slot->receivedMask & bit) != 0) {
        slot->lastActivityMs = nowMs;
        r.status = Status::Duplicate;
        return r;
    }

    slot->fragLen[p.fragIndex] = p.payloadLen;
    if (p.payloadLen > 0) {
        std::memcpy(slot->frag[p.fragIndex], p.payload, p.payloadLen);
    }
    slot->receivedMask = static_cast<uint16_t>(slot->receivedMask | bit);
    slot->lastActivityMs = nowMs;

    // All bits set for this fragment count?
    const uint16_t full = (slot->fragCount >= 16)
                              ? 0xFFFFu
                              : static_cast<uint16_t>((1u << slot->fragCount) - 1u);
    if (slot->receivedMask != full) {
        r.status = Status::Incomplete;
        return r;
    }

    size_t offset = 0;
    for (uint8_t i = 0; i < slot->fragCount; ++i) {
        const uint8_t n = slot->fragLen[i];
        if (n > 0) {
            std::memcpy(assembled_ + offset, slot->frag[i], n);
            offset += n;
        }
    }

    r.status = Status::Complete;
    r.langId  = slot->langId;
    r.flags   = slot->flags;
    r.appType = slot->appType;
    r.data   = assembled_;
    r.len    = offset;

    slot->inUse = false;
    slot->receivedMask = 0;
    markCompleted(p.msgId, nowMs);
    return r;
}

}  // namespace lorax
