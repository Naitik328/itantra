// Adaptive spreading-factor / bandwidth control.
//
// PURE LOGIC. No Arduino, no RadioLib, no I/O. Observations go in, decisions
// come out; the caller applies them. This is deliberate: testing adaptation on
// hardware means physically walking away from a board, and every threshold
// tweak would mean walking again. All of it is exercised natively instead.
//
// ===========================================================================
// DIRECTION - the thing that is easy to get backwards
// ===========================================================================
//
//   Lower SF  -> faster, shorter range      Higher SF -> slower, longer range
//   WIDER BW  -> faster, shorter range      NARROWER BW -> slower, longer range
//
// Both dials move together. The ladder is ordered fastest-first, so a LOWER
// index is faster and more fragile. To avoid the classic off-by-one-direction
// bug there is no ++/-- on rungs anywhere: use faster() and moreRobust().
//
// ===========================================================================
// HOW AGREEMENT IS REACHED
// ===========================================================================
//
// A receiver on the wrong config cannot decode at all - this is not graceful
// degradation, it is silence. So:
//
//   1. Data AND control both travel at the CURRENT config. One radio
//      demodulates one configuration at a time, so a parallel "always
//      listening" control channel is not physically available.
//   2. A change is proposed in-band, over the link that currently works.
//   3. The peer accepts, then both switch.
//   4. If the accept is lost the two can split - one moved, one did not. That
//      window is real and unavoidable over a lossy link. It is bounded by the
//      SILENCE WATCHDOG: after silenceMs with no valid frame, each node
//      UNILATERALLY returns to the anchor rung. Recovery needs no
//      communication, which is what makes it work precisely when
//      communication has failed.

#pragma once

#include <cstddef>
#include <cstdint>

