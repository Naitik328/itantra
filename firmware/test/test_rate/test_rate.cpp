// Adaptive rate control: ladder sanity, thresholds, hysteresis, watchdog,
// manual override. All of it native - testing adaptation on hardware means
// physically walking away from a board.

#include <unity.h>

#include <cmath>
#include <cstdio>

#include "airtime.h"
#include "envelope.h"
#include "fragmenter.h"
#include "packet.h"
#include "rate_control.h"

using namespace lorax;

void setUp() {}
void tearDown() {}

static LoRaParams paramsFor(Rung r) {
    const RungConfig& c = rungConfig(r);
    LoRaParams p;
    p.sf = c.sf;
    p.bandwidthHz = c.bandwidthHz;
    p.codingRate = c.codingRate;
    return p;
}

// --- ladder ----------------------------------------------------------------

// The direction trap: lower index must be BOTH faster and less sensitive.
// If this ever inverts, "long range" mode is silently worse than "fast" mode.
static void test_ladder_direction_is_consistent(void) {
    double prevAir = -1.0;
    float  prevSnrFloor = 1e9f;
    for (uint8_t i = 0; i < RUNG_COUNT; ++i) {
        const Rung r = static_cast<Rung>(i);
        const double air = timeOnAirMs(paramsFor(r), 254);
        const float floorDb = rungConfig(r).requiredSnrDb;
        char msg[64];
        std::snprintf(msg, sizeof(msg), "rung %s", rungName(r));
        // slower as we go down the ladder
        TEST_ASSERT_TRUE_MESSAGE(air > prevAir, msg);
        // and more sensitive (a LOWER required SNR)
        TEST_ASSERT_TRUE_MESSAGE(floorDb < prevSnrFloor, msg);
        prevAir = air;
        prevSnrFloor = floorDb;
    }
}

static void test_ladder_is_the_approved_one(void) {
    TEST_ASSERT_EQUAL_UINT8(7,  rungConfig(Rung::Fast).sf);
    TEST_ASSERT_EQUAL_UINT32(250000, rungConfig(Rung::Fast).bandwidthHz);
    TEST_ASSERT_EQUAL_UINT8(9,  rungConfig(Rung::Medium).sf);
    TEST_ASSERT_EQUAL_UINT32(125000, rungConfig(Rung::Medium).bandwidthHz);
    TEST_ASSERT_EQUAL_UINT8(10, rungConfig(Rung::Far).sf);
    TEST_ASSERT_EQUAL_UINT8(12, rungConfig(Rung::Max).sf);
}

static void test_rung_movement_saturates(void) {
    TEST_ASSERT_EQUAL(Rung::Fast, faster(Rung::Fast));       // nowhere faster
    TEST_ASSERT_EQUAL(Rung::Fast, faster(Rung::Medium));
    TEST_ASSERT_EQUAL(Rung::Max,  moreRobust(Rung::Max));    // nowhere slower
    TEST_ASSERT_EQUAL(Rung::Max,  moreRobust(Rung::Far));
    TEST_ASSERT_TRUE(isFastest(Rung::Fast));
    TEST_ASSERT_TRUE(isMostRobust(Rung::Max));
}

static void test_rung_names_parse(void) {
    Rung r;
    TEST_ASSERT_TRUE(rungFromName("fast", r));   TEST_ASSERT_EQUAL(Rung::Fast, r);
    TEST_ASSERT_TRUE(rungFromName("MEDIUM", r)); TEST_ASSERT_EQUAL(Rung::Medium, r);
    TEST_ASSERT_TRUE(rungFromName("Far", r));    TEST_ASSERT_EQUAL(Rung::Far, r);
    TEST_ASSERT_TRUE(rungFromName("max", r));    TEST_ASSERT_EQUAL(Rung::Max, r);
    TEST_ASSERT_FALSE(rungFromName("turbo", r));
}

// The rung choice has a FRAGMENTATION consequence, not just an airtime one:
// a maximum-size 247-byte app payload is one frame at MEDIUM and two at FAR.
static void test_medium_is_single_fragment_where_far_is_not(void) {
    constexpr double BUDGET_MS = 2200.0;
    const size_t body = app::ENVELOPE_MAX_PAYLOAD;   // 247

    const size_t medCap = maxPayloadForBudget(paramsFor(Rung::Medium), BUDGET_MS);
    const size_t farCap = maxPayloadForBudget(paramsFor(Rung::Far), BUDGET_MS);

    TEST_ASSERT_EQUAL_UINT(1, fragmentCount(body, maxFragmentPayload(medCap)));
    TEST_ASSERT_EQUAL_UINT(2, fragmentCount(body, maxFragmentPayload(farCap)));

    // It physically fits one frame at either rung; it is the airtime budget,
    // not the 255-byte limit, that forces the split at FAR.
    TEST_ASSERT_LESS_OR_EQUAL_UINT(MAX_FRAME, body + OVERHEAD);
    TEST_ASSERT_TRUE(timeOnAirMs(paramsFor(Rung::Far), body + OVERHEAD) > BUDGET_MS);
    TEST_ASSERT_TRUE(timeOnAirMs(paramsFor(Rung::Medium), body + OVERHEAD) <= BUDGET_MS);
}

