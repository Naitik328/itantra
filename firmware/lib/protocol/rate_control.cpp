#include "rate_control.h"

#include <cmath>
#include <cstring>

namespace lorax {

namespace {

// SX1262 demodulation floors, datasheet typical. Each SF step is 2.5 dB.
constexpr RungConfig kLadder[RUNG_COUNT] = {
    {7,  250000, 5,  -7.5f, "FAST"},
    {9,  125000, 5, -12.5f, "MEDIUM"},
    {10, 125000, 5, -15.0f, "FAR"},
    {12, 125000, 5, -20.0f, "MAX"},
};

bool sameName(const char* a, const char* b) {
    for (size_t i = 0;; ++i) {
        char ca = a[i];
        char cb = b[i];
        if (ca >= 'a' && ca <= 'z') ca = static_cast<char>(ca - 32);
        if (cb >= 'a' && cb <= 'z') cb = static_cast<char>(cb - 32);
        if (ca != cb) return false;
        if (ca == '\0') return true;
    }
}

}  // namespace

const RungConfig& rungConfig(Rung r) {
    const uint8_t i = static_cast<uint8_t>(r);
    return kLadder[i < RUNG_COUNT ? i : RUNG_COUNT - 1];
}

const char* rungName(Rung r) { return rungConfig(r).name; }

bool rungFromName(const char* s, Rung& out) {
    if (s == nullptr) return false;
    for (uint8_t i = 0; i < RUNG_COUNT; ++i) {
        if (sameName(s, kLadder[i].name)) {
            out = static_cast<Rung>(i);
            return true;
        }
    }
    return false;
}

Rung faster(Rung r) {
    const uint8_t i = static_cast<uint8_t>(r);
    return i == 0 ? r : static_cast<Rung>(i - 1);
}

Rung moreRobust(Rung r) {
    const uint8_t i = static_cast<uint8_t>(r);
    return i >= RUNG_COUNT - 1 ? r : static_cast<Rung>(i + 1);
}

bool isFastest(Rung r)    { return static_cast<uint8_t>(r) == 0; }
bool isMostRobust(Rung r) { return static_cast<uint8_t>(r) == RUNG_COUNT - 1; }

float stepUpThresholdTo(Rung from, Rung to, float fadeMarginDb) {
    if (static_cast<uint8_t>(to) >= static_cast<uint8_t>(from)) return -1e9f;
    const RungConfig& cur = rungConfig(from);
    const RungConfig& tgt = rungConfig(to);
    // Widening the bandwidth raises the noise floor, so the SNR we measure
    // drops the instant we switch. Pay for that up front.
    const float bwPenalty = 10.0f * std::log10(static_cast<float>(tgt.bandwidthHz) /
                                               static_cast<float>(cur.bandwidthHz));
    return tgt.requiredSnrDb + bwPenalty + fadeMarginDb;
}

float stepUpThresholdDb(Rung from, float fadeMarginDb) {
    if (isFastest(from)) return 1e9f;  // nowhere faster to go
    return stepUpThresholdTo(from, faster(from), fadeMarginDb);
}

Rung bestRungFor(Rung from, float snrDb, float fadeMarginDb, uint8_t maxJump) {
    Rung best = from;
    Rung candidate = from;
    for (uint8_t step = 0; step < maxJump; ++step) {
        if (isFastest(candidate)) break;
        candidate = faster(candidate);
        // Monotonic in target, so the first failure ends the search.
        if (snrDb < stepUpThresholdTo(from, candidate, fadeMarginDb)) break;
        best = candidate;
    }
    return best;
}

float stepDownThresholdDb(Rung at, float marginDb) {
    return rungConfig(at).requiredSnrDb + marginDb;
}

int8_t encodeSnr(float snrDb) {
    float q = snrDb * 4.0f;
    if (q > 127.0f) q = 127.0f;
    if (q < -128.0f) q = -128.0f;
    return static_cast<int8_t>(q >= 0.0f ? q + 0.5f : q - 0.5f);
}

float decodeSnr(int8_t raw) { return static_cast<float>(raw) / 4.0f; }

size_t encodeRateMessage(const RateMessage& m, uint8_t* out, size_t cap) {
    if (out == nullptr || cap < RATE_MESSAGE_BYTES) return 0;
    out[0] = static_cast<uint8_t>(m.opcode);
    out[1] = static_cast<uint8_t>(m.snr);
    out[2] = static_cast<uint8_t>(m.rung);
    return RATE_MESSAGE_BYTES;
}

bool decodeRateMessage(const uint8_t* in, size_t len, RateMessage& out) {
    if (in == nullptr || len < RATE_MESSAGE_BYTES) return false;
    if (in[0] > static_cast<uint8_t>(RateOpcode::Accept)) return false;
    if (in[2] >= RUNG_COUNT) return false;
    out.opcode = static_cast<RateOpcode>(in[0]);
    out.snr    = static_cast<int8_t>(in[1]);
    out.rung   = static_cast<Rung>(in[2]);
    return true;
}

// ---------------------------------------------------------------------------

RungNegotiator::Step RungNegotiator::propose(Rung target, uint32_t nowMs) {
    Step s;
    if (state_ != State::Idle) return s;   // one handshake at a time
    state_     = State::AwaitingAccept;
    target_    = target;
    startedMs_ = nowMs;
    s.action = Action::SendPropose;
    s.rung   = target;
    return s;
}

RungNegotiator::Step RungNegotiator::onPropose(Rung target, uint32_t nowMs) {
    Step s;
    // An incoming proposal wins over one of ours still awaiting a reply: the
    // peer has evidence we do not, and both ends disagreeing means silence.
    if (state_ == State::SwitchAfterTx) return s;
    state_     = State::SwitchAfterTx;
    target_    = target;
    startedMs_ = nowMs;
    s.action = Action::SendAccept;
    s.rung   = target;
    return s;
}

RungNegotiator::Step RungNegotiator::onAccept(Rung target, uint32_t nowMs) {
    Step s;
    // Only the proposer acts, and only on the rung it actually asked for.
    // Anything else is stale or crossed in flight.
    if (state_ != State::AwaitingAccept || target != target_) return s;
    (void)nowMs;
    state_ = State::Idle;
    s.action = Action::ApplyRung;
    s.rung   = target_;
    return s;
}

RungNegotiator::Step RungNegotiator::onTxIdle(uint32_t nowMs) {
    Step s;
    // ONLY the accepter switches here. The proposer transmitting its proposal
    // means nothing - the peer may never have heard it.
    if (state_ != State::SwitchAfterTx) return s;
    (void)nowMs;
    state_ = State::Idle;
    s.action = Action::ApplyRung;
    s.rung   = target_;
    return s;
}

RungNegotiator::Step RungNegotiator::onTimeout(uint32_t nowMs, uint32_t timeoutMs) {
    Step s;
    if (state_ != State::AwaitingAccept) return s;
    if (static_cast<uint32_t>(nowMs - startedMs_) <= timeoutMs) return s;
    state_ = State::Idle;   // stay where we are; no change is the safe outcome
    return s;
}

void RungNegotiator::reset() { state_ = State::Idle; }

// ---------------------------------------------------------------------------

RateController::RateController(Rung start) : current_(start) {}

void RateController::begin(uint32_t nowMs) {
    lastFrameMs_ = nowMs;
}

uint32_t RateController::silentForMs(uint32_t nowMs) const {
    if (lastFrameMs_ == 0) return 0;
    return static_cast<uint32_t>(nowMs - lastFrameMs_);
}

void RateController::onGoodFrame(float snrDb, uint32_t nowMs) {
    lastLocalSnr_ = snrDb;
    lastFrameMs_  = nowMs;

    const float snr = effectiveSnrDb(nowMs);

    if (snr < stepDownThresholdDb(current_, params_.stepDownMarginDb)) {
        // Decoded, but marginally. Retreat before it stops decoding.
        retreatRequested_ = true;
        consecutiveGood_  = 0;
        return;
    }

    if (!isFastest(current_) &&
        snr >= stepUpThresholdDb(current_, params_.fadeMarginDb)) {
        // Track the WORST sample in the streak, not the latest: the jump is
        // sized from evidence that held for the whole confirmation window.
        if (consecutiveGood_ == 0 || snr < minSnrInStreak_) minSnrInStreak_ = snr;
        if (consecutiveGood_ < 255) ++consecutiveGood_;
    } else {
        consecutiveGood_ = 0;
    }
}

void RateController::onBadFrame(uint32_t nowMs) {
    (void)nowMs;
    // One failure is enough. Silence is worse than slowness.
    retreatRequested_ = true;
    consecutiveGood_  = 0;
}

void RateController::onPeerReportedSnr(float snrDb, uint32_t nowMs) {
    lastPeerSnr_   = snrDb;
    lastPeerSnrMs_ = nowMs;
    peerSnrValid_  = true;
}

float RateController::effectiveSnrDb(uint32_t nowMs) const {
    if (!peerSnrValid_) return lastLocalSnr_;
    if (static_cast<uint32_t>(nowMs - lastPeerSnrMs_) > params_.peerSnrValidMs) {
        return lastLocalSnr_;
    }
    return lastPeerSnr_ < lastLocalSnr_ ? lastPeerSnr_ : lastLocalSnr_;
}

RateController::Decision RateController::evaluate(uint32_t nowMs) const {
    Decision d;
    d.target = current_;
    if (!adaptive_) return d;

    // Silence watchdog runs FIRST and ignores the post-change hold: if we have
    // gone quiet, nothing else matters. Both nodes do this independently, which
    // is what lets them reconverge without talking to each other.
    if (lastFrameMs_ != 0) {
        const uint32_t quiet = static_cast<uint32_t>(nowMs - lastFrameMs_);
        if (quiet >= params_.deepRendezvousMs && current_ != params_.deepAnchor) {
            d.change = true;
            d.target = params_.deepAnchor;
            d.reason = "deep rendezvous: prolonged silence";
            d.unilateral = true;
            d.observedSilenceMs = quiet;
            return d;
        }
        if (quiet >= params_.silenceMs && current_ != params_.anchor &&
            current_ != params_.deepAnchor) {
            d.change = true;
            d.target = params_.anchor;
            d.reason = "silence watchdog: falling back to anchor";
            d.unilateral = true;
            d.observedSilenceMs = quiet;
            return d;
        }
    }

    // Hold-off stops oscillation across a threshold right after a change.
    if (lastChangeMs_ != 0 &&
        static_cast<uint32_t>(nowMs - lastChangeMs_) < params_.holdAfterChangeMs) {
        return d;
    }

    if (retreatRequested_ && !isMostRobust(current_)) {
        d.change = true;
        d.target = moreRobust(current_);
        d.reason = "link degraded: retreating one rung";
        return d;
    }

    // Withhold promotion while an urgent message is queued. Retreat above is
    // deliberately NOT gated: making the link more robust is always allowed.
    if (urgentPending_) return d;

    if (consecutiveGood_ >= params_.stepUpConsecutive && !isFastest(current_)) {
        // One confirmation cycle buys the rung the margin actually supports,
        // capped, rather than a single step. Retreat stays asymmetric: a wrong
        // jump upward is a broken link, a wrong retreat is only slow.
        const Rung target = bestRungFor(current_, minSnrInStreak_,
                                        params_.fadeMarginDb, params_.maxRungJump);
        if (target != current_) {
            d.change = true;
            d.target = target;
            d.reason = "sustained margin: promoting to the supported rung";
        }
        return d;
    }
    return d;
}

void RateController::applied(Rung r, uint32_t nowMs) {
    current_          = r;
    lastChangeMs_     = nowMs;
    consecutiveGood_  = 0;
    retreatRequested_ = false;
    // A fresh rung has told us nothing yet; do not carry stale evidence across.
    peerSnrValid_     = false;
}

void RateController::setAdaptive(bool on) {
    adaptive_ = on;
    if (on) {
        consecutiveGood_  = 0;
        retreatRequested_ = false;
    }
}

void RateController::forceRung(Rung r, uint32_t nowMs) {
    adaptive_ = false;
    applied(r, nowMs);
}

}  // namespace lorax
