// SIH26173 - LoRa Communication Extender: firmware entry point.
//
// Full path is live: BLE <-> adapter <-> protocol <-> radio.
//
//   phone --BLE--> [envelope] --> adapter --> compress/fragment/CRC --> LoRa
//   LoRa --> CRC/reassemble/decompress --> adapter --> [envelope] --BLE--> phone
//
// Adaptive SF/BW runs underneath all of it. Nothing above LoraLink knows.
//
// >>> ANTENNA MUST BE CONNECTED BEFORE POWER-ON. See docs/bringup-checklist.md

#include <Arduino.h>

#include <cstdlib>
#include <cstring>

#include "ble_service.h"
#include "codec_hook.h"
#include "link.h"
#include "packet_log.h"
#include "radio_config.h"
#include "rate_control.h"

using namespace lorax;

// ~22 KB (Reassembler + fragment queue + buffers). Static, never on a stack.
static LoraLink   g_link;
static BleService g_ble;

static uint32_t g_lastReportMs = 0;
constexpr uint32_t REPORT_INTERVAL_MS = 10000;

// ---------------------------------------------------------------------------
// Runtime rate-control override.
//
// Deliberately a SERIAL command, not a build flag: controlled A/B range testing
// means pinning a rung and disabling adaptation on a board already sealed in a
// box and standing in a field. Recompiling to change the config under test
// defeats the purpose.
//
//   rate status               what rung, adaptive or pinned, current SNRs
//   rate auto                 re-enable adaptation
//   rate off                  freeze at the current rung
//   rate fast|medium|far|max  pin a rung AND disable adaptation
// ---------------------------------------------------------------------------
static void printRateStatus() {
    const RateController& rc = g_link.rate();
    const RungConfig& c = rungConfig(rc.current());
    Serial.printf("[rate] rung=%s SF%u/%luHz  mode=%s  localSNR=%.1f  peerSNR=%.1f  streak=%u/%u\n",
                  rungName(rc.current()), static_cast<unsigned>(c.sf),
                  static_cast<unsigned long>(c.bandwidthHz),
                  rc.adaptive() ? "ADAPTIVE" : "PINNED",
                  static_cast<double>(rc.lastLocalSnrDb()),
                  static_cast<double>(rc.lastPeerSnrDb()),
                  static_cast<unsigned>(rc.consecutiveGood()),
                  static_cast<unsigned>(rc.params().stepUpConsecutive));
    Serial.printf("[rate]   promote at >= %.1f dB, retreat below %.1f dB\n",
                  static_cast<double>(stepUpThresholdDb(rc.current(),
                                                        rc.params().fadeMarginDb)),
                  static_cast<double>(stepDownThresholdDb(rc.current(),
                                                          rc.params().stepDownMarginDb)));
    Serial.printf("[rate]   silence: %lu ms observed / %lu ms threshold "
                  "(deep %lu ms)\n",
                  static_cast<unsigned long>(rc.silentForMs(millis())),
                  static_cast<unsigned long>(rc.params().silenceMs),
                  static_cast<unsigned long>(rc.params().deepRendezvousMs));
}

// ---------------------------------------------------------------------------
// Range-test logging.
//
//   log csv                CSV with a header line - pastes straight into a sheet
//   log on                 readable form, for watching while walking
//   log off                silent
//   log mark <text>        annotate the stream, e.g. "log mark 500m LOS"
//
// The marker is what correlates distance to packets afterwards. Marks print
// even with logging off, so one is never silently lost.
// ---------------------------------------------------------------------------
static void handleLogCommand(const char* arg, uint32_t nowMs) {
    if (strncmp(arg, "mark", 4) == 0) {
        const char* text = arg + 4;
        while (*text == ' ') ++text;
        g_link.logMark(text, nowMs);
        return;
    }
    if (strcmp(arg, "csv") == 0) {
        g_link.setLogMode(LogMode::Csv);
        Serial.println("[log] CSV mode - header emitted above, paste from there");
        return;
    }
    if (strcmp(arg, "on") == 0) {
        g_link.setLogMode(LogMode::Human);
        Serial.println("[log] readable mode");
        return;
    }
    if (strcmp(arg, "off") == 0) {
        g_link.setLogMode(LogMode::Off);
        Serial.println("[log] off");
        return;
    }
    if (*arg == '\0' || strcmp(arg, "status") == 0) {
        const LogMode m = g_link.logMode();
        Serial.printf("[log] mode=%s\n", m == LogMode::Csv    ? "csv"
                                          : m == LogMode::Human ? "on"
                                                                : "off");
        return;
    }
    Serial.println("[cmd] log csv|on|off|status|mark <text>");
}