// --- thresholds ------------------------------------------------------------

static void test_step_up_thresholds_match_the_approved_numbers(void) {
    const float m = 6.0f;
    TEST_ASSERT_FLOAT_WITHIN(0.05f, -9.0f, stepUpThresholdDb(Rung::Max, m));
    TEST_ASSERT_FLOAT_WITHIN(0.05f, -6.5f, stepUpThresholdDb(Rung::Far, m));
    // The only bandwidth-changing promotion, and the hardest to earn: +3 dB
    // extra because doubling BW raises the noise floor.
    TEST_ASSERT_FLOAT_WITHIN(0.05f, 1.51f, stepUpThresholdDb(Rung::Medium, m));
}

static void test_step_down_thresholds_match(void) {
    const float m = 2.0f;
    TEST_ASSERT_FLOAT_WITHIN(0.05f,  -5.5f, stepDownThresholdDb(Rung::Fast, m));
    TEST_ASSERT_FLOAT_WITHIN(0.05f, -10.5f, stepDownThresholdDb(Rung::Medium, m));
    TEST_ASSERT_FLOAT_WITHIN(0.05f, -13.0f, stepDownThresholdDb(Rung::Far, m));
    TEST_ASSERT_FLOAT_WITHIN(0.05f, -18.0f, stepDownThresholdDb(Rung::Max, m));
}

// --- SNR byte --------------------------------------------------------------

static void test_snr_byte_roundtrip_and_clamping(void) {
    for (float v = -20.0f; v <= 12.0f; v += 0.25f) {
        TEST_ASSERT_FLOAT_WITHIN(0.13f, v, decodeSnr(encodeSnr(v)));
    }
    TEST_ASSERT_EQUAL_INT8(127, encodeSnr(1000.0f));
    TEST_ASSERT_EQUAL_INT8(-128, encodeSnr(-1000.0f));
    TEST_ASSERT_FLOAT_WITHIN(0.01f, 0.0f, decodeSnr(0));
}

static void test_rate_message_codec(void) {
    RateMessage m;
    m.opcode = RateOpcode::Propose;
    m.snr = encodeSnr(-7.25f);
    m.rung = Rung::Medium;

    uint8_t buf[RATE_MESSAGE_BYTES];
    TEST_ASSERT_EQUAL_UINT(RATE_MESSAGE_BYTES, encodeRateMessage(m, buf, sizeof(buf)));

    RateMessage back;
    TEST_ASSERT_TRUE(decodeRateMessage(buf, sizeof(buf), back));
    TEST_ASSERT_EQUAL(RateOpcode::Propose, back.opcode);
    TEST_ASSERT_EQUAL(Rung::Medium, back.rung);
    TEST_ASSERT_FLOAT_WITHIN(0.13f, -7.25f, decodeSnr(back.snr));

    uint8_t bad[RATE_MESSAGE_BYTES] = {9, 0, 0};
    TEST_ASSERT_FALSE(decodeRateMessage(bad, sizeof(bad), back));
    uint8_t badRung[RATE_MESSAGE_BYTES] = {0, 0, 99};
    TEST_ASSERT_FALSE(decodeRateMessage(badRung, sizeof(badRung), back));
    TEST_ASSERT_FALSE(decodeRateMessage(bad, 2, back));

    // An ACK's single SNR byte fits comfortably in a control frame.
    TEST_ASSERT_LESS_OR_EQUAL_UINT(MAX_PAYLOAD, RATE_MESSAGE_BYTES);
}

// --- promotion is slow -----------------------------------------------------

static void test_promotion_needs_sustained_evidence(void) {
    RateController rc(Rung::Far);
    const float good = stepUpThresholdDb(Rung::Far, rc.params().fadeMarginDb) + 1.0f;
    uint32_t t = 100000;

    for (uint8_t i = 1; i < rc.params().stepUpConsecutive; ++i) {
        rc.onGoodFrame(good, t);
        TEST_ASSERT_FALSE(rc.evaluate(t).change);   // not yet
        t += 1000;
    }
    rc.onGoodFrame(good, t);
    const auto d = rc.evaluate(t);
    TEST_ASSERT_TRUE(d.change);
    TEST_ASSERT_EQUAL(Rung::Medium, d.target);
    TEST_ASSERT_FALSE(d.unilateral);   // promotion must be negotiated
}

