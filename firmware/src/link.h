// Protocol glue: joins the radio to the existing protocol layer.
//
// Reimplements nothing. Compression, fragmentation, serialisation, CRC and
// reassembly all come from lib/protocol and src/codec_hook; this class only
// sequences them, and the sequence is the point.
//
// ===========================================================================
// SEND   text -> compress -> fragment -> serialize(+CRC) -> airtime -> radio
// RECV   radio -> deserialize(CRC first) -> reassemble -> decompress -> text
// ===========================================================================
//
// MEMORY: contains a Reassembler (~16 KB) and a 16-fragment transmit queue
// (~4 KB). This object is ~22 KB. Declare it static or global, NEVER on a stack.

#pragma once

#include <cstdint>

#include "fragmenter.h"
#include "packet.h"
#include "packet_log.h"
#include "radio.h"
#include "rate_control.h"
#include "reassembler.h"
#include "tx_queue.h"

namespace lorax {

class LoraLink {
public:
    enum class SendResult : uint8_t {
        Ok = 0,
        Queued,            // accepted behind an in-flight message
        PreemptedQueued,   // accepted; an ALERT displaced a waiting NORMAL
        QueueFull,         // NOT accepted - the caller must surface this
        TooLong,           // needs more than MAX_FRAGMENTS
        SerializeFailed,
        RadioRefused,      // airtime guard or radio error
    };

    static bool accepted(SendResult r) {
        return r == SendResult::Ok || r == SendResult::Queued ||
               r == SendResult::PreemptedQueued;
    }

    enum class DropReason : uint8_t {
        None = 0,
        BadFrame,          // deserialize() rejected it (CRC, version, network)
        Rejected,          // reassembler refused it
        DecompressFailed,
    };

    struct Incoming {
        const uint8_t*      text   = nullptr;  // valid until the next takeMessage()
        size_t              len    = 0;
        uint8_t             langId = 0;
        uint8_t             appType = 0;   // app envelope type, carried opaquely
        bool                alert  = false;
        bool                wasCompressed = false;
        Sx1262Radio::RxInfo radio;
    };

    struct Counters {
        uint32_t messagesSent      = 0;
        uint32_t messagesReceived  = 0;
        uint32_t fragmentsSent     = 0;
        uint32_t framesDropped     = 0;
        uint32_t duplicatesIgnored = 0;
        uint32_t acksSent          = 0;
        uint32_t acksReceived      = 0;
        uint32_t rungChanges       = 0;
        uint32_t messagesQueued    = 0;
        uint32_t alertsPreempted   = 0;
        uint32_t sendsRefused      = 0;   // queue genuinely full
    };

    bool begin();
    void loop(uint32_t nowMs);

    // Hands down PLAIN, UNCOMPRESSED UTF-8 - exactly what the app sends over
    // BLE. Everything below this call is the radio hop's business.
    SendResult sendText(const uint8_t* text, size_t len, uint8_t langId,
                        bool alert, uint8_t appType, uint32_t nowMs);

    bool takeMessage(Incoming& out);
    bool sendBusy() const { return !tx_.empty(); }
    bool alertPending() const { return tx_.alertPending(); }

    const Counters&   counters()   const { return counters_; }

    // Adaptive rate control. The rest of the firmware never touches this - the
    // BLE adapter and the protocol layer do not know adaptation exists.
    RateController&       rate()       { return rate_; }
    const RateController& rate() const { return rate_; }

    // Range-test logging. Emitting a CSV header on enable is what makes the
    // stream paste straight into a sheet.
    void    setLogMode(LogMode m);
    LogMode logMode() const { return logMode_; }
    // Annotates the stream from the serial console while walking. Without
    // these there is no way to correlate distance to packets afterwards.
    void    logMark(const char* text, uint32_t nowMs);
    DropReason        lastDrop()   const { return lastDrop_; }
    Sx1262Radio&      radio()            { return radio_; }
    const Reassembler& reassembler() const { return rx_; }

    static const char* sendResultName(SendResult r);

private:
    void serviceTx(uint32_t nowMs);
    void serviceRx(uint32_t nowMs);
    void serviceRate(uint32_t nowMs);
    void emitLog(const LogEvent& e);
    void logRadioErrors(uint32_t nowMs);
    void handleRateFrame(const Packet& p, uint32_t nowMs);
    void handleAckFrame(const Packet& p, uint32_t nowMs);
    bool queueControl(const Packet& p);
    void sendAck(uint8_t msgId, float observedSnr);
    void sendRate(RateOpcode op, Rung rung, float observedSnr);

    Sx1262Radio    radio_;
    Reassembler    rx_;
    RateController rate_;

    // Single-slot control queue. Control frames are tiny (10 bytes) and rare,
    // and they go out between messages rather than interleaved into one.
    Packet   pendingControl_;
    bool     hasPendingControl_ = false;

    // Handshake state machine. Pure logic, lives in rate_control.h so it can be
    // tested without a radio - see the comment on RungNegotiator for why the
    // two roles must not share a flag.
    RungNegotiator negotiator_;

    TxQueue  tx_;
    // Fragmentation workspace. A member, not a local: 16 Packets is 4 KB and
    // would overflow the task stack.
    Packet   fragScratch_[MAX_FRAGMENTS];
    uint8_t  nextMsgId_ = 0;

    // Compression scratch. Sized for the worst case: a codec that expands.
    uint8_t  codecBuf_[Reassembler::MAX_MESSAGE_BYTES] = {};
    uint8_t  msgBuf_[Reassembler::MAX_MESSAGE_BYTES]   = {};
    size_t   msgLen_   = 0;
    bool     msgReady_ = false;
    Incoming pending_;

    Counters   counters_;
    DropReason lastDrop_ = DropReason::None;

    LogMode  logMode_       = LogMode::Off;
    uint32_t loggedRxErrors_ = 0;
};

}  // namespace lorax
