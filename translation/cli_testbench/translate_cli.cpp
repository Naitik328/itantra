// Interactive CLI test bench for the MT module -- pure C++, no Android/JVM.
//
// Exercises the exact same ONNX graphs (encoder.int8.onnx/decoder.int8.onnx
// per direction) and the same onnxruntime-extensions custom-op tokenizer
// that OnnxMtAdapter.kt uses, through the ONNX Runtime C++ API directly.
// This is NOT the same code path as the Android app (which goes through
// ai.onnxruntime's Java bindings, not C++) -- it exercises the same native
// onnxruntime + onnxruntime_extensions libraries and the same ONNX graphs,
// which is what actually matters for "is the model/tokenizer/pivot logic
// correct" testing without needing a JVM, Android device, or emulator.
//
// Mirrors, deliberately kept in lockstep with:
//   - tools/quantize_and_verify.py's greedy_decode_onnx_embedded() for the
//     single-hop encode/decode/detokenize sequence and the Devanagari-pivot
//     transliteration (tools/indictrans_common.py's transliterate()).
//   - translation/kotlin/com/itantra/orchestrator/Orchestrator.kt for the
//     pivot-chaining logic: same-language is a no-op, one side "en" is a
//     single MT call, otherwise chain src->en->tgt (indic-to-indic never
//     goes direct -- only en-indic/indic-en checkpoints exist).
//
// Does NOT port the full IndicProcessor.kt preprocessing pipeline (Moses
// punctuation normalization, placeholder wrapping, English tokenization) --
// same deliberate scope as quantize_and_verify.py: this tests whether the
// model/tokenizer/pivot design is correct, not the full text-normalization
// pipeline, which is already verified separately in Kotlin
// (translation/kotlin_verify/).
#include <onnxruntime_cxx_api.h>
#include <nlohmann/json.hpp>

#include <algorithm>
#include <cstdint>
#include <cstdio>
#include <fstream>
#include <iostream>
#include <map>
#include <memory>
#include <sstream>
#include <string>
#include <vector>

using json = nlohmann::json;

// ---------------------------------------------------------------------
// FLORES-200 tags (spec #4.1) -- only the four languages this app ships.
// ---------------------------------------------------------------------
static const std::map<std::string, std::string> kFloresTag = {
    {"en", "eng_Latn"},
    {"hi", "hin_Deva"},
    {"te", "tel_Telu"},
    {"bn", "ben_Beng"},
};

static std::string floresTag(const std::string &iso) {
  auto it = kFloresTag.find(iso);
  if (it == kFloresTag.end()) {
    throw std::runtime_error("No FLORES tag configured for '" + iso + "'");
  }
  return it->second;
}

// ---------------------------------------------------------------------
// UTF-8 <-> codepoint helpers (needed for the transliteration below --
// Devanagari/Telugu/Bengali all fall in the 3-byte UTF-8 range).
// ---------------------------------------------------------------------
static std::vector<uint32_t> utf8Decode(const std::string &s) {
  std::vector<uint32_t> out;
  size_t i = 0;
  while (i < s.size()) {
    unsigned char c = s[i];
    uint32_t cp;
    size_t len;
    if ((c & 0x80) == 0) {
      cp = c;
      len = 1;
    } else if ((c & 0xE0) == 0xC0) {
      cp = c & 0x1F;
      len = 2;
    } else if ((c & 0xF0) == 0xE0) {
      cp = c & 0x0F;
      len = 3;
    } else if ((c & 0xF8) == 0xF0) {
      cp = c & 0x07;
      len = 4;
    } else {
      // Invalid leading byte -- pass it through as-is rather than throw;
      // a test bench shouldn't crash on odd input.
      out.push_back(c);
      i += 1;
      continue;
    }
    if (i + len > s.size()) {
      out.push_back(c);
      i += 1;
      continue;
    }
    for (size_t j = 1; j < len; j++) {
      cp = (cp << 6) | (static_cast<unsigned char>(s[i + j]) & 0x3F);
    }
    out.push_back(cp);
    i += len;
  }
  return out;
}