// One mediocre frame resets the streak: the evidence has to be consecutive.
static void test_one_mediocre_frame_resets_the_streak(void) {
    RateController rc(Rung::Far);
    const float good = stepUpThresholdDb(Rung::Far, rc.params().fadeMarginDb) + 1.0f;
    const float meh  = stepUpThresholdDb(Rung::Far, rc.params().fadeMarginDb) - 1.0f;
    uint32_t t = 100000;

    for (uint8_t i = 0; i < rc.params().stepUpConsecutive - 1; ++i) {
        rc.onGoodFrame(good, t);
        t += 1000;
    }
    rc.onGoodFrame(meh, t);      // still decodes fine, just not enough margin
    t += 1000;
    TEST_ASSERT_EQUAL_UINT8(0, rc.consecutiveGood());
    rc.onGoodFrame(good, t);
    TEST_ASSERT_FALSE(rc.evaluate(t).change);
}

static void test_cannot_promote_past_fastest(void) {
    RateController rc(Rung::Fast);
    for (int i = 0; i < 50; ++i) rc.onGoodFrame(20.0f, 100000 + i * 1000);
    TEST_ASSERT_FALSE(rc.evaluate(160000).change);
}

// --- demotion is instant ---------------------------------------------------

static void test_one_bad_frame_retreats_immediately(void) {
    RateController rc(Rung::Medium);
    rc.onBadFrame(100000);
    const auto d = rc.evaluate(100000);
    TEST_ASSERT_TRUE(d.change);
    TEST_ASSERT_EQUAL(Rung::Far, d.target);
    TEST_ASSERT_FALSE(d.unilateral);
}

static void test_marginal_snr_retreats_before_the_link_breaks(void) {
    RateController rc(Rung::Far);
    // Decodes, but under the tripwire - retreat while it still works.
    const float marginal =
        stepDownThresholdDb(Rung::Far, rc.params().stepDownMarginDb) - 0.5f;
    rc.onGoodFrame(marginal, 100000);
    const auto d = rc.evaluate(100000);
    TEST_ASSERT_TRUE(d.change);
    TEST_ASSERT_EQUAL(Rung::Max, d.target);
}

static void test_cannot_retreat_past_most_robust(void) {
    RateController rc(Rung::Max);
    rc.onBadFrame(100000);
    TEST_ASSERT_FALSE(rc.evaluate(100000).change);
}

// --- watchdog --------------------------------------------------------------

static void test_silence_falls_back_to_anchor_unilaterally(void) {
    RateController rc(Rung::Fast);
    rc.onGoodFrame(10.0f, 100000);
    TEST_ASSERT_FALSE(rc.evaluate(100000 + rc.params().silenceMs - 1).change);

    const auto d = rc.evaluate(100000 + rc.params().silenceMs);
    TEST_ASSERT_TRUE(d.change);
    TEST_ASSERT_EQUAL(Rung::Far, d.target);          // the anchor
    // No handshake: the peer is unreachable by definition.
    TEST_ASSERT_TRUE(d.unilateral);
}

static void test_prolonged_silence_reaches_the_deep_anchor(void) {
    RateController rc(Rung::Fast);
    rc.onGoodFrame(10.0f, 100000);
    rc.applied(Rung::Far, 100000);   // already fell back to the anchor
    const auto d = rc.evaluate(100000 + rc.params().deepRendezvousMs);
    TEST_ASSERT_TRUE(d.change);
    TEST_ASSERT_EQUAL(Rung::Max, d.target);
    TEST_ASSERT_TRUE(d.unilateral);
}

// The watchdog outranks the post-change hold: if we have gone quiet, nothing
// else matters.
static void test_watchdog_ignores_the_hold_window(void) {
    RateController rc(Rung::Fast);
    rc.onGoodFrame(10.0f, 100000);
    rc.applied(Rung::Fast, 100000 + rc.params().silenceMs);  // change just now
    const auto d = rc.evaluate(100000 + rc.params().silenceMs);
    TEST_ASSERT_TRUE(d.change);
    TEST_ASSERT_EQUAL(Rung::Far, d.target);
}

static void test_hold_window_prevents_oscillation(void) {
    RateController rc(Rung::Medium);
    rc.applied(Rung::Medium, 100000);
    rc.onBadFrame(100500);
    // Inside the hold window a normal retreat is suppressed...
    TEST_ASSERT_FALSE(rc.evaluate(100500).change);
    // ...and allowed once it expires.
    const uint32_t after = 100000 + rc.params().holdAfterChangeMs + 1;
    TEST_ASSERT_TRUE(rc.evaluate(after).change);
}

// --- asymmetry -------------------------------------------------------------

