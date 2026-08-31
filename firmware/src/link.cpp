#include "link.h"

#include <Arduino.h>

#include <cstring>

#include "airtime.h"
#include "codec_hook.h"
#include "radio_config.h"

namespace lorax {

namespace {
// Per-fragment airtime budget. TTS worst case is 2000 ms and STT ~800 ms, so a
// ~5 s end-to-end target leaves roughly this much for the radio.
constexpr double FRAGMENT_BUDGET_MS = 2200.0;
}  // namespace

void LoraLink::setLogMode(LogMode m) {
    logMode_ = m;
    if (m == LogMode::Csv) {
        char header[LOG_LINE_MAX];
        if (formatCsvHeader(header, sizeof(header)) > 0) Serial.println(header);
    }
}

void LoraLink::emitLog(const LogEvent& e) {
    if (logMode_ == LogMode::Off) return;
    char line[LOG_LINE_MAX];
    const size_t n = (logMode_ == LogMode::Csv) ? formatCsvRow(e, line, sizeof(line))
                                                : formatHuman(e, line, sizeof(line));
    if (n > 0) Serial.println(line);
}

void LoraLink::logMark(const char* text, uint32_t nowMs) {
    LogEvent e;
    e.timestampMs = nowMs;
    e.direction   = LogDirection::Mark;
    e.rung        = radio_.rung();
    e.note        = text;
    // Markers print even with logging off, so a mark is never silently lost.
    if (logMode_ == LogMode::Off) {
        Serial.printf("[mark] %s\n", text ? text : "");
        return;
    }
    emitLog(e);
}

// Frames the SX1262's own hardware CRC rejected never reach us, but during a
// range test they are exactly the "almost heard it" evidence that shows where
// the link starts failing. Picked up as a counter delta.
void LoraLink::logRadioErrors(uint32_t nowMs) {
    const uint32_t total = radio_.stats().rxErrors;
    while (loggedRxErrors_ < total) {
        ++loggedRxErrors_;
        LogEvent e;
        e.timestampMs  = nowMs;
        e.direction    = LogDirection::Rx;
        e.rung         = radio_.rung();
        e.result       = LogResult::RadioError;
        e.hasRadioInfo = false;
        e.peerSnrDb    = rate_.lastPeerSnrDb();
        emitLog(e);
    }
}

const char* LoraLink::sendResultName(SendResult r) {
    switch (r) {
        case SendResult::Ok:              return "Ok";
        case SendResult::Queued:          return "Queued";
        case SendResult::PreemptedQueued: return "PreemptedQueued";
        case SendResult::QueueFull:       return "QueueFull";
        case SendResult::TooLong:         return "TooLong";
        case SendResult::SerializeFailed: return "SerializeFailed";
        case SendResult::RadioRefused:    return "RadioRefused";
    }
    return "Unknown";
}

bool LoraLink::begin() {
    if (radio_.begin() != Sx1262Radio::Result::Ok) return false;
    // Arm the silence watchdog now, so a node that boots on a non-anchor rung
    // and hears nothing still finds its way back to the anchor.
    rate_.begin(millis());
    return true;
}

// ---------------------------------------------------------------------------
// SEND PATH
// ---------------------------------------------------------------------------
LoraLink::SendResult LoraLink::sendText(const uint8_t* text, size_t len,
                                        uint8_t langId, bool alert,
                                        uint8_t appType, uint32_t nowMs) {
    (void)nowMs;
    if (text == nullptr || langId > MAX_LANGUAGE_ID) return SendResult::TooLong;

    // --- 1. COMPRESS THE WHOLE MESSAGE, on payload bytes only ---------------
    // Before any framing exists. No header has been built yet, so there is
    // nothing here but the app's text.
    const uint8_t* body    = text;
    size_t         bodyLen = len;
    uint8_t        flags   = alert ? FLAG_ALERT : 0;

    size_t compressedLen = 0;
    if (compressMessage(text, len, codecBuf_, sizeof(codecBuf_), compressedLen)) {
        // Policy: only if it actually got smaller. compressMessage() enforces
        // this too, but the caller must never be the one that trusts blindly.
        if (compressedLen < len) {
            body    = codecBuf_;
            bodyLen = compressedLen;
            flags  |= FLAG_COMPRESSED;
        }
    }

    // --- 2. FRAGMENT the (possibly compressed) body -------------------------
    // Follows the rung in force right now: at FAST a whole message fits one
    // frame, at MAX it does not. Adaptation changes fragment counts, not just
    // airtime.
    const LoRaParams p = radio_.currentParams();
    const size_t frameCap = maxPayloadForBudget(p, FRAGMENT_BUDGET_MS);
    const size_t chunk    = maxFragmentPayload(frameCap);
    if (chunk == 0) return SendResult::RadioRefused;

    FragmentOptions opt;
    opt.msgId  = nextMsgId_;
    opt.langId = langId;
    opt.flags   = flags;  // identical on every fragment, including COMPRESSED
    opt.appType = appType;
    opt.type    = PacketType::Data;

    size_t count = 0;
    const FragmentResult fr =
        fragment(body, bodyLen, chunk, opt, fragScratch_, MAX_FRAGMENTS, count);
    if (fr != FragmentResult::Ok) {
        Serial.printf("[link] fragment failed: %s (%zu B, chunk %zu)\n",
                      fragmentResultName(fr), bodyLen, chunk);
        return SendResult::TooLong;
    }

    const bool wasIdle = tx_.empty();
    const TxQueue::AdmitResult ar =
        tx_.admit(fragScratch_, static_cast<uint8_t>(count), alert);

    if (ar == TxQueue::AdmitResult::Full) {
        // Never swallowed. The caller has to tell the user.
        ++counters_.sendsRefused;
        Serial.printf("[link] REFUSED %s message: transmit queue full\n",
                      alert ? "ALERT" : "normal");
        return SendResult::QueueFull;
    }

    ++nextMsgId_;
    if (ar == TxQueue::AdmitResult::EvictedQueued) {
        ++counters_.alertsPreempted;
        Serial.println("[link] ALERT displaced a waiting normal message");
        return SendResult::PreemptedQueued;
    }
    if (!wasIdle) {
        ++counters_.messagesQueued;
        return SendResult::Queued;
    }
    return SendResult::Ok;
}

// Fragments are serialized one at a time, immediately before transmission.
// serialize() is what appends the CRC, so the CRC always covers the exact bytes
// that go on air - header plus the already-compressed payload.
bool LoraLink::queueControl(const Packet& p) {
    if (hasPendingControl_) return false;  // one slot; control frames are rare
    pendingControl_ = p;
    hasPendingControl_ = true;
    return true;
}

void LoraLink::sendAck(uint8_t msgId, float observedSnr) {
    Packet p = makeControl(PacketType::Ack, msgId, 0, 1);
    const int8_t snr = encodeSnr(observedSnr);
    p.setPayload(reinterpret_cast<const uint8_t*>(&snr), 1);
    if (queueControl(p)) ++counters_.acksSent;
}

void LoraLink::sendRate(RateOpcode op, Rung rung, float observedSnr) {
    Packet p = makeControl(PacketType::Beacon, 0, 0, 1);
    RateMessage m;
    m.opcode = op;
    m.snr    = encodeSnr(observedSnr);
    m.rung   = rung;
    uint8_t body[RATE_MESSAGE_BYTES];
    const size_t n = encodeRateMessage(m, body, sizeof(body));
    p.setPayload(body, n);
    queueControl(p);
}

void LoraLink::handleAckFrame(const Packet& p, uint32_t nowMs) {
    ++counters_.acksReceived;
    if (p.payloadLen >= 1) {
        // What the PEER heard from US. The link is only as good as its weaker
        // direction, and both ends must run the same rung.
        rate_.onPeerReportedSnr(decodeSnr(static_cast<int8_t>(p.payload[0])), nowMs);
    }
}

void LoraLink::handleRateFrame(const Packet& p, uint32_t nowMs) {
    RateMessage m;
    if (!decodeRateMessage(p.payload, p.payloadLen, m)) return;
    rate_.onPeerReportedSnr(decodeSnr(m.snr), nowMs);

    switch (m.opcode) {
        case RateOpcode::Report:
            break;

        case RateOpcode::Propose: {
            if (!rate_.adaptive()) break;
            const RungNegotiator::Step step = negotiator_.onPropose(m.rung, nowMs);
            if (step.action == RungNegotiator::Action::SendAccept) {
                sendRate(RateOpcode::Accept, step.rung, rate_.lastLocalSnrDb());
                Serial.printf("[rate] peer proposed %s - accepting\n",
                              rungName(step.rung));
            }
            break;
        }

        case RateOpcode::Accept: {
            const RungNegotiator::Step step = negotiator_.onAccept(m.rung, nowMs);
            if (step.action == RungNegotiator::Action::ApplyRung) {
                radio_.applyRung(step.rung);
                rate_.applied(step.rung, nowMs);
                ++counters_.rungChanges;
                Serial.printf("[rate] peer accepted - now at %s\n",
                              rungName(step.rung));
            }
            break;
        }
    }
}

// Applies a decision, negotiating first unless it is a unilateral fallback.
void LoraLink::serviceRate(uint32_t nowMs) {
    // Our transmit queue has drained. Only a pending ACCEPTANCE switches here;
    // a proposal of ours going out means nothing until the peer replies.
    if (!radio_.txBusy() && !hasPendingControl_) {
        const RungNegotiator::Step step = negotiator_.onTxIdle(nowMs);
        if (step.action == RungNegotiator::Action::ApplyRung) {
            radio_.applyRung(step.rung);
            rate_.applied(step.rung, nowMs);
            ++counters_.rungChanges;
            return;
        }
    }

    if (negotiator_.state() == RungNegotiator::State::AwaitingAccept) {
        // Round trip is two control frames plus processing; scale it to the
        // rung so MAX (~991 ms per control frame) gets a fair wait.
        const uint32_t timeout = static_cast<uint32_t>(
            radio_.airtimeMs(OVERHEAD + RATE_MESSAGE_BYTES) * 4.0) + 1000;
        const RungNegotiator::State before = negotiator_.state();
        negotiator_.onTimeout(nowMs, timeout);
        if (before != negotiator_.state()) {
            Serial.printf("[rate] no Accept within %lu ms - staying at %s\n",
                          static_cast<unsigned long>(timeout),
                          rungName(radio_.rung()));
        }
        return;
    }

    const RateController::Decision d = rate_.evaluate(nowMs);
    if (!d.change) return;

    if (d.unilateral) {
        // No handshake: the peer is unreachable by definition. Both nodes run
        // this independently and land on the same rung without talking. The
        // observed silence is logged so the threshold can be tuned against real
        // data rather than left at whatever it was first guessed to be.
        Serial.printf("[rate] %s -> %s (%s; silent %lu ms vs %lu ms threshold)\n",
                      rungName(radio_.rung()), rungName(d.target), d.reason,
                      static_cast<unsigned long>(d.observedSilenceMs),
                      static_cast<unsigned long>(rate_.params().silenceMs));
        negotiator_.reset();   // a fallback overrides any handshake in flight
        radio_.applyRung(d.target);
        rate_.applied(d.target, nowMs);
        ++counters_.rungChanges;
        return;
    }

    if (negotiator_.busy() || !tx_.empty() || hasPendingControl_) return;

    const RungNegotiator::Step step = negotiator_.propose(d.target, nowMs);
    if (step.action == RungNegotiator::Action::SendPropose) {
        Serial.printf("[rate] proposing %s -> %s (%s)\n", rungName(radio_.rung()),
                      rungName(step.rung), d.reason);
        sendRate(RateOpcode::Propose, step.rung, rate_.lastLocalSnrDb());
    }
}

void LoraLink::serviceTx(uint32_t nowMs) {
    if (radio_.txBusy()) return;

    // Control frames first: they are ~10 bytes and carry the feedback the rate
    // controller runs on, so delaying them behind a long message is backwards.
    if (hasPendingControl_) {
        uint8_t frame[MAX_FRAME];
        const size_t n = serialize(pendingControl_, frame, sizeof(frame));
        hasPendingControl_ = false;
        if (n > 0) radio_.startSend(frame, n, nowMs);
        return;
    }

    const Packet* frag = tx_.peek();
    if (frag == nullptr) return;

    uint8_t frame[MAX_FRAME];
    const size_t n = serialize(*frag, frame, sizeof(frame));
    if (n == 0) {
        Serial.println("[link] serialize failed - dropping fragment");
        tx_.advance();
        return;
    }

    const Sx1262Radio::Result r = radio_.startSend(frame, n, nowMs);

    LogEvent ev;
    ev.timestampMs  = nowMs;
    ev.direction    = LogDirection::Tx;
    ev.rung         = radio_.rung();
    ev.result       = (r == Sx1262Radio::Result::Ok) ? LogResult::Ok : LogResult::Refused;
    ev.hasRadioInfo = false;   // no RSSI/SNR on our own transmissions
    ev.peerSnrDb    = rate_.lastPeerSnrDb();
    ev.payloadBytes = frag->payloadLen;
    ev.airtimeMs    = static_cast<float>(radio_.airtimeMs(n));
    ev.fragIndex    = frag->fragIndex;
    ev.fragCount    = frag->fragCount;
    ev.msgId        = frag->msgId;
    ev.compressed   = frag->isCompressed();
    if (r != Sx1262Radio::Result::Ok) ev.note = Sx1262Radio::resultName(r);
    emitLog(ev);

    if (r != Sx1262Radio::Result::Ok) {
        if (logMode_ == LogMode::Off) {
            Serial.printf("[link] radio refused fragment: %s\n",
                          Sx1262Radio::resultName(r));
        }
        tx_.advance();   // do not wedge the queue on a refused fragment
        return;
    }

    const bool lastFragment = (frag->fragIndex + 1 == frag->fragCount);
    ++counters_.fragmentsSent;
    tx_.advance();
    if (lastFragment) ++counters_.messagesSent;
}

// ---------------------------------------------------------------------------
// RECEIVE PATH
// ---------------------------------------------------------------------------
void LoraLink::serviceRx(uint32_t nowMs) {
    uint8_t frame[MAX_FRAME];
    size_t  len = 0;
    Sx1262Radio::RxInfo info;

    while (radio_.takeFrame(frame, sizeof(frame), len, info)) {
        // --- 1. DESERIALIZE. Checks the CRC before interpreting any field. ---
        Packet pkt;
        const DecodeResult dr = deserialize(frame, len, pkt);
        if (dr != DecodeResult::Ok) {
            ++counters_.framesDropped;
            lastDrop_ = DropReason::BadFrame;
            // A frame that failed CRC is evidence the link is struggling. One
            // is enough to retreat - silence is worse than slowness.
            rate_.onBadFrame(nowMs);
            LogEvent ev;
            ev.timestampMs  = nowMs;
            ev.direction    = LogDirection::Rx;
            ev.rung         = radio_.rung();
            ev.result       = (dr == DecodeResult::CrcMismatch) ? LogResult::CrcFail
                                                                : LogResult::Dropped;
            ev.rssiDbm      = info.rssiDbm;
            ev.snrDb        = info.snrDb;
            ev.peerSnrDb    = rate_.lastPeerSnrDb();
            ev.payloadBytes = static_cast<uint16_t>(len);
            ev.airtimeMs    = static_cast<float>(radio_.airtimeMs(len));
            ev.note         = decodeResultName(dr);
            emitLog(ev);
            if (logMode_ == LogMode::Off) {
                Serial.printf("[link] dropped frame: %s (%zu B, RSSI %.0f dBm)\n",
                              decodeResultName(dr), len, info.rssiDbm);
            }
            continue;
        }

        // A frame that decoded cleanly is positive evidence, whatever type.
        rate_.onGoodFrame(info.snrDb, nowMs);

        LogEvent ev;
        ev.timestampMs  = nowMs;
        ev.direction    = LogDirection::Rx;
        ev.rung         = radio_.rung();
        ev.result       = LogResult::Ok;
        ev.rssiDbm      = info.rssiDbm;
        ev.snrDb        = info.snrDb;
        ev.peerSnrDb    = rate_.lastPeerSnrDb();
        ev.payloadBytes = pkt.payloadLen;
        ev.airtimeMs    = static_cast<float>(radio_.airtimeMs(len));
        ev.fragIndex    = pkt.fragIndex;
        ev.fragCount    = pkt.fragCount;
        ev.msgId        = pkt.msgId;
        ev.compressed   = pkt.isCompressed();
        emitLog(ev);

        if (pkt.type == PacketType::Beacon) {
            handleRateFrame(pkt, nowMs);
            continue;
        }
        if (pkt.type == PacketType::Ack || pkt.type == PacketType::Nack) {
            handleAckFrame(pkt, nowMs);
            continue;
        }

        // --- 2. REASSEMBLE. Emits only when every fragment has arrived. ------
        const Reassembler::Result rr = rx_.offer(pkt, nowMs);
        if (rr.status == Reassembler::Status::Duplicate) {
            ++counters_.duplicatesIgnored;
            continue;
        }
        if (rr.status == Reassembler::Status::Rejected) {
            ++counters_.framesDropped;
            lastDrop_ = DropReason::Rejected;
            continue;
        }
        if (rr.status != Reassembler::Status::Complete) {
            continue;  // still waiting - never hand partial text upward
        }

        // --- 3. READ THE FLAG WITHOUT DECOMPRESSING --------------------------
        // rr.flags came out of header byte 1, which deserialize() only trusted
        // after the CRC passed. Deciding whether to decompress costs nothing.
        const bool compressed = rr.compressed();

        if (compressed) {
            // --- 4. DECOMPRESS the fully reassembled message -----------------
            size_t plainLen = 0;
            if (!decompressMessage(rr.data, rr.len, msgBuf_, sizeof(msgBuf_),
                                   plainLen)) {
                ++counters_.framesDropped;
                lastDrop_ = DropReason::DecompressFailed;
                Serial.println("[link] COMPRESSED message but no codec - dropped");
                continue;
            }
            msgLen_ = plainLen;
        } else {
            msgLen_ = rr.len < sizeof(msgBuf_) ? rr.len : sizeof(msgBuf_);
            memcpy(msgBuf_, rr.data, msgLen_);
        }

        pending_.text          = msgBuf_;
        pending_.len           = msgLen_;
        pending_.langId        = rr.langId;
        pending_.appType       = rr.appType;
        pending_.alert         = rr.alert();
        pending_.wasCompressed = compressed;
        pending_.radio         = info;
        msgReady_              = true;
        ++counters_.messagesReceived;

        // ACK the completed message, carrying the SNR we observed. This is the
        // ONLY vehicle for rate feedback, and deliberately so: it exists
        // exactly when packets are getting through.
        sendAck(rr.msgId, info.snrDb);
    }
}

void LoraLink::loop(uint32_t nowMs) {
    radio_.loop(nowMs);
    logRadioErrors(nowMs);
    serviceRx(nowMs);
    // A promotion makes the link MORE fragile. Never do that with an urgent
    // message still waiting to go out.
    rate_.setUrgentPending(tx_.alertPending());
    serviceRate(nowMs);
    serviceTx(nowMs);
    rx_.evictExpired(nowMs);
}

bool LoraLink::takeMessage(Incoming& out) {
    if (!msgReady_) return false;
    out       = pending_;
    msgReady_ = false;
    return true;
}

}  // namespace lorax
