# Adaptive SF/BW — design proposal

**Status: APPROVED AND IMPLEMENTED.** Ladder, thresholds, SNR-on-ACK feedback and
the unilateral-fallback negotiation were approved as specified and built in
`lib/protocol/rate_control.{h,cpp}`, wired through `src/link.cpp` and
`src/radio.cpp`. 25 native tests in `test/test_rate/` pin every threshold and
transition in this document.

---

## 0. Direction check — your statement is correct

Confirmed before anything else, since getting it backwards would make "long
range" mode worse than "fast" mode:

- **Lower SF → faster, shorter range.** Symbol time is `2^SF / BW`, so each SF
  step down halves airtime and costs 2.5 dB of processing gain.
- **Wider BW → faster, shorter range.** Wider bandwidth shortens the symbol *and*
  admits more thermal noise — the floor rises 3 dB per doubling, so sensitivity
  worsens by 3 dB.
- **Narrower BW → slower, longer range.**

They move together. Fast-and-close = **low SF + wide BW**. Slow-and-far =
**high SF + narrow BW**. The numbers below confirm this holds end to end: FAST
is 45× faster than MAX and 15.5 dB less sensitive.

---

## 1. The ladder — real numbers

Airtime from the existing calculator (the one verified against your six measured
SF7–SF12 values). Sensitivity is `-174 + 10log₁₀(BW) + NF + SNR_required`, with
NF = 6 dB and SX1262 datasheet SNR figures.

| Rung | SF | BW | Tsym | 90 B | 168 B | 254 B | Sensitivity | vs FAST | Range ×(n=2) | ×(n=3) |
|---|---|---|---|---|---|---|---|---|---|---|
| **FAST** | 7 | 250 kHz | 0.51 ms | 85 ms | 141 ms | 200 ms | −121.5 dBm | — | 1.0× | 1.0× |
| **MEDIUM** | 9 | 125 kHz | 4.10 ms | 534 ms | 902 ms | 1250 ms | −129.5 dBm | +8.0 dB | 2.5× | 1.8× |
| **FAR** | 10 | 125 kHz | 8.19 ms | 985 ms | 1640 ms | 2255 ms | −132.0 dBm | +10.5 dB | 3.4× | 2.2× |
| **MAX** | 12 | 125 kHz | 32.77 ms | 3940 ms | 6398 ms | 9019 ms | −137.0 dBm | +15.5 dB | 6.0× | 3.3× |

`n=2` is free space (optimistic); `n=3` is a realistic obstructed environment.
Take the `n=3` column as the honest one.

### The rung choice changes FRAGMENT COUNT, not just airtime

This is the consequence that is easy to miss. A maximum-size app payload is
**247 bytes**, which becomes a **254-byte** LoRa frame — inside the SX1262's
255-byte limit with one byte spare. So it *physically* fits one frame at every
rung. But the 2200 ms per-fragment budget does not:

| Rung | 254 B time-on-air | Within 2200 ms budget? | Fragments for a max message |
|---|---|---|---|
| FAST | 200 ms | yes | **1** |
| **MEDIUM** | **1250 ms** | **yes** | **1** |
| FAR | 2255 ms | **no, by 55 ms** | **2** |
| MAX | 9019 ms | no | 7 |

**MEDIUM (SF9) is the slowest rung at which a maximum-size message is genuinely
single-fragment. FAR (SF10) is not** — it misses by 55 ms of airtime, not by any
size limit. Stepping FAST→MEDIUM costs latency; stepping MEDIUM→FAR additionally
introduces a second fragment, and with it a second packet-loss opportunity and a
reassembly window. That is a step change in failure modes, not a smooth
degradation, and it is worth knowing which rung boundary it sits on.

Pinned by `test_medium_is_single_fragment_where_far_is_not`.

**Read the 254 B column against the 2200 ms budget.** Only FAST and MEDIUM carry
a maximum-size message inside budget. FAR is 55 ms over; MAX is four times over.
That is the single most useful thing this table says.

### Comments on the proposed rungs

- **The four rungs are well spaced** — roughly 8, 10.5, 15.5 dB — with no
  redundant rung. Worth keeping.
