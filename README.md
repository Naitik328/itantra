# iTantra Relay — Android app

Week-1 skeleton for **SIH26173** (ISRO): offline multilingual speech relay over
low-bitrate links. Send **text** on the wire, rebuild **voice** at both ends.

This module is the **Kotlin / Jetpack Compose** app (owner: Naitik). It already
contains the pieces that don't depend on the AI models, so the team can build in
parallel behind the frozen wire frame.

## What's here

| Area | File | Status |
|------|------|--------|
| Wire frame (the team contract) | `protocol/WireFrame.kt` | ✅ done |
| Frame encode/decode + CRC check | `protocol/WireCodec.kt` | ✅ done |
| CRC-16/CCITT-FALSE | `protocol/Crc16.kt` | ✅ done — mirror this on the ESP32 |
| Mic capture, 16 kHz mono PCM | `audio/AudioCapture.kt` | ✅ done |
| Transport interface | `transport/Transport.kt` | ✅ done |
| Loopback transport (for testing) | `transport/LoopbackTransport.kt` | ✅ done |
| **Wi-Fi Direct** (Backend B, phone↔phone) | `transport/WifiDirectTransport.kt` | ✅ done |
| Bluetooth RFCOMM (kept for future ESP32/LoRa) | `transport/BluetoothRfcommTransport.kt` | 🅿️ unwired |
| Design-matched UI (Profile / Walkie / Members) | `ui/**`, `MainActivity.kt` | ✅ done |
| **STT / TTS / VAD** (sherpa-onnx) | — | ⬜ next, with AI-Member-3 |
| **Wi-Fi Direct** (Backend B) | — | later |
| **USB serial → LoRa** (Backend C) | — | later, with Jai |

## The wire frame

```
[0] ver(4b) | type(4b)
[1] src   [2] dst (0xFF=broadcast)   [3] lang   [4] seq   [5] len
[6 .. 6+len-1] payload (UTF-8 text)
[.. +2] crc16   (CRC-16/CCITT-FALSE over all preceding bytes)
```
`type` = NORMAL(0) / ALERT(1) / ACK(2). ~100 bytes per sentence.

## UI (matches the `design/` mockups)

Screens under `ui/screens/`, navigated by a back-stack in `MainActivity`
(**Onboarding → Home → Profile / Members → Walkie**):

- **Onboarding** — first-launch setup: name, avatar (preset colour or a chosen
  photo), gender, and permissions (mic, Bluetooth, notifications). Saved in
  SharedPreferences (`ProfileStore`); shown only until completed.
- **Home** — searchable squad list with last-active + connection status, unread
  badges, pin/favourite, **Create a Squad** + **Join** buttons, and a **Search
  nearby** bottom sheet that scans and lists discovered squads.
- **Profile** — pastel gradient, mascot, mono wordmark, Settings / Members pills.
- **Members** — 4-column avatar grid + "See all".
- **Walkie** — active-speaker card + **Hold to Speak** (press-and-hold drives the
  real mic via `AudioCapture`; the samples will feed STT next).

Design system in `ui/theme/`: `Color.kt` (palette + gradient), `Type.kt` (bundled
**Inter** variable font + **Space Mono** wordmark, in `res/font/`), `Theme.kt`.
Reusable parts in `ui/components/`. Avatars are tinted-initial placeholders standing
in for the mockup's stock photos.

Flow: Profile → Members → tap a member → Walkie. System back navigates up.

## Build & run

Open the folder in **Android Studio** and press **Run** with a phone connected.

From the command line:
```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Toolchain (locked for this project)

- Android Gradle Plugin **9.3.0**, Gradle **9.5.0**
- Kotlin **2.2.20** (Compose compiler via `org.jetbrains.kotlin.plugin.compose`)
- AGP 9.0+ has **built-in Kotlin** — do *not* add the `kotlin.android` plugin
- compileSdk 35, minSdk 26, targetSdk 35, **arm64-v8a only**
- JDK 25 (Android Studio's bundled JBR) — works with AGP 9.3

## What the demo proves

1. A message → `WireCodec.encode` → bytes (see the hex + byte count) → loopback →
   `WireCodec.decode` → CRC verified → text back. The whole frame path works.
2. The mic captures 16 kHz PCM (the level meter moves). This is the exact audio
   the STT model will consume next.

## Testing Wi-Fi Direct (Backend B) — needs two Android phones

1. Install the app on **both** phones, finish onboarding, grant the Wi-Fi/nearby
   permission. Turn **Wi-Fi on** (no need to join a network).
2. Both phones now **advertise the iTantra service continuously** — no prompt, no
   time limit (unlike Bluetooth's ~5-minute discoverable cap).
3. On phone **A**, open **Search nearby** → phone **B** appears **by name** (only
   iTantra phones show). Tap **Connect**.
4. **Accept the one-time "invite to connect" system dialog on phone B.** Status
   goes to **Connected** on both and they open the talk screen.

Discovery uses Wi-Fi P2P DNS-SD service discovery (so only app peers appear); the
link is a local TCP socket over the P2P group. The frame codec is unchanged.

## Next steps (in order)

1. Get the stock **sherpa-onnx** streaming-ASR sample running on a real phone
   (prove the runtime) — then pull it in as a dependency here.
2. Wire STT → `WireCodec.encode` → transport, and transport → `WireCodec.decode`
   → TTS. The transport seam is done — STT/TTS just plug into the ends.
3. Add **de-dup by seq** and **ACK** frames on receive (the frame already carries
   both fields).