// A link is only as good as its weaker direction, and both ends must run the
// same rung, so decisions follow min(local, peer).
static void test_decisions_follow_the_weaker_direction(void) {
    RateController rc(Rung::Far);
    const float good = stepUpThresholdDb(Rung::Far, rc.params().fadeMarginDb) + 3.0f;
    uint32_t t = 100000;

    // Comfortably above FAR's retreat tripwire (-13.0) but well below its
    // promotion threshold (-6.5): the outbound link is adequate, not good.
    rc.onPeerReportedSnr(-10.0f, t);
    for (uint8_t i = 0; i < rc.params().stepUpConsecutive + 2; ++i) {
        rc.onGoodFrame(good, t);       // but we hear the peer beautifully
        t += 1000;
    }
    // No promotion: our outbound direction cannot support it.
    TEST_ASSERT_FALSE(rc.evaluate(t).change);
    TEST_ASSERT_FLOAT_WITHIN(0.1f, -10.0f, rc.effectiveSnrDb(t));
    TEST_ASSERT_EQUAL_UINT8(0, rc.consecutiveGood());
}

// The same asymmetry in the other direction: if the PEER reports it can barely
// hear us, we retreat even though our own inbound link looks excellent. Only
// one end needs to be failing for the shared rung to be wrong.
static void test_peer_reporting_a_bad_link_forces_retreat(void) {
    RateController rc(Rung::Far);
    rc.onPeerReportedSnr(-14.0f, 100000);   // below FAR's -13.0 tripwire
    rc.onGoodFrame(8.0f, 100000);           // our inbound is fine
    const auto d = rc.evaluate(100000);
    TEST_ASSERT_TRUE(d.change);
    TEST_ASSERT_EQUAL(Rung::Max, d.target);
}

static void test_stale_peer_snr_is_ignored(void) {
    RateController rc(Rung::Far);
    rc.onPeerReportedSnr(-14.0f, 100000);
    rc.onGoodFrame(5.0f, 100000);
    TEST_ASSERT_FLOAT_WITHIN(0.1f, -14.0f, rc.effectiveSnrDb(100000));
    // Past the freshness window, fall back to what we can measure ourselves.
    const uint32_t later = 100000 + rc.params().peerSnrValidMs + 1;
    TEST_ASSERT_FLOAT_WITHIN(0.1f, 5.0f, rc.effectiveSnrDb(later));
}

// --- manual override -------------------------------------------------------

static void test_adaptation_can_be_frozen(void) {
    RateController rc(Rung::Medium);
    rc.setAdaptive(false);
    rc.onBadFrame(100000);
    TEST_ASSERT_FALSE(rc.evaluate(100000).change);
    for (int i = 0; i < 50; ++i) rc.onGoodFrame(20.0f, 100000 + i * 1000);
    TEST_ASSERT_FALSE(rc.evaluate(200000).change);
    TEST_ASSERT_EQUAL(Rung::Medium, rc.current());
}

static void test_force_rung_pins_and_disables(void) {
    RateController rc(Rung::Far);
    TEST_ASSERT_TRUE(rc.adaptive());
    rc.forceRung(Rung::Max, 100000);
    TEST_ASSERT_EQUAL(Rung::Max, rc.current());
    TEST_ASSERT_FALSE(rc.adaptive());   // pinning implies freezing

    // Even prolonged silence must not move a pinned rung - an A/B range test
    // that silently reconfigures itself measures nothing.
    TEST_ASSERT_FALSE(rc.evaluate(100000 + rc.params().deepRendezvousMs * 2).change);

    rc.setAdaptive(true);
    TEST_ASSERT_TRUE(rc.adaptive());
}

static void test_applied_clears_stale_evidence(void) {
    RateController rc(Rung::Far);
    rc.onPeerReportedSnr(-14.0f, 100000);
    rc.onGoodFrame(5.0f, 100000);
    rc.applied(Rung::Medium, 100000);
    // A fresh rung has told us nothing yet.
    TEST_ASSERT_EQUAL_UINT8(0, rc.consecutiveGood());
    TEST_ASSERT_FLOAT_WITHIN(0.1f, 5.0f, rc.effectiveSnrDb(100000));
}

// --- handshake -------------------------------------------------------------

// THE bug this state machine was extracted to make visible. The proposer must
// NOT switch when its own proposal finishes transmitting - the peer may never
// have heard it. Sharing one "pending" flag between the two roles made every
// rung change silently unilateral.
static void test_proposer_does_not_switch_on_its_own_transmission(void) {
    RungNegotiator n;
    const auto p = n.propose(Rung::Medium, 1000);
    TEST_ASSERT_EQUAL(RungNegotiator::Action::SendPropose, p.action);
    TEST_ASSERT_EQUAL(RungNegotiator::State::AwaitingAccept, n.state());

    // Our proposal has gone out. That is NOT permission to switch.
    const auto idle = n.onTxIdle(1100);
    TEST_ASSERT_EQUAL(RungNegotiator::Action::None, idle.action);
    TEST_ASSERT_EQUAL(RungNegotiator::State::AwaitingAccept, n.state());
}