- **FAST at SF7/250k is aggressive.** It is 45× faster than MAX but gives up
  everything; it is only usable at close range. That is exactly its job.
- **MEDIUM and FAR are only 2.5 dB apart** — the smallest gap in the ladder, for
  a 1.8× airtime cost. Consider dropping MEDIUM to SF8/125k (+5 dB from FAST,
  ~2× faster than FAR) for more even spacing. **Your call — flagging, not
  changing.**
- **MAX is close to unusable for data**: 9 s for a full message, and one 7-byte
  control frame costs 991 ms. Treat it as a rescue rung, not a working one.

### Control-frame cost (7-byte ACK/BEACON)

| FAST | MEDIUM | FAR | MAX |
|---|---|---|---|
| 18 ms | 124 ms | 248 ms | **991 ms** |

This is what kills the beacon-channel idea below.

---

## 2. Negotiation — why the proposed beacon channel cannot be built

> *"the SLOWEST/MOST ROBUST config as a fixed always-listening beacon channel for
> negotiation, with actual data sent at whatever faster config was just agreed"*

**As literally specified this is not implementable on this hardware, and the
reason is not a detail.**

A LoRa radio demodulates **one** configuration at a time. The SX1262 cannot
listen at SF12/125k and SF7/250k simultaneously — preamble detection is
config-specific. An "always-listening" control channel therefore needs either a
second radio (we have one) or time-slicing between configs. Time-slicing means
both nodes must agree on a schedule — which is precisely the agreement the
channel exists to negotiate. It is circular.

Two further problems even if the hardware allowed it: a control frame at SF12
costs **991 ms**, so a two-way handshake is ~2 s of pure overhead; and any
listening time spent on the control config is time not spent receiving data.

### The instinct is right — keep it, restructure it

The valuable idea is *"there is a config both nodes can always fall back to"*.
That should be a **rendezvous state**, not a parallel channel:

> **In-band negotiation + unilateral anchor fallback.**

1. **Data and control both travel at the current config.** One radio, one state.
2. **A change is proposed in-band, at the config that currently works.** Sender
   emits a `BEACON` control packet: *"switching to rung N"*.
3. **Receiver ACKs at the current config, then switches.** Sender switches only
   on receiving that ACK.
4. **No ACK → no change.** The sender stays where it is. The failure mode is
   "nothing happened", never "half the link moved".
5. **Anchor fallback:** both nodes run a *no valid frame for T_silence* watchdog.
   On expiry each **unilaterally** reverts to the anchor rung.

**Why this is the right pattern:** the announcement travels over a link that is
*currently known to work*, so it is maximally likely to arrive — whereas a
control channel at SF12 is a link you have no recent evidence about. And the
recovery path requires **no communication at all**: both sides independently time
out and land on the same rung. That is what makes it robust exactly when
communication has failed, which is the one moment a handshake cannot help you.

### Choosing the anchor — a real tradeoff

| Anchor | Rendezvous robustness | Cost of every recovery |
|---|---|---|
| **FAR** (SF10/125k) | good | 248 ms control, 2255 ms data |
| **MAX** (SF12/125k) | best | 991 ms control, 9019 ms data |

**Proposal: anchor at FAR, with MAX as a second-tier rendezvous.** If FAR
rendezvous fails for 3 × T_silence, try MAX before giving up. That is a
two-config scan, not a four-config one, so worst-case reacquisition stays
bounded and cheap. FAR is also the currently proven working config.

### The asymmetry problem — needs your decision

RSSI/SNR is measured at the **receiver**, but the **transmitter** chooses the
rung. A→B and B→A are not necessarily equal. So a node cannot learn its own
outbound link quality without being told.

Cleanest fix that costs nothing on the data path: **carry the observed SNR in the
control/ACK frame payload**. An ACK is currently 0 payload bytes; adding one
signed byte (SNR in ¼ dB steps) costs ~8 ms at SF10 and nothing at all on data
packets. The alternative — a byte in the data header — costs 8 ms on *every*
fragment forever, which is the wrong trade.

