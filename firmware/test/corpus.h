// Shared test corpus: the real emergency sentences from the TTS pipeline.
//
// These are the exact strings the team measured, verified byte-for-byte against
// those measurements (162/164/81/145/172/239). They are also the training and
// benchmark corpus for the compression work in tools/ - which is why the real
// character distributions matter and synthetic stand-ins were replaced.
//
// All six are already in NFC form: Unicode normalisation does not change their
// byte length.
//
// Tamil is the worst case at 239 B - 2.95x the English equivalent.

#pragma once

#include <cstddef>

namespace corpus {

// Hindi - 162 B, 62 codepoints, 27 distinct
inline constexpr char HI[] = "सावधान। बाढ़ का पानी बढ़ रहा है। तुरंत ऊँचाई वाली जगह पर जाएँ।";
// Bengali - 164 B, 60 codepoints, 27 distinct
inline constexpr char BN[] = "সতর্ক থাকুন। বন্যার পানি বাড়ছে। অবিলম্বে উঁচু জায়গায় যান।";
// Gujarati - 145 B, 57 codepoints, 26 distinct
inline constexpr char GU[] = "સાવધાન. પૂરનું પાણી વધી રહ્યું છે. તરત જ ઊંચી જગ્યાએ જાઓ.";
// Kannada - 172 B, 64 codepoints, 25 distinct
inline constexpr char KN[] = "ಎಚ್ಚರಿಕೆ. ಪ್ರವಾಹದ ನೀರು ಹೆಚ್ಚುತ್ತಿದೆ. ತಕ್ಷಣ ಎತ್ತರದ ಸ್ಥಳಕ್ಕೆ ಹೋಗಿ.";
// Tamil - 239 B, 87 codepoints, 24 distinct  (worst case)
inline constexpr char TA[] = "எச்சரிக்கை. வெள்ளத்தின் அளவு அதிகரித்து வருகிறது. உடனடியாக உயரமான இடத்திற்கு செல்லவும்.";
// English - 81 B, 81 codepoints, 22 distinct
inline constexpr char EN[] = "Warning. Flood water levels are rising. Please move to higher ground immediately.";

inline constexpr size_t HI_LEN = sizeof(HI) - 1;
inline constexpr size_t BN_LEN = sizeof(BN) - 1;
inline constexpr size_t GU_LEN = sizeof(GU) - 1;
inline constexpr size_t KN_LEN = sizeof(KN) - 1;
inline constexpr size_t TA_LEN = sizeof(TA) - 1;
inline constexpr size_t EN_LEN = sizeof(EN) - 1;

// Measured lengths from the team's tested sentences. The assertions in
// test_packet check the strings above still hit these exactly.
inline constexpr size_t MEASURED_HI = 162;
inline constexpr size_t MEASURED_BN = 164;
inline constexpr size_t MEASURED_GU = 145;
inline constexpr size_t MEASURED_KN = 172;
inline constexpr size_t MEASURED_TA = 239;
inline constexpr size_t MEASURED_EN = 81;

}  // namespace corpus