// The accepter is the opposite: it MUST switch once its reply is on the air,
// because the reply has to leave at the rung the peer still listens on.
static void test_accepter_switches_once_its_reply_is_sent(void) {
    RungNegotiator n;
    const auto a = n.onPropose(Rung::Fast, 1000);
    TEST_ASSERT_EQUAL(RungNegotiator::Action::SendAccept, a.action);
    TEST_ASSERT_EQUAL(Rung::Fast, a.rung);

    const auto idle = n.onTxIdle(1100);
    TEST_ASSERT_EQUAL(RungNegotiator::Action::ApplyRung, idle.action);
    TEST_ASSERT_EQUAL(Rung::Fast, idle.rung);
    TEST_ASSERT_EQUAL(RungNegotiator::State::Idle, n.state());
}

static void test_proposer_applies_only_on_accept(void) {
    RungNegotiator n;
    n.propose(Rung::Medium, 1000);
    const auto ok = n.onAccept(Rung::Medium, 1200);
    TEST_ASSERT_EQUAL(RungNegotiator::Action::ApplyRung, ok.action);
    TEST_ASSERT_EQUAL(Rung::Medium, ok.rung);
    TEST_ASSERT_FALSE(n.busy());
}

static void test_stale_or_crossed_accepts_are_ignored(void) {
    RungNegotiator n;
    n.propose(Rung::Medium, 1000);
    // An Accept for a rung we never asked for.
    TEST_ASSERT_EQUAL(RungNegotiator::Action::None, n.onAccept(Rung::Fast, 1200).action);
    TEST_ASSERT_EQUAL(RungNegotiator::State::AwaitingAccept, n.state());

    RungNegotiator idleN;
    // An Accept with no proposal outstanding.
    TEST_ASSERT_EQUAL(RungNegotiator::Action::None, idleN.onAccept(Rung::Far, 5).action);
}

static void test_unanswered_proposal_times_out_without_switching(void) {
    RungNegotiator n;
    n.propose(Rung::Fast, 1000);
    n.onTimeout(1000 + 500, 1000);
    TEST_ASSERT_EQUAL(RungNegotiator::State::AwaitingAccept, n.state());  // not yet
    n.onTimeout(1000 + 1001, 1000);
    TEST_ASSERT_EQUAL(RungNegotiator::State::Idle, n.state());
    // Crucially, nothing was applied: staying put is the safe outcome.
    TEST_ASSERT_EQUAL(RungNegotiator::Action::None, n.onTxIdle(3000).action);
}

// Simultaneous proposals cross in flight. The incoming one wins, so both nodes
// converge instead of each insisting on its own.
static void test_incoming_proposal_overrides_our_own(void) {
    RungNegotiator n;
    n.propose(Rung::Medium, 1000);
    const auto a = n.onPropose(Rung::Max, 1050);
    TEST_ASSERT_EQUAL(RungNegotiator::Action::SendAccept, a.action);
    TEST_ASSERT_EQUAL(Rung::Max, a.rung);
    TEST_ASSERT_EQUAL(RungNegotiator::State::SwitchAfterTx, n.state());
    TEST_ASSERT_EQUAL(Rung::Max, n.onTxIdle(1100).rung);
}

static void test_reset_abandons_a_handshake(void) {
    RungNegotiator n;
    n.propose(Rung::Fast, 1000);
    TEST_ASSERT_TRUE(n.busy());
    n.reset();                       // a watchdog fallback overrides everything
    TEST_ASSERT_FALSE(n.busy());
    TEST_ASSERT_EQUAL(RungNegotiator::Action::None, n.onTxIdle(1100).action);
}

// Two nodes, happy path: both end on the same rung.
static void test_two_nodes_converge(void) {
    RungNegotiator a, b;
    Rung aRung = Rung::Far, bRung = Rung::Far;

    const auto proposal = a.propose(Rung::Medium, 1000);
    TEST_ASSERT_EQUAL(RungNegotiator::Action::SendPropose, proposal.action);

    const auto accept = b.onPropose(proposal.rung, 1100);   // B hears it
    TEST_ASSERT_EQUAL(RungNegotiator::Action::SendAccept, accept.action);
    const auto bApply = b.onTxIdle(1200);                   // B's reply goes out
    if (bApply.action == RungNegotiator::Action::ApplyRung) bRung = bApply.rung;

    const auto aApply = a.onAccept(accept.rung, 1300);      // A hears the accept
    if (aApply.action == RungNegotiator::Action::ApplyRung) aRung = aApply.rung;

    TEST_ASSERT_EQUAL(Rung::Medium, aRung);
    TEST_ASSERT_EQUAL(Rung::Medium, bRung);
    TEST_ASSERT_FALSE(a.busy());
    TEST_ASSERT_FALSE(b.busy());
}