**Approved and built.** The reason is worth stating explicitly, because it is
the property that makes the mechanism safe rather than merely cheap:

> An SNR byte that rides **only on ACKs** means rate feedback exists exactly when
> packets are getting through — which is the only time stepping **up** is safe to
> consider. When packets stop, feedback stops, and the silence watchdog handles
> it. **The mechanism degrades in the correct direction.**

A byte in every data header would tax every fragment forever to carry
information that matters rarely, and would keep supplying promotion evidence
right up to the moment the link died — exactly backwards.

Implementation: `encodeSnr()` / `decodeSnr()` in `rate_control.h`, signed
quarter-dB steps covering −32.00 to +31.75 dB. It rides on `PacketType::Ack`
(1 byte) and on `PacketType::Beacon` rate frames (3 bytes: opcode, SNR, rung).
Receiving a completed message now emits an ACK carrying the observed SNR — the
first ACK traffic in the project, at ~254 ms per ACK at SF10.

---

## 3. Thresholds — asymmetric by design

Step **up** slowly and only on repeated evidence; step **down** instantly on any
evidence. Silence is worse than slowness.

### Step up (faster) — conservative

Requires **8 consecutive** frames above threshold. Thresholds account for both
the next rung's SNR floor and, where BW changes, the 3 dB the measured SNR loses
when the noise bandwidth doubles. Fade margin is **6 dB**.

| Transition | Need measured SNR ≥ | Made of |
|---|---|---|
| MAX → FAR | **−9.0 dB** | floor −15.0, BW shift 0, margin +6 |
| FAR → MEDIUM | **−6.5 dB** | floor −12.5, BW shift 0, margin +6 |
| MEDIUM → FAST | **+1.5 dB** | floor −7.5, **BW shift +3.0**, margin +6 |

Note MEDIUM → FAST is the only transition that changes bandwidth, and it is
correspondingly the hardest to earn — as it should be, since it is the biggest
single drop in robustness.

### Step down (more robust) — immediate

**Any one** of these retreats a rung at once, no counter:

- one frame that fails CRC or fails to decode
- one transmit that gets no expected response
- one frame whose SNR is below the tripwire:

| Rung | Own floor | Retreat if SNR < |
|---|---|---|
| FAST | −7.5 dB | **−5.5 dB** |
| MEDIUM | −12.5 dB | **−10.5 dB** |
| FAR | −15.0 dB | **−13.0 dB** |
| MAX | −20.0 dB | — (nowhere lower) |

The 2 dB tripwire fires *before* the link actually breaks, which is the point —
retreating from a link that still works is cheap; recovering from one that
stopped working costs a full rendezvous.

### Timers

| Parameter | Proposed | Rationale |
|---|---|---|
| `STEP_UP_CONSECUTIVE` | 8 frames | At ~1 message per 5 s, ~40 s of sustained good link before speeding up |
| `STEP_DOWN_EVENTS` | 1 | Immediate |
| `T_SILENCE` | 60 s | ~2× the reassembly timeout, so a slow multi-fragment message never trips it |
| `T_DEEP_RENDEZVOUS` | 180 s | 3 × T_SILENCE before trying the MAX anchor |
| `HOLD_AFTER_CHANGE` | 10 s | Prevents oscillation across a threshold |

**All five are the numbers to argue with.** `STEP_UP_CONSECUTIVE` and the 6 dB
margin are the two that most change behaviour.

---

## 4. Module boundary

The rest of the firmware must not know adaptation exists.

```
                 ┌─────────────────────────────────────┐
  BLE adapter    │  knows nothing about rungs           │
  LoraLink       │  knows nothing about rungs           │
                 └─────────────────────────────────────┘
                                │ feeds observations
                                ▼
                 ┌─────────────────────────────────────┐
  RateController │  PURE LOGIC. No Arduino, no radio.   │
  (new)          │  In → SNR, CRC results, timeouts     │
                 │  Out → "change to rung N", or nothing│
                 └─────────────────────────────────────┘
                                │ decision only
                                ▼
                 ┌─────────────────────────────────────┐
  Sx1262Radio    │  gains applyRung(); nothing else     │
                 └─────────────────────────────────────┘
```

