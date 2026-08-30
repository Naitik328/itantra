// ###########################################################################
// #                                                                         #
// #   RADIO BRING-UP SKETCH  -  flash this FIRST, before anything else       #
// #                                                                         #
// #   >>> NEVER POWER THIS BOARD WITHOUT AN ANTENNA ON THE E22's ANT PAD <<< #
// #                                                                         #
// #   Transmitting into an open pad reflects the full 22 dBm back into the   #
// #   PA. That destroys the output stage - sometimes instantly, sometimes    #
// #   after a few transmissions, which is worse because you will blame the   #
// #   firmware. Solder the antenna (or a 50 ohm load) BEFORE first power-on. #
// #   This sketch transmits every 3 seconds, so there is no safe window.     #
// #                                                                         #
// ###########################################################################
//
// Proves the wiring and the radio configuration with no BLE, no protocol
// layer, and no compression in the way. Build and flash with:
//
//     pio run -e bringup -t upload && pio device monitor
//
// Expected: a banner, SPI liveness, then a counter going out every 3 s. With a
// second board flashed identically, each prints the other's counter plus RSSI
// and SNR. Step-by-step diagnosis: docs/bringup-checklist.md

#include <Arduino.h>

#include "radio.h"
#include "radio_config.h"

using namespace lorax;

namespace {

Sx1262Radio  g_radio;
uint32_t     g_counter     = 0;
uint32_t     g_lastTxMs    = 0;
constexpr uint32_t TX_INTERVAL_MS = 3000;

void printBanner() {
    Serial.println();
    Serial.println("=====================================================");
    Serial.println(" SIH26173 LoRa extender - RADIO BRING-UP");
    Serial.println("=====================================================");
    Serial.println(" !! ANTENNA MUST BE CONNECTED - this sketch transmits");
    Serial.println("    every 3 s. No antenna = destroyed PA.");
    Serial.println("=====================================================");
    Serial.printf("  frequency   %.3f MHz   (India ISM 865-867)\n", LORA_FREQUENCY_MHZ);
    Serial.printf("  bandwidth   %.1f kHz\n", LORA_BANDWIDTH_KHZ);
    Serial.printf("  SF          %u\n", static_cast<unsigned>(LORA_SPREADING_FACTOR));
    Serial.printf("  coding rate 4/%u\n", static_cast<unsigned>(LORA_CODING_RATE));
    Serial.printf("  sync word   0x%02X   (not 0x12/0x34)\n",
                  static_cast<unsigned>(LORA_SYNC_WORD));
    Serial.printf("  TX power    %d dBm  (~%.1f dBm EIRP with 3.2 dBi)\n",
                  static_cast<int>(LORA_TX_POWER_DBM), LORA_TX_POWER_DBM + 3.2f);
    Serial.printf("  TCXO        %.1f V\n", LORA_TCXO_VOLTAGE);
    Serial.printf("  pins        SCK=%d MISO=%d MOSI=%d NSS=%d\n",
                  PIN_LORA_SCK, PIN_LORA_MISO, PIN_LORA_MOSI, PIN_LORA_NSS);
    Serial.printf("              RST=%d BUSY=%d DIO1=%d RXEN=%d TXEN=%d\n",
                  PIN_LORA_RESET, PIN_LORA_BUSY, PIN_LORA_DIO1,
                  PIN_LORA_RXEN, PIN_LORA_TXEN);
    Serial.printf("  airtime     %.0f ms for a %u B frame\n",
                  g_radio.airtimeMs(64), 64u);
    Serial.println("-----------------------------------------------------");
}

// The SX1262 has no chip-version register (unlike the SX127x RegVersion), so
// there is nothing to read back and compare. What we CAN prove is that SPI
// reads return live data: randomByte() samples RSSI noise, so successive calls
// must differ. All-zero or all-0xFF means MISO is dead, not that the RF is bad.
void probeSpi() {
    Serial.print("  SPI liveness (random bytes): ");
    uint8_t first = 0;
    bool varied = false;
    for (int i = 0; i < 8; ++i) {
        const uint8_t b = g_radio.raw().randomByte();
        Serial.printf("%02X ", b);
        if (i == 0) first = b;
        else if (b != first) varied = true;
    }
    Serial.println();
    if (varied) {
        Serial.println("  -> SPI read path OK (values vary)");
    } else {
        Serial.println("  -> SUSPECT: values identical. Check MISO, NSS, and 3V3.");
    }
}

}  // namespace

void setup() {
    Serial.begin(115200);
    const uint32_t start = millis();
    while (!Serial && (millis() - start) < 3000) {
    }

    printBanner();

    Serial.print("  radio.begin() ... ");
    const Sx1262Radio::Result r = g_radio.begin();
    if (r != Sx1262Radio::Result::Ok) {
        Serial.printf("FAILED (%s)\n", Sx1262Radio::resultName(r));
        Serial.println();
        Serial.println("  STOP. Do not continue to the protocol layer.");
        Serial.println("  Work through docs/bringup-checklist.md step 3.");
        Serial.println("  Most likely, in order: TCXO voltage, BUSY wiring,");
        Serial.println("  NSS wiring, 3V3 supply sag.");
        return;
    }
    Serial.println("OK");
    probeSpi();
    Serial.println("-----------------------------------------------------");
    Serial.println("  listening; transmitting a counter every 3 s");
    Serial.println();
}

void loop() {
    const uint32_t now = millis();
    g_radio.loop(now);

    uint8_t frame[MAX_FRAME];
    size_t  len = 0;
    Sx1262Radio::RxInfo info;
    if (g_radio.takeFrame(frame, sizeof(frame), len, info)) {
        Serial.printf("[RX] %2zu B  RSSI %6.1f dBm  SNR %5.1f dB  ferr %+6ld Hz  | ",
                      len, info.rssiDbm, info.snrDb,
                      static_cast<long>(info.freqErrorHz));
        for (size_t i = 0; i < len; ++i) {
            const char c = static_cast<char>(frame[i]);
            Serial.print((c >= 32 && c < 127) ? c : '.');
        }
        Serial.println();
    }

    if (!g_radio.txBusy() && static_cast<uint32_t>(now - g_lastTxMs) >= TX_INTERVAL_MS) {
        g_lastTxMs = now;
        char msg[48];
        const int n = snprintf(msg, sizeof(msg), "BRINGUP seq=%lu",
                               static_cast<unsigned long>(g_counter++));
        const Sx1262Radio::Result tr =
            g_radio.startSend(reinterpret_cast<const uint8_t*>(msg),
                              static_cast<size_t>(n), now);
        if (tr == Sx1262Radio::Result::Ok) {
            Serial.printf("[TX] %s  (%.0f ms on air)\n", msg,
                          g_radio.airtimeMs(static_cast<size_t>(n)));
        } else {
            Serial.printf("[TX] refused: %s\n", Sx1262Radio::resultName(tr));
        }
    }
}