// Two nodes, the unavoidable case: the Accept is lost. B switched, A did not.
// No handshake can fix this (Two Generals). What must hold is that the split is
// BOUNDED - both independently fall back to the anchor on the silence watchdog.
static void test_lost_accept_splits_then_both_fall_back_to_anchor(void) {
    RungNegotiator a, b;
    RateController rcA(Rung::Far), rcB(Rung::Far);
    rcA.begin(1000);
    rcB.begin(1000);
    Rung aRung = Rung::Far, bRung = Rung::Far;

    const auto proposal = a.propose(Rung::Fast, 1000);
    const auto accept   = b.onPropose(proposal.rung, 1100);
    const auto bApply   = b.onTxIdle(1200);
    bRung = bApply.rung;
    rcB.applied(bRung, 1200);
    // ...and A never hears the Accept.
    a.onTimeout(1000 + 60000, 5000);

    TEST_ASSERT_EQUAL(Rung::Far,  aRung);   // A stayed
    TEST_ASSERT_EQUAL(Rung::Fast, bRung);   // B moved - genuinely split
    TEST_ASSERT_FALSE(a.busy());

    // Neither hears anything now, because they are on different configs.
    const uint32_t later = 1200 + rcB.params().silenceMs;
    const auto dB = rcB.evaluate(later);
    TEST_ASSERT_TRUE(dB.change);
    TEST_ASSERT_TRUE(dB.unilateral);
    TEST_ASSERT_EQUAL(Rung::Far, dB.target);        // B returns to the anchor
    TEST_ASSERT_TRUE(dB.observedSilenceMs >= rcB.params().silenceMs);

    // A was already on the anchor, so it has nowhere to fall back to and stays.
    const auto dA = rcA.evaluate(later);
    TEST_ASSERT_FALSE(dA.change);
    // Converged, without either node communicating.
    TEST_ASSERT_EQUAL(Rung::Far, dB.target);
}

// --- silence watchdog tuning ------------------------------------------------

static void test_begin_arms_the_watchdog_from_boot(void) {
    RateController rc(Rung::Fast);
    // Never heard anything and never armed: no reference time, no fallback.
    TEST_ASSERT_FALSE(rc.evaluate(10u * 60u * 1000u).change);
    TEST_ASSERT_EQUAL_UINT32(0, rc.silentForMs(5000));

    RateController armed(Rung::Fast);
    armed.begin(1000);
    TEST_ASSERT_EQUAL_UINT32(4000, armed.silentForMs(5000));
    // A node that boots on a fast rung and hears nothing still finds the anchor.
    const auto d = armed.evaluate(1000 + armed.params().silenceMs);
    TEST_ASSERT_TRUE(d.change);
    TEST_ASSERT_EQUAL(Rung::Far, d.target);
}

static void test_observed_silence_is_reported(void) {
    RateController rc(Rung::Fast);
    rc.begin(1000);
    const uint32_t over = rc.params().silenceMs + 7345;
    const auto d = rc.evaluate(1000 + over);
    TEST_ASSERT_TRUE(d.change);
    // The actual duration, not just "past the threshold" - this is what makes
    // the default tunable against real data instead of guesswork.
    TEST_ASSERT_EQUAL_UINT32(over, d.observedSilenceMs);
}

static void test_silence_threshold_is_runtime_adjustable(void) {
    RateController rc(Rung::Fast);
    rc.begin(1000);
    TEST_ASSERT_EQUAL_UINT32(60000, rc.params().silenceMs);   // default stays 60 s

    rc.params().silenceMs = 8000;
    TEST_ASSERT_FALSE(rc.evaluate(1000 + 7999).change);
    const auto d = rc.evaluate(1000 + 8000);
    TEST_ASSERT_TRUE(d.change);
    TEST_ASSERT_EQUAL(Rung::Far, d.target);
    TEST_ASSERT_EQUAL_UINT32(8000, d.observedSilenceMs);
}

static void test_a_received_frame_resets_the_silence_clock(void) {
    RateController rc(Rung::Fast);
    rc.begin(1000);
    rc.onGoodFrame(5.0f, 1000 + rc.params().silenceMs - 1);
    // The clock restarts from the frame, not from boot.
    TEST_ASSERT_FALSE(rc.evaluate(1000 + rc.params().silenceMs).change);
    TEST_ASSERT_EQUAL_UINT32(1, rc.silentForMs(1000 + rc.params().silenceMs));
}

// --- margin-based promotion ------------------------------------------------

static void test_step_up_thresholds_rise_with_target_speed(void) {
    const float m = 6.0f;
    // From MAX, each faster target demands more margin - which is what lets
    // bestRungFor stop at the first failure.
    const float toFar    = stepUpThresholdTo(Rung::Max, Rung::Far, m);
    const float toMedium = stepUpThresholdTo(Rung::Max, Rung::Medium, m);
    const float toFast   = stepUpThresholdTo(Rung::Max, Rung::Fast, m);
    TEST_ASSERT_TRUE(toFar < toMedium);
    TEST_ASSERT_TRUE(toMedium < toFast);
    TEST_ASSERT_FLOAT_WITHIN(0.05f, -9.0f, toFar);
    TEST_ASSERT_FLOAT_WITHIN(0.05f, -6.5f, toMedium);
    TEST_ASSERT_FLOAT_WITHIN(0.05f, 1.51f, toFast);
    // The adjacent-rung helper is the same function, one step along.
    TEST_ASSERT_FLOAT_WITHIN(0.05f, toFar, stepUpThresholdDb(Rung::Max, m));
}