Proposed home: **`lib/protocol/rate_control.{h,cpp}`** — no Arduino dependency,
so the whole state machine is natively testable. That matters more here than
anywhere else in the project: testing adaptation on real hardware means
physically walking away from a board, and every threshold change would mean
walking again.

```cpp
namespace lorax {

enum class Rung : uint8_t { Fast = 0, Medium = 1, Far = 2, Max = 3 };

struct RungConfig {
    uint8_t  sf;
    uint32_t bandwidthHz;
    uint8_t  codingRate;
    float    requiredSnrDb;
};
const RungConfig& rungConfig(Rung r);

class RateController {
public:
    struct Decision {
        bool        change = false;
        Rung        target = Rung::Far;
        const char* reason = "";     // for the log; adaptation must be explainable
    };

    // Observations in. None of these touch hardware.
    void onGoodFrame(float snrDb, uint32_t nowMs);
    void onBadFrame(uint32_t nowMs);
    void onPeerReportedSnr(float snrDb, uint32_t nowMs);   // from the ACK byte
    void tick(uint32_t nowMs);                             // drives T_SILENCE

    // Decision out. Caller applies it and confirms.
    Decision evaluate(uint32_t nowMs) const;
    void     applied(Rung r, uint32_t nowMs);

    // Manual override — section 5.
    void setAdaptive(bool on);
    void forceRung(Rung r);
    bool adaptive() const;
    Rung current() const;
};

}  // namespace lorax
```

`LoraLink` gains exactly two call sites — `onGoodFrame`/`onBadFrame` in
`serviceRx`, and an `evaluate`/`applied` pair in `loop`. Nothing else in the
firmware changes.

---

## 5. Manual override — required for A/B testing

Adaptation must be switchable **off** and pinnable to one rung, because a range
test where the config silently changes underneath you measures nothing.

- `setAdaptive(false)` freezes the current rung.
- `forceRung(r)` pins a specific one and implies `setAdaptive(false)`.
- Serial debug commands in `main.cpp`: `rate off`, `rate fast|medium|far|max`,
  `rate auto`, `rate status`.
- A compile-time `-DRATE_FIXED=2` for flashing a board that can never adapt —
  the safest configuration for a controlled measurement run.

**Recommendation: demo with adaptation on, range-test with it pinned.** They are
different experiments and mixing them produces uninterpretable data.

---

## ALERT priority — DECIDED AND IMPLEMENTED

Status: the queueing bug is fixed, **(c)** is built, **(b)** waits for ARQ, and
**(a) is rejected permanently** — see the reasoning below, which is recorded
here specifically so it does not get re-proposed. Today an `ALERT` is treated identically to a
`NORMAL` message: same rung, same single transmission, same queue. That is a
policy by default rather than by choice, and "we never thought about it" is a
bad answer to *"what happens to an emergency message on a marginal link?"*

### What already helps, and is worth saying out loud

The rate controller retreats on a single bad frame and only promotes after
sustained evidence. So on a degrading link the radio is **already** at the most
robust rung conditions warrant by the time an alert is sent. Alert handling does
not have to rediscover the link quality — that work is done. This is a real
answer to the judge's question, just not a complete one.

### The queueing bug — FIXED

`LoraLink::sendText()` used to return `Busy` while any message was in flight,
and the BLE adapter logged and **dropped** it. An ALERT raised during a NORMAL
transmission simply vanished — silent loss, before the radio was involved, so
none of the CRC machinery could catch it.

Fixed by `lib/protocol/tx_queue.h`: a two-slot priority queue with
**fragment-boundary preemption**. `test_alert_during_in_flight_normal_is_
delivered_not_dropped` is the permanent regression guard.

#### Why preemption happens at fragment boundaries, not mid-fragment

Worst-case ALERT delay behind a full-size message, measured with the airtime
calculator:

| Rung | Queue behind whole message | Preempt at fragment boundary | Preempt mid-fragment |
|---|---|---|---|
| FAST | 200 ms | 200 ms | ~0 ms |
| MEDIUM | 1250 ms | 1250 ms | ~0 ms |
| FAR | 2503 ms | 2173 ms | ~0 ms |
| MAX | **14475 ms** | **2138 ms** | ~0 ms |