static void utf8Encode(uint32_t cp, std::string &out) {
  if (cp <= 0x7F) {
    out.push_back(static_cast<char>(cp));
  } else if (cp <= 0x7FF) {
    out.push_back(static_cast<char>(0xC0 | (cp >> 6)));
    out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
  } else if (cp <= 0xFFFF) {
    out.push_back(static_cast<char>(0xE0 | (cp >> 12)));
    out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
    out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
  } else {
    out.push_back(static_cast<char>(0xF0 | (cp >> 18)));
    out.push_back(static_cast<char>(0x80 | ((cp >> 12) & 0x3F)));
    out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
    out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
  }
}

// ---------------------------------------------------------------------
// Offset-based script transliteration -- same algorithm as
// UnicodeIndicTransliterator.kt / tools/indictrans_common.py's
// transliterate(), ported from indic_nlp_library (Anoop Kunchukuttan,
// MIT licensed). See either of those files' doc comments for why this
// matters: IndicTrans2 pivots every Indic language through Devanagari
// internally, so te/bn text must be transliterated to Devanagari before
// hitting the tokenizer, and the model's Devanagari output must be
// transliterated back to the real target script afterward.
// ---------------------------------------------------------------------
static const std::map<std::string, uint32_t> kScriptBase = {
    {"hi", 0x0900},
    {"bn", 0x0980},
    {"te", 0x0C00},
};
static const uint32_t kCoordStart = 0x00;
static const uint32_t kCoordEnd = 0x6F;
static const uint32_t kDanda = 0x0964;
static const uint32_t kDoubleDanda = 0x0965;

static std::string transliterate(const std::string &text, const std::string &srcLang, const std::string &tgtLang) {
  auto srcIt = kScriptBase.find(srcLang);
  auto tgtIt = kScriptBase.find(tgtLang);
  if (srcIt == kScriptBase.end() || tgtIt == kScriptBase.end()) return text;
  uint32_t srcBase = srcIt->second;
  uint32_t tgtBase = tgtIt->second;

  std::vector<uint32_t> codepoints = utf8Decode(text);
  std::string out;
  for (uint32_t cp : codepoints) {
    int64_t offset = static_cast<int64_t>(cp) - static_cast<int64_t>(srcBase);
    bool inRange = offset >= static_cast<int64_t>(kCoordStart) && offset <= static_cast<int64_t>(kCoordEnd);
    bool isDanda = (cp == kDanda || cp == kDoubleDanda);
    utf8Encode((inRange && !isDanda) ? (tgtBase + static_cast<uint32_t>(offset)) : cp, out);
  }
  return out;
}

// ---------------------------------------------------------------------
// Config loaded per direction (mirrors OnnxMtAdapter.kt's VocabIds).
// ---------------------------------------------------------------------
struct VocabIds {
  int64_t decoderStartId;
  int64_t eosId;
  std::map<std::string, int64_t> langTagIds;  // FLORES tag -> dict.SRC-space id
};

static VocabIds loadVocabIds(const std::string &path) {
  std::ifstream f(path);
  if (!f) throw std::runtime_error("Cannot open " + path);
  json j;
  f >> j;
  VocabIds v;
  v.decoderStartId = j.at("decoder_start_id").get<int64_t>();
  v.eosId = j.at("eos_id").get<int64_t>();
  for (auto &[tag, id] : j.at("lang_tag_ids").items()) {
    v.langTagIds[tag] = id.get<int64_t>();
  }
  return v;
}

static std::vector<std::string> loadTgtVocab(const std::string &path) {
  std::ifstream f(path);
  if (!f) throw std::runtime_error("Cannot open " + path);
  json j;
  f >> j;
  std::vector<std::string> vocab;
  vocab.reserve(j.size());
  for (auto &piece : j) vocab.push_back(piece.get<std::string>());
  return vocab;
}