static void test_best_rung_sizes_the_jump_to_the_margin(void) {
    const float m = 6.0f;
    // Nothing to spare: stay put.
    TEST_ASSERT_EQUAL(Rung::Max, bestRungFor(Rung::Max, -20.0f, m, 2));
    // Enough for one rung only.
    TEST_ASSERT_EQUAL(Rung::Far, bestRungFor(Rung::Max, -8.0f, m, 2));
    // Enough for two.
    TEST_ASSERT_EQUAL(Rung::Medium, bestRungFor(Rung::Max, -5.0f, m, 2));
    // From FAR, a strong link reaches FAST in a single decision.
    TEST_ASSERT_EQUAL(Rung::Fast, bestRungFor(Rung::Far, 5.0f, m, 2));
}

// A freak reading must not launch the link to FAST. Two independent guards:
// the jump cap, and sizing from the worst sample in the streak.
static void test_jump_is_capped(void) {
    const float m = 6.0f;
    // Absurdly good SNR from the most robust rung: still only two rungs.
    TEST_ASSERT_EQUAL(Rung::Medium, bestRungFor(Rung::Max, 40.0f, m, 2));
    // With the cap lowered it crawls one at a time.
    TEST_ASSERT_EQUAL(Rung::Far, bestRungFor(Rung::Max, 40.0f, m, 1));
    // Cap cannot push past the end of the ladder.
    TEST_ASSERT_EQUAL(Rung::Fast, bestRungFor(Rung::Medium, 40.0f, m, 2));
    TEST_ASSERT_EQUAL(Rung::Fast, bestRungFor(Rung::Fast, 40.0f, m, 2));
}

static void test_promotion_jumps_two_rungs_in_one_cycle(void) {
    RateController rc(Rung::Max);
    uint32_t t = 100000;
    // Comfortably supports MEDIUM (-6.5) but not FAST (+1.51).
    for (uint8_t i = 0; i < rc.params().stepUpConsecutive; ++i) {
        rc.onGoodFrame(-5.0f, t);
        t += 1000;
    }
    const auto d = rc.evaluate(t);
    TEST_ASSERT_TRUE(d.change);
    // Two rungs from ONE confirmation cycle, not two cycles of one rung.
    TEST_ASSERT_EQUAL(Rung::Medium, d.target);
    TEST_ASSERT_FALSE(d.unilateral);
}

// The decision is sized from the WORST sample in the streak, so a single lucky
// frame among excellent ones cannot buy a rung the link cannot hold.
static void test_streak_floor_not_latest_sample_sizes_the_jump(void) {
    RateController rc(Rung::Far);
    uint32_t t = 100000;
    rc.onGoodFrame(-5.0f, t);          // adequate: supports MEDIUM, not FAST
    t += 1000;
    for (uint8_t i = 1; i < rc.params().stepUpConsecutive; ++i) {
        rc.onGoodFrame(15.0f, t);      // and then a run of excellent ones
        t += 1000;
    }
    TEST_ASSERT_FLOAT_WITHIN(0.1f, -5.0f, rc.streakFloorSnrDb());
    const auto d = rc.evaluate(t);
    TEST_ASSERT_TRUE(d.change);
    // MEDIUM, not FAST: the excellent samples do not erase the weak one.
    TEST_ASSERT_EQUAL(Rung::Medium, d.target);
}

// Retreat is unchanged by any of this: still one event, still one rung at a
// time. A wrong jump upward is a broken link; a wrong retreat is only slow.
static void test_retreat_remains_single_step_and_immediate(void) {
    RateController rc(Rung::Fast);
    rc.onBadFrame(100000);
    const auto d = rc.evaluate(100000);
    TEST_ASSERT_TRUE(d.change);
    TEST_ASSERT_EQUAL(Rung::Medium, d.target);   // one rung, not two
}

// --- ALERT policy (c): promotions withheld while urgent traffic waits --------

static void test_urgent_pending_withholds_promotion(void) {
    RateController rc(Rung::Far);
    const float good = stepUpThresholdDb(Rung::Far, rc.params().fadeMarginDb) + 5.0f;
    uint32_t t = 100000;
    for (uint8_t i = 0; i < rc.params().stepUpConsecutive + 2; ++i) {
        rc.onGoodFrame(good, t);
        t += 1000;
    }
    // Earned a promotion...
    TEST_ASSERT_TRUE(rc.evaluate(t).change);

    // ...but an ALERT is waiting. Promoting makes the link faster and therefore
    // more fragile, at exactly the wrong moment.
    rc.setUrgentPending(true);
    TEST_ASSERT_FALSE(rc.evaluate(t).change);

    // Released once the urgent message is away.
    rc.setUrgentPending(false);
    TEST_ASSERT_TRUE(rc.evaluate(t).change);
}