static void handleCommand(const char* line, uint32_t nowMs) {
    if (strncmp(line, "log", 3) == 0) {
        const char* arg = line + 3;
        while (*arg == ' ') ++arg;
        handleLogCommand(arg, nowMs);
        return;
    }
    if (strncmp(line, "rate", 4) != 0) {
        Serial.println("[cmd] rate status|auto|off|fast|medium|far|max|silence <ms>");
        Serial.println("[cmd] log  csv|on|off|status|mark <text>");
        return;
    }
    const char* arg = line + 4;
    while (*arg == ' ') ++arg;

    if (*arg == '\0' || strcmp(arg, "status") == 0) {
        printRateStatus();
        return;
    }
    if (strcmp(arg, "auto") == 0) {
        g_link.rate().setAdaptive(true);
        Serial.println("[rate] adaptation ENABLED");
        printRateStatus();
        return;
    }
    // rate silence <ms> - tune the watchdog empirically during range testing
    // rather than living with a number guessed before any real link data.
    if (strncmp(arg, "silence", 7) == 0) {
        const char* value = arg + 7;
        while (*value == ' ') ++value;
        if (*value == '\0') {
            Serial.printf("[rate] silenceMs = %lu (observed %lu ms)\n",
                          static_cast<unsigned long>(g_link.rate().params().silenceMs),
                          static_cast<unsigned long>(g_link.rate().silentForMs(nowMs)));
            return;
        }
        const long ms = strtol(value, nullptr, 10);
        // Floor: a single 254 B frame at MAX is ~9 s, so a multi-fragment
        // message can legitimately be silent for tens of seconds. Below ~2 s
        // the watchdog would fire on normal traffic.
        if (ms < 2000 || ms > 600000) {
            Serial.printf("[rate] silence must be 2000..600000 ms (got %ld)\n", ms);
            return;
        }
        g_link.rate().params().silenceMs = static_cast<uint32_t>(ms);
        if (g_link.rate().params().deepRendezvousMs <
            g_link.rate().params().silenceMs * 2) {
            g_link.rate().params().deepRendezvousMs =
                g_link.rate().params().silenceMs * 3;
            Serial.printf("[rate] deepRendezvousMs raised to %lu to stay above it\n",
                          static_cast<unsigned long>(
                              g_link.rate().params().deepRendezvousMs));
        }
        Serial.printf("[rate] silenceMs = %ld\n", ms);
        return;
    }
    if (strcmp(arg, "off") == 0) {
        g_link.rate().setAdaptive(false);
        Serial.println("[rate] adaptation DISABLED - rung frozen where it is");
        printRateStatus();
        return;
    }
    Rung r;
    if (rungFromName(arg, r)) {
        g_link.rate().forceRung(r, nowMs);
        g_link.radio().applyRung(r);
        Serial.printf("[rate] PINNED to %s, adaptation disabled\n", rungName(r));
        printRateStatus();
        return;
    }
    Serial.printf("[cmd] unknown argument: \"%s\"\n", arg);
}

static void pollSerialCommands(uint32_t nowMs) {
    static char   buf[96];
    static size_t len = 0;
    while (Serial.available() > 0) {
        const char c = static_cast<char>(Serial.read());
        if (c == '\r') continue;
        if (c == '\n') {
            buf[len] = '\0';
            if (len > 0) handleCommand(buf, nowMs);
            len = 0;
            continue;
        }
        if (len < sizeof(buf) - 1) buf[len++] = c;
    }
}

void setup() {
    Serial.begin(115200);
    const uint32_t start = millis();
    while (!Serial && (millis() - start) < 3000) {
    }

    Serial.println();
    Serial.println("SIH26173 LoRa extender");
    Serial.printf("  %.3f MHz  sync 0x%02X  %d dBm  overhead %u B/fragment\n",
                  LORA_FREQUENCY_MHZ, static_cast<unsigned>(LORA_SYNC_WORD),
                  static_cast<int>(LORA_TX_POWER_DBM),
                  static_cast<unsigned>(OVERHEAD));
    Serial.printf("  codec %s\n",
                  codecAvailable() ? "enabled" : "not linked (raw path)");

    if (!g_link.begin()) {
        Serial.println("  radio init FAILED - flash env:bringup and work the checklist");
        return;
    }
    Serial.println("  radio up, listening");
    printRateStatus();
    Serial.println("  rate status|auto|off|fast|medium|far|max|silence <ms>");
    Serial.println("  log  csv|on|off|mark <text>     <- range-test data capture");

    if (!g_ble.begin(g_link)) {
        Serial.println("  BLE init FAILED");
        return;
    }
}

void loop() {
    const uint32_t now = millis();

    // Non-blocking: services the radio, drains received frames, feeds the next
    // fragment, and runs rate control. Returns promptly so BLE is not starved.
    g_link.loop(now);
    // Drains received messages and rewraps them as envelopes for the phone.
    g_ble.loop(now);
    pollSerialCommands(now);

    if (static_cast<uint32_t>(now - g_lastReportMs) >= REPORT_INTERVAL_MS) {
        g_lastReportMs = now;
        const LoraLink::Counters& c = g_link.counters();
        const Sx1262Radio::Stats& r = g_link.radio().stats();
        const BleService::Counters& b = g_ble.counters();

        Serial.printf("[stat] rung %s (%s)  acks tx/rx %lu/%lu  rungChanges %lu\n",
                      rungName(g_link.rate().current()),
                      g_link.rate().adaptive() ? "auto" : "pinned",
                      static_cast<unsigned long>(c.acksSent),
                      static_cast<unsigned long>(c.acksReceived),
                      static_cast<unsigned long>(c.rungChanges));
        Serial.printf("[stat] ble %s  writes %lu  relayed %lu  notified %lu  badtype %lu\n",
                      g_ble.connected() ? "connected" : "advertising",
                      static_cast<unsigned long>(b.writesReceived),
                      static_cast<unsigned long>(b.envelopesRelayed),
                      static_cast<unsigned long>(b.envelopesNotified),
                      static_cast<unsigned long>(b.droppedBadType));
        Serial.printf("[stat] msg rx/tx %lu/%lu  frames rx/tx %lu/%lu  dropped %lu  "
                      "dupes %lu  rxerr %lu  refused %lu\n",
                      static_cast<unsigned long>(c.messagesReceived),
                      static_cast<unsigned long>(c.messagesSent),
                      static_cast<unsigned long>(r.framesReceived),
                      static_cast<unsigned long>(r.framesSent),
                      static_cast<unsigned long>(c.framesDropped),
                      static_cast<unsigned long>(c.duplicatesIgnored),
                      static_cast<unsigned long>(r.rxErrors),
                      static_cast<unsigned long>(r.txRefused));
    }
}