// IndicTransTokenizer.convert_tokens_to_string, exactly -- a plain string
// join, not a SentencePiece decode. Same three operations as
// OnnxMtAdapter.kt's detokenize().
static std::string detokenize(const std::vector<std::string> &tgtVocab, const std::vector<int64_t> &ids, int64_t eosId) {
  std::string joined;
  for (int64_t id : ids) {
    if (id == eosId) continue;
    if (id >= 0 && static_cast<size_t>(id) < tgtVocab.size()) joined += tgtVocab[id];
  }
  // Replace "▁" (U+2581, 3-byte UTF-8: E2 96 81) with a space, then trim.
  std::string out;
  size_t i = 0;
  const std::string marker = "\xE2\x96\x81";
  while (i < joined.size()) {
    if (joined.compare(i, marker.size(), marker) == 0) {
      out += ' ';
      i += marker.size();
    } else {
      out += joined[i];
      i += 1;
    }
  }
  size_t start = out.find_first_not_of(' ');
  size_t end = out.find_last_not_of(' ');
  if (start == std::string::npos) return "";
  return out.substr(start, end - start + 1);
}

// ---------------------------------------------------------------------
// One direction's loaded sessions + vocab (mirrors OnnxMtAdapter.kt's
// DirectionSession). Loaded lazily and kept resident for the process
// lifetime -- this is a test bench, not a memory-constrained mobile app,
// so no idle-eviction logic here (see OnnxMtAdapter.kt for that).
// ---------------------------------------------------------------------
struct DirectionSession {
  std::unique_ptr<Ort::Session> encoder;
  std::unique_ptr<Ort::Session> decoder;
  VocabIds vocab;
  std::vector<std::string> tgtVocab;
};

class MtEngine {
 public:
  MtEngine(std::string modelRoot, std::string extensionsLibPath)
      : env_(ORT_LOGGING_LEVEL_WARNING, "itantra-mt-cli"),
        modelRoot_(std::move(modelRoot)),
        extensionsLibPath_(std::move(extensionsLibPath)) {}

  // Full pivot-chaining translate -- mirrors Orchestrator's routing:
  // same language is a no-op, one side "en" is a single MT hop, anything
  // else chains src->en->tgt (never direct indic-to-indic).
  std::string translate(const std::string &text, const std::string &srcLang, const std::string &tgtLang) {
    if (srcLang == tgtLang) return text;
    if (srcLang == "en" || tgtLang == "en") {
      return oneHop(text, srcLang, tgtLang);
    }
    std::string pivotEnglish = oneHop(text, srcLang, "en");
    return oneHop(pivotEnglish, "en", tgtLang);
  }

 private:
  Ort::Env env_;
  std::string modelRoot_;
  std::string extensionsLibPath_;
  std::map<std::string, DirectionSession> sessions_;

  DirectionSession &sessionFor(const std::string &direction) {
    auto it = sessions_.find(direction);
    if (it != sessions_.end()) return it->second;

    Ort::SessionOptions opts;
    opts.RegisterCustomOpsLibrary(extensionsLibPath_.c_str());
    opts.SetIntraOpNumThreads(4);  // spec #6.3 -- shared numThreads, not per-language

    DirectionSession sess;
    std::string dir = modelRoot_ + "/" + direction;
    sess.encoder = std::make_unique<Ort::Session>(env_, (dir + "/encoder.int8.onnx").c_str(), opts);
    sess.decoder = std::make_unique<Ort::Session>(env_, (dir + "/decoder.int8.onnx").c_str(), opts);
    sess.vocab = loadVocabIds(dir + "/vocab_ids.json");
    sess.tgtVocab = loadTgtVocab(dir + "/tgt_vocab.json");

    auto [inserted, ok] = sessions_.emplace(direction, std::move(sess));
    return inserted->second;
  }