namespace lorax {

// Ordered fastest -> most robust. Index order is load-bearing.
enum class Rung : uint8_t {
    Fast   = 0,  // SF7  / 250 kHz
    Medium = 1,  // SF9  / 125 kHz
    Far    = 2,  // SF10 / 125 kHz
    Max    = 3,  // SF12 / 125 kHz
};
constexpr uint8_t RUNG_COUNT = 4;

struct RungConfig {
    uint8_t     sf;
    uint32_t    bandwidthHz;
    uint8_t     codingRate;
    float       requiredSnrDb;  // demodulation floor for this SF
    const char* name;
};

const RungConfig& rungConfig(Rung r);
const char*       rungName(Rung r);
bool              rungFromName(const char* s, Rung& out);

// Explicit, direction-safe ladder movement. Saturating at both ends.
Rung faster(Rung r);
Rung moreRobust(Rung r);
bool isFastest(Rung r);
bool isMostRobust(Rung r);

// SNR needed AT `from` before promoting to the next faster rung. Derived from
// the ladder rather than hardcoded, so it stays correct if a rung changes:
//
//   threshold = requiredSnr(faster) + 10*log10(BW_faster / BW_from) + fadeMargin
//
// The middle term is the reason MEDIUM -> FAST is the hardest promotion: the
// bandwidth doubles, the noise floor rises 3 dB, and the measured SNR drops by
// that much the moment you switch.
float stepUpThresholdDb(Rung from, float fadeMarginDb);

// Generalised to any target, not just the adjacent rung, so a link with plenty
// of margin can be promoted straight to the rung it can actually support
// instead of crawling one step at a time. Thresholds rise monotonically as the
// target gets faster, so a target that fails implies every faster one fails.
float stepUpThresholdTo(Rung from, Rung to, float fadeMarginDb);

// The fastest rung `snrDb` supports from `from`, never skipping more than
// `maxJump`. Returns `from` when nothing faster is supportable.
Rung bestRungFor(Rung from, float snrDb, float fadeMarginDb, uint8_t maxJump);

// Below this, retreat immediately.
float stepDownThresholdDb(Rung at, float marginDb);

// --- SNR feedback byte ------------------------------------------------------
//
// Signed, quarter-dB steps: -32.00 .. +31.75 dB, which covers LoRa's useful
// range (about -20 to +12) with room to spare.
//
// It rides ONLY on ACK and control frames, never on data headers. That is the
// point: feedback then exists exactly when packets are getting through, which
// is the only time promoting is safe to consider. When packets stop, feedback
// stops and the silence watchdog takes over - the mechanism degrades in the
// correct direction. A byte in every data header would tax every fragment
// forever to carry information that matters rarely.
int8_t encodeSnr(float snrDb);
float  decodeSnr(int8_t raw);

// --- rate-control control frames -------------------------------------------
// Carried in the payload of a PacketType::Beacon frame.
enum class RateOpcode : uint8_t {
    Report  = 0,  // heartbeat: here is the SNR I observe from you
    Propose = 1,  // I want to move to `rung`
    Accept  = 2,  // agreed, switching to `rung`
};

struct RateMessage {
    RateOpcode opcode = RateOpcode::Report;
    int8_t     snr    = 0;
    Rung       rung   = Rung::Far;
};

constexpr size_t RATE_MESSAGE_BYTES = 3;
size_t encodeRateMessage(const RateMessage& m, uint8_t* out, size_t cap);
bool   decodeRateMessage(const uint8_t* in, size_t len, RateMessage& out);

// ---------------------------------------------------------------------------

// Rung-change handshake, as a pure state machine.
//
// Extracted from the link layer deliberately: the two roles are NOT symmetric,
// and a single shared "pending" flag makes the PROPOSER switch as soon as its
// proposal is transmitted, without ever waiting for the peer. That is a silent
// split-brain on every change, and it is invisible to inspection - so the state
// machine lives here, where it can be tested without a radio.
//
//   proposer:  decide -> SendPropose -> (wait) -> Accept arrives -> ApplyRung
//   accepter:  Propose arrives -> SendAccept -> (our TX drains) -> ApplyRung
//
// The proposer must NOT switch on its own transmission completing. The accepter
// MUST, because its reply has to leave at the rung the peer still listens on.
class RungNegotiator {
public:
    enum class State : uint8_t {
        Idle,
        AwaitingAccept,  // we proposed; do NOT switch until the peer agrees
        SwitchAfterTx,   // we accepted; switch once our Accept has gone out
    };

    enum class Action : uint8_t { None, SendPropose, SendAccept, ApplyRung };

    struct Step {
        Action action = Action::None;
        Rung   rung   = Rung::Far;
    };

    // We decided locally that the rung should change.
    Step propose(Rung target, uint32_t nowMs);
    // The peer proposed a change.
    Step onPropose(Rung target, uint32_t nowMs);
    // The peer accepted ours.
    Step onAccept(Rung target, uint32_t nowMs);
    // Our transmit queue drained; a pending acceptance can now be applied.
    Step onTxIdle(uint32_t nowMs);
    // No Accept arrived in time - give up and stay put.
    Step onTimeout(uint32_t nowMs, uint32_t timeoutMs);

    // A unilateral watchdog fallback overrides any handshake in flight.
    void reset();

    State    state() const { return state_; }
    bool     busy()  const { return state_ != State::Idle; }
    Rung     target() const { return target_; }
    uint32_t startedMs() const { return startedMs_; }

private:
    State    state_     = State::Idle;
    Rung     target_    = Rung::Far;
    uint32_t startedMs_ = 0;
};

class RateController {
public:
    struct Params {
        // Promotion is slow and needs repeated evidence.
        uint8_t  stepUpConsecutive = 8;
        float    fadeMarginDb      = 6.0f;
        // Confirmation gates the DECISION, not each intermediate step, so a
        // strong link reaches its true rung in one cycle rather than three.
        // The cap stops a freak reading launching straight to FAST; the
        // minimum-across-the-streak rule below is the second guard.
        uint8_t  maxRungJump       = 2;
        // Demotion is instant; this tripwire fires before the link actually
        // breaks, because retreating from a working link is cheap and
        // recovering from a dead one is not.
        float    stepDownMarginDb  = 2.0f;
        // ~2x the reassembly timeout, so a slow multi-fragment message at a
        // high SF never trips it.
        uint32_t silenceMs         = 60000;
        uint32_t deepRendezvousMs  = 180000;
        uint32_t holdAfterChangeMs = 10000;
        // Peer SNR older than this is ignored as stale.
        uint32_t peerSnrValidMs    = 30000;
        Rung     anchor            = Rung::Far;
        Rung     deepAnchor        = Rung::Max;
    };