// Retreat is NOT gated: making the link more robust is always allowed, urgent
// traffic or not.
static void test_urgent_pending_still_allows_retreat(void) {
    RateController rc(Rung::Medium);
    rc.setUrgentPending(true);
    rc.onBadFrame(100000);
    const auto d = rc.evaluate(100000);
    TEST_ASSERT_TRUE(d.change);
    TEST_ASSERT_EQUAL(Rung::Far, d.target);
}

// Nor is the silence watchdog: falling back to the anchor is the one thing that
// recovers a dead link, and an alert waiting makes that more important, not less.
static void test_urgent_pending_still_allows_watchdog_fallback(void) {
    RateController rc(Rung::Fast);
    rc.begin(1000);
    rc.setUrgentPending(true);
    const auto d = rc.evaluate(1000 + rc.params().silenceMs);
    TEST_ASSERT_TRUE(d.change);
    TEST_ASSERT_TRUE(d.unilateral);
    TEST_ASSERT_EQUAL(Rung::Far, d.target);
}

int main(void) {
    UNITY_BEGIN();
    RUN_TEST(test_ladder_direction_is_consistent);
    RUN_TEST(test_ladder_is_the_approved_one);
    RUN_TEST(test_rung_movement_saturates);
    RUN_TEST(test_rung_names_parse);
    RUN_TEST(test_medium_is_single_fragment_where_far_is_not);
    RUN_TEST(test_step_up_thresholds_match_the_approved_numbers);
    RUN_TEST(test_step_down_thresholds_match);
    RUN_TEST(test_snr_byte_roundtrip_and_clamping);
    RUN_TEST(test_rate_message_codec);
    RUN_TEST(test_promotion_needs_sustained_evidence);
    RUN_TEST(test_one_mediocre_frame_resets_the_streak);
    RUN_TEST(test_cannot_promote_past_fastest);
    RUN_TEST(test_one_bad_frame_retreats_immediately);
    RUN_TEST(test_marginal_snr_retreats_before_the_link_breaks);
    RUN_TEST(test_cannot_retreat_past_most_robust);
    RUN_TEST(test_silence_falls_back_to_anchor_unilaterally);
    RUN_TEST(test_prolonged_silence_reaches_the_deep_anchor);
    RUN_TEST(test_watchdog_ignores_the_hold_window);
    RUN_TEST(test_hold_window_prevents_oscillation);
    RUN_TEST(test_decisions_follow_the_weaker_direction);
    RUN_TEST(test_peer_reporting_a_bad_link_forces_retreat);
    RUN_TEST(test_stale_peer_snr_is_ignored);
    RUN_TEST(test_adaptation_can_be_frozen);
    RUN_TEST(test_force_rung_pins_and_disables);
    RUN_TEST(test_applied_clears_stale_evidence);
    RUN_TEST(test_proposer_does_not_switch_on_its_own_transmission);
    RUN_TEST(test_accepter_switches_once_its_reply_is_sent);
    RUN_TEST(test_proposer_applies_only_on_accept);
    RUN_TEST(test_stale_or_crossed_accepts_are_ignored);
    RUN_TEST(test_unanswered_proposal_times_out_without_switching);
    RUN_TEST(test_incoming_proposal_overrides_our_own);
    RUN_TEST(test_reset_abandons_a_handshake);
    RUN_TEST(test_two_nodes_converge);
    RUN_TEST(test_lost_accept_splits_then_both_fall_back_to_anchor);
    RUN_TEST(test_begin_arms_the_watchdog_from_boot);
    RUN_TEST(test_observed_silence_is_reported);
    RUN_TEST(test_silence_threshold_is_runtime_adjustable);
    RUN_TEST(test_a_received_frame_resets_the_silence_clock);
    RUN_TEST(test_step_up_thresholds_rise_with_target_speed);
    RUN_TEST(test_best_rung_sizes_the_jump_to_the_margin);
    RUN_TEST(test_jump_is_capped);
    RUN_TEST(test_promotion_jumps_two_rungs_in_one_cycle);
    RUN_TEST(test_streak_floor_not_latest_sample_sizes_the_jump);
    RUN_TEST(test_retreat_remains_single_step_and_immediate);
    RUN_TEST(test_urgent_pending_withholds_promotion);
    RUN_TEST(test_urgent_pending_still_allows_retreat);
    RUN_TEST(test_urgent_pending_still_allows_watchdog_fallback);
    return UNITY_END();
}