  // One MT hop -- mirrors greedy_decode_onnx_embedded() / OnnxMtAdapter.kt's
  // translate() body exactly (minus the idle-eviction bookkeeping, which
  // doesn't apply to a short-lived CLI process).
  std::string oneHop(const std::string &text, const std::string &srcLang, const std::string &tgtLang) {
    std::string direction = (srcLang == "en") ? "en-indic" : "indic-en";
    DirectionSession &sess = sessionFor(direction);

    std::string srcTag = floresTag(srcLang);
    std::string tgtTag = floresTag(tgtLang);
    auto srcTagIt = sess.vocab.langTagIds.find(srcTag);
    auto tgtTagIt = sess.vocab.langTagIds.find(tgtTag);
    if (srcTagIt == sess.vocab.langTagIds.end() || tgtTagIt == sess.vocab.langTagIds.end()) {
      throw std::runtime_error("Direction '" + direction + "' has no lang_tag_ids entry for '" + srcTag + "' or '" + tgtTag + "'");
    }

    std::string pivotedText = transliterate(text, srcLang, "hi");

    Ort::MemoryInfo memInfo = Ort::MemoryInfo::CreateCpu(OrtArenaAllocator, OrtMemTypeDefault);
    Ort::AllocatorWithDefaultOptions allocator;

    // raw_text: string tensor, shape [1]
    int64_t textShape[] = {1};
    Ort::Value textTensor = Ort::Value::CreateTensor(allocator, textShape, 1, ONNX_TENSOR_ELEMENT_DATA_TYPE_STRING);
    const char *rawStrings[] = {pivotedText.c_str()};
    textTensor.FillStringTensor(rawStrings, 1);

    int64_t srcTagId = srcTagIt->second;
    int64_t tgtTagId = tgtTagIt->second;
    int64_t eosId = sess.vocab.eosId;
    int64_t scalarShape[] = {1};
    Ort::Value srcTagTensor = Ort::Value::CreateTensor<int64_t>(memInfo, &srcTagId, 1, scalarShape, 1);
    Ort::Value tgtTagTensor = Ort::Value::CreateTensor<int64_t>(memInfo, &tgtTagId, 1, scalarShape, 1);
    Ort::Value eosTensor = Ort::Value::CreateTensor<int64_t>(memInfo, &eosId, 1, scalarShape, 1);

    const char *encInputNames[] = {"raw_text", "src_tag_id", "tgt_tag_id", "eos_id_const"};
    Ort::Value encInputs[] = {std::move(textTensor), std::move(srcTagTensor), std::move(tgtTagTensor), std::move(eosTensor)};
    const char *encOutputNames[] = {"encoder_hidden_states"};

    auto encOutputs = sess.encoder->Run(Ort::RunOptions{nullptr}, encInputNames, encInputs, 4, encOutputNames, 1);
    Ort::Value &hiddenStates = encOutputs[0];
    auto hiddenShape = hiddenStates.GetTensorTypeAndShapeInfo().GetShape();  // [1, srcLen, hidden]
    int64_t srcLen = hiddenShape[1];

    // encoder_attention_mask: all-ones [1, srcLen] -- same as OnnxMtAdapter.kt,
    // built locally rather than read back from the encoder (that output is
    // consumed internally by the tokenizer-merge and isn't exposed -- see
    // OnnxMtAdapter.kt's runEncoder() doc comment).
    std::vector<int64_t> maskData(static_cast<size_t>(srcLen), 1);
    int64_t maskShape[] = {1, srcLen};
    Ort::Value maskTensor = Ort::Value::CreateTensor<int64_t>(memInfo, maskData.data(), maskData.size(), maskShape, 2);

    // decoder_input_ids seeded with ONLY decoder_start_id -- see
    // indictrans_common.py's greedy_decode_onnx for the full explanation of
    // why not [bos_id, tgt_tag_id].
    std::vector<int64_t> decoderIds = {sess.vocab.decoderStartId};
    const int kMaxNewTokens = 128;  // spec #7.4 -- chat messages are short

    const char *decInputNames[] = {"decoder_input_ids", "encoder_hidden_states", "encoder_attention_mask"};
    const char *decOutputNames[] = {"logits"};

    for (int step = 0; step < kMaxNewTokens; step++) {
      int64_t curLen = static_cast<int64_t>(decoderIds.size());
      int64_t idsShape[] = {1, curLen};
      Ort::Value idsTensor = Ort::Value::CreateTensor<int64_t>(memInfo, decoderIds.data(), decoderIds.size(), idsShape, 2);

      Ort::Value decInputs[] = {std::move(idsTensor), std::move(hiddenStates), std::move(maskTensor)};
      auto decOutputs = sess.decoder->Run(Ort::RunOptions{nullptr}, decInputNames, decInputs, 3, decOutputNames, 1);

      // Ort::Run moves our Values into itself for the call but returns them
      // via move semantics on Value -- reclaim hiddenStates/maskTensor so
      // they can be reused next iteration (they're passed in decInputs by
      // reference to Run, which doesn't take ownership; std::move above is
      // just to satisfy the array-of-Value construction, not a real
      // ownership transfer for a raw C API call underneath).
      hiddenStates = std::move(decInputs[1]);
      maskTensor = std::move(decInputs[2]);

      auto logitsShape = decOutputs[0].GetTensorTypeAndShapeInfo().GetShape();  // [1, curLen, vocab]
      int64_t vocabSize = logitsShape[2];
      const float *logits = decOutputs[0].GetTensorData<float>();
      const float *lastStep = logits + (curLen - 1) * vocabSize;
      int64_t bestId = 0;
      float bestScore = lastStep[0];
      for (int64_t v = 1; v < vocabSize; v++) {
        if (lastStep[v] > bestScore) {
          bestScore = lastStep[v];
          bestId = v;
        }
      }
      decoderIds.push_back(bestId);
      if (bestId == eosId) break;
    }

    std::string decoded = detokenize(sess.tgtVocab, decoderIds, eosId);
    return transliterate(decoded, "hi", tgtLang);
  }
};