Waiting for the whole message is far worse than the ~4.5 s first assumed — at
MAX a full message is seven fragments and **14.5 seconds**. That rules out
queue-and-wait.

But mid-fragment abort is not the answer either. It buys at most ~2.2 s over
the fragment-boundary option, and costs **the entire in-flight message**: there
is no ARQ to recover it, so an alert would be paid for by destroying a message
the user believed was sent. It also strands the fragments already delivered in
the peer's reassembler until they time out.

**Fragment-boundary preemption is bounded by the per-fragment airtime budget at
every rung** — because that budget is what sized the fragments in the first
place. It gets within ~2.2 s of the theoretical best while destroying nothing.
Interleaving is safe by construction: the reassembler is keyed by `msgId`,
tolerates out-of-order arrival, and holds three slots.

Ordering rules, all in `TxQueue`:

- ALERTs are served before any queued NORMAL.
- An ALERT with no free slot **displaces a NORMAL that has not yet transmitted**
  a fragment — but never one already on the air, which would strand fragments
  the peer is holding.
- A genuinely full queue returns `Full`, and the BLE adapter **surfaces it**:
  the STATUS characteristic gains `send_failed` / `alert_failed` counters and is
  pushed immediately rather than at the next tick. A message the radio would not
  take is a message the user believes was sent; it must never vanish.

### The four options

#### (a) Send ALERTs one rung more robust — **REJECTED PERMANENTLY. Do not re-propose.**

Superficially obvious, and actively dangerous. **Both nodes must agree on the
rung.** A transmitting at rung+1 while B listens at the current rung is not a
weaker link — B hears *nothing*. So this requires negotiating down, sending,
then negotiating back.

That negotiation must succeed **at the current rung** — the very rung we already
suspect. It costs two control frames (~500 ms at FAR, ~2 s at MAX), and a lost
`Propose` means a multi-second timeout before the alert even starts. It adds a
new failure mode to the message that can least afford one, in exchange for
2.5 dB.

**Inverts the risk.** The reasoning generalises, and is the part worth
remembering: **a unilateral transmission at a "more robust" configuration is not
a weaker link, it is silence.** Both nodes must agree on the rung; a receiver on
the wrong configuration hears nothing at all. Anything that changes
configuration therefore must not sit in the path of an urgent message.

This option looks like the obvious answer and is the one most likely to be
suggested again. It is recorded here so that it is not.

#### (b) More aggressive retry count for ALERTs — **recommend, once ARQ exists**

The right mechanism, for reasons (a) is the wrong one:

- Retries happen at the **current, already-agreed rung**. No negotiation, no
  split-brain window, no new failure mode.
- Fading is time-varying, so each retry is a genuinely independent attempt — a
  frame that fails now may well succeed in two seconds.
- Cost is bounded and predictable: N × airtime, no handshake latency.

At FAR a 254 B frame is 2255 ms, so three attempts is ~6.8 s worst case. If a
single attempt succeeds half the time, three take delivery from 50% to ~88% —
comfortably more than the ~2.5 dB option (a) buys, at no configuration risk.

**Blocked on ARQ, which does not exist.** `PacketType::Nack` is still unused.
This is where alert priority belongs when that lands.

#### (c) ALERT suppresses a pending rung-upgrade — **IMPLEMENTED**

A promotion makes the link *more fragile*. Doing that while an urgent message is
queued is plainly wrong, and the same applies to starting any rung negotiation:
the handshake occupies the radio and can leave us mid-switch exactly when the
alert needs to go.

`RateController::setUrgentPending()`, driven from `LoraLink::loop()` off
`TxQueue::alertPending()`. While set, `evaluate()` withholds promotions.
**Retreats and the silence watchdog stay live** — both make the link more
reliable, and a waiting alert makes recovery more important, not less. Three
tests cover the gating and the two exemptions.

#### (d) Do nothing — **the honest baseline, but incomplete**