    struct Decision {
        bool        change = false;
        Rung        target = Rung::Far;
        const char* reason = "";
        // Watchdog fallbacks are applied WITHOUT negotiating - that is the
        // whole point of an anchor. Negotiating requires a working link, and
        // this fires precisely when there isn't one.
        bool        unilateral = false;
        // How long we had actually been silent when a watchdog decision fired.
        // Logged so the silenceMs default can be tuned against real data
        // rather than left at whatever it was first guessed to be.
        uint32_t    observedSilenceMs = 0;
    };

    explicit RateController(Rung start = Rung::Far);

    // Arms the silence watchdog from boot. Without this a node that starts on
    // a non-anchor rung and never hears anything would sit there forever,
    // because the watchdog would have no reference time to measure from.
    void begin(uint32_t nowMs);

    // --- observations in ---
    void onGoodFrame(float snrDb, uint32_t nowMs);
    void onBadFrame(uint32_t nowMs);
    void onPeerReportedSnr(float snrDb, uint32_t nowMs);

    // Option (c) from the ALERT policy: while an urgent message is waiting,
    // promotions are withheld. Promoting makes the link FASTER and therefore
    // MORE FRAGILE - precisely the wrong trade when the message that most needs
    // to arrive has not gone out yet. Retreats and the silence watchdog stay
    // live, because both make the link more reliable rather than less.
    void setUrgentPending(bool pending) { urgentPending_ = pending; }
    bool urgentPending() const { return urgentPending_; }

    // --- decision out ---
    Decision evaluate(uint32_t nowMs) const;
    void     applied(Rung r, uint32_t nowMs);

    // --- manual override (runtime, no rebuild) ---
    void setAdaptive(bool on);
    bool adaptive() const { return adaptive_; }
    void forceRung(Rung r, uint32_t nowMs);  // pins AND disables adaptation

    Rung    current() const { return current_; }
    uint8_t consecutiveGood() const { return consecutiveGood_; }
    // Worst SNR seen during the current promotion streak. Decisions use THIS,
    // not the latest sample, so one lucky frame cannot carry a jump.
    float   streakFloorSnrDb() const { return minSnrInStreak_; }
    bool    haveHeardPeer() const { return lastFrameMs_ != 0; }

    // The SNR that actually governs decisions: the worse of what we hear from
    // the peer and what the peer reports hearing from us. A link is only as
    // good as its weaker direction, and both ends must run the same rung.
    float effectiveSnrDb(uint32_t nowMs) const;
    float lastLocalSnrDb() const { return lastLocalSnr_; }
    // How long since the last valid frame. 0 if we have never heard anything.
    uint32_t silentForMs(uint32_t nowMs) const;
    float lastPeerSnrDb() const { return lastPeerSnr_; }

    Params&       params()       { return params_; }
    const Params& params() const { return params_; }

private:
    Params   params_;
    Rung     current_          = Rung::Far;
    bool     adaptive_         = true;
    uint8_t  consecutiveGood_  = 0;
    float    minSnrInStreak_   = 0.0f;
    bool     retreatRequested_ = false;
    float    lastLocalSnr_     = 0.0f;
    float    lastPeerSnr_      = 0.0f;
    uint32_t lastPeerSnrMs_    = 0;
    uint32_t lastFrameMs_      = 0;
    uint32_t lastChangeMs_     = 0;
    bool     peerSnrValid_     = false;
    bool     urgentPending_    = false;
};

}  // namespace lorax