// ---------------------------------------------------------------------
// CLI
// ---------------------------------------------------------------------
static void printLanguages() {
  std::cout << "Languages: en (English), hi (Hindi), te (Telugu), bn (Bengali)\n";
  std::cout << "Note: bn has no real STT in the app (spec #3.4) -- bn as a\n";
  std::cout << "source works here (the indic-en checkpoint supports it) but\n";
  std::cout << "is never called that way in production.\n";
}

int main(int argc, char **argv) {
  std::string modelRoot;
  std::string extensionsLibPath;
  for (int i = 1; i < argc; i++) {
    std::string arg = argv[i];
    if (arg == "--model-root" && i + 1 < argc) {
      modelRoot = argv[++i];
    } else if (arg == "--extensions-lib" && i + 1 < argc) {
      extensionsLibPath = argv[++i];
    } else if (arg == "--help" || arg == "-h") {
      std::cout << "Usage: translate_cli --model-root DIR --extensions-lib PATH\n"
                   "  --model-root DIR      Directory containing en-indic/ and indic-en/\n"
                   "                        (each with encoder.int8.onnx, decoder.int8.onnx,\n"
                   "                        vocab_ids.json, tgt_vocab.json)\n"
                   "  --extensions-lib PATH Path to onnxruntime_extensions' native library\n"
                   "                        (get_library_path() in Python, or the .so found\n"
                   "                        under a pip install of onnxruntime_extensions)\n";
      return 0;
    }
  }
  if (modelRoot.empty() || extensionsLibPath.empty()) {
    std::cerr << "Missing --model-root or --extensions-lib. Run with --help.\n";
    return 1;
  }

  MtEngine engine(modelRoot, extensionsLibPath);

  std::cout << "iTantra MT CLI test bench\n";
  std::cout << "model-root: " << modelRoot << "\n";
  printLanguages();
  std::cout << "Type 'quit' at any prompt to exit.\n\n";

  while (true) {
    std::string srcLang, tgtLang, text;

    std::cout << "source language [en/hi/te/bn]: ";
    if (!std::getline(std::cin, srcLang) || srcLang == "quit") break;
    std::cout << "target language [en/hi/te/bn]: ";
    if (!std::getline(std::cin, tgtLang) || tgtLang == "quit") break;

    if (!kFloresTag.count(srcLang) || !kFloresTag.count(tgtLang)) {
      std::cout << "Unknown language code. ";
      printLanguages();
      continue;
    }

    std::cout << "text to translate: ";
    if (!std::getline(std::cin, text) || text == "quit") break;

    try {
      std::string result = engine.translate(text, srcLang, tgtLang);
      std::cout << "-> [" << srcLang << "->" << tgtLang << "] " << result << "\n\n";
    } catch (const std::exception &e) {
      std::cout << "ERROR: " << e.what() << "\n\n";
    }
  }

  std::cout << "Bye.\n";
  return 0;
}