Defensible as far as it goes: the controller already keeps the link at the
fastest rung that works, and an alert cannot change the physics. But the
`ALERT` bit exists precisely so the system *can* treat these differently, and
options (c) and the queueing fix cost almost nothing.

### Recommendation

| Status | Change |
|---|---|
| **done** | Priority queue with fragment-boundary preemption; failures surfaced to the app |
| **done** | (c) promotions withheld while an alert is pending |
| **waiting on ARQ** | (b) alert-specific retry count — the real mechanism |
| **rejected** | (a) send at a more robust rung — breaks the shared-rung invariant |

**(1) was a bug, (2) was free, (3) is the real mechanism, and (a) is a trap that
looks like the obvious answer.**

## A bug this design nearly shipped with

The first implementation used a single `rungPending_` flag for both sides of the
handshake. **The two roles are not symmetric**, and conflating them meant:

- the **proposer** switched as soon as its own `Propose` finished transmitting,
  without ever waiting for the peer's `Accept`;
- so every rung change was effectively unilateral, and split-brain occurred
  whenever the *Propose* was lost — not just the rarer lost-*Accept* case the
  design accounts for.

It compiled, it looked right, and no test could reach it, because the logic
lived in `LoraLink` alongside the radio. The fix was to extract it as
`RungNegotiator` in `rate_control.h` — a pure state machine with no I/O:

| Role | Trigger | Action |
|---|---|---|
| proposer | own TX drains | **nothing** — the peer may never have heard it |
| proposer | `Accept` arrives for the rung it asked for | apply |
| proposer | timeout | give up, stay put |
| accepter | `Propose` arrives | send `Accept` |
| accepter | own TX drains | **apply** — the reply had to leave at the old rung |

Nine tests now cover it, including both roles' TX-idle behaviour, crossed
proposals, stale accepts, and the two-node convergence and split-brain paths.
The general lesson: **logic that cannot be reached by a test will eventually be
wrong, and reading it is not a substitute.**

## Settled

1. **Ladder** — FAST SF7/250k, MEDIUM SF9/125k, FAR SF10/125k, MAX SF12/125k,
   as specified. Built.
2. **Anchor** — FAR, with MAX as the deep rendezvous after 3 × T_SILENCE.
3. **SNR feedback byte on ACK/control frames only.** Approved, built.
4. **Asymmetric thresholds** with +3 dB extra on MEDIUM→FAST for the bandwidth
   change. Built, and derived from the ladder rather than hardcoded so they stay
   correct if a rung ever changes.
5. **Unilateral fallback on the silence watchdog**, chosen over a parallel
   beacon channel — a LoRa radio demodulates one configuration at a time, which
   makes a separate always-listening control channel circular.

## Still open, deliberately

- **MEDIUM at SF9 or SF8?** SF9 sits only 2.5 dB from FAR. Left as approved.
- **`stepUpConsecutive = 8`** may feel sluggish in a demo. It is a runtime
  field (`rate().params().stepUpConsecutive`), so it can be tuned without a
  rebuild once there is real link data.
- **Split-brain window.** If an `Accept` is lost, one node switches and the
  other does not. This is the Two Generals problem and **no handshake fixes
  it** — the bound is the silence watchdog, after which both land on the anchor
  without communicating. Pinned by
  `test_lost_accept_splits_then_both_fall_back_to_anchor`.

  **`silenceMs` is now runtime-adjustable rather than an inherited guess:**

  ```
  rate silence <ms>        # 2000..600000, default stays 60000
  rate silence             # report the current threshold and observed silence
  ```

  Every watchdog fallback logs **the silence it actually observed**, not just
  that a threshold was crossed:

  ```
  [rate] FAST -> FAR (silence watchdog: falling back to anchor;
                      silent 61240 ms vs 60000 ms threshold)
  ```

  So range testing produces data on what legitimate silence looks like at each
  rung, and the default becomes a measured decision. The 2 s floor exists
  because a single 254 B frame at MAX is ~9 s — a multi-fragment message can
  legitimately be silent for tens of seconds, and a watchdog below that would
  fire on healthy traffic. Raising `silenceMs` past half of `deepRendezvousMs`
  raises the deep threshold with it, so the two cannot invert.
