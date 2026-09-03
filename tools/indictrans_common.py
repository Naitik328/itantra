"""Shared helpers for the IndicTrans2 export/quantize/verify pipeline.

Not used at Android runtime -- that's OnnxMtAdapter.kt (Phase 4). This module
only serves the two build-time tools in this directory.
"""
from __future__ import annotations

import sys
from dataclasses import dataclass
from pathlib import Path

# IndicTrans2-Distilled checkpoint IDs (200M, int8-friendly). One model per
# direction -- both are multilingual across all Indic languages they cover,
# so hi/te/bn share a single en-indic model and hi/te share a single
# indic-en model. See docs/ITANTRA_INTEGRATION_SPEC.md #4.1, #7.4.
CHECKPOINTS = {
    "en-indic": "ai4bharat/indictrans2-en-indic-dist-200M",
    "indic-en": "ai4bharat/indictrans2-indic-en-dist-200M",
}

# FLORES-200 style language tags IndicTrans2 expects as the first source
# token and the forced first decoder token. Only the languages this project
# actually ships (docs/CLAUDE.md #1) are listed -- extend when a language is
# added to languages.json, not speculatively.
LANG_TAGS = {
    "en": "eng_Latn",
    "hi": "hin_Deva",
    "te": "tel_Telu",
    "bn": "ben_Beng",
}


def flores_tag(lang: str) -> str:
    try:
        return LANG_TAGS[lang]
    except KeyError:
        raise SystemExit(
            f"No FLORES tag configured for '{lang}'. Add it to LANG_TAGS in "
            f"indictrans_common.py -- do not guess one."
        )


# Unicode block starts for the Brahmi-derived scripts this project ships --
# same constants as translation/kotlin/com/itantra/mt/IndicScripts.kt, ported
# from indic_nlp_library's langinfo.py (Anoop Kunchukuttan, MIT licensed).
SCRIPT_RANGES = {
    "hi": 0x0900,
    "bn": 0x0980,
    "te": 0x0C00,
}
COORDINATED_RANGE_START = 0x00
COORDINATED_RANGE_END = 0x6F
DANDA = 0x0964
DOUBLE_DANDA = 0x0965


def transliterate(text: str, src_lang: str, tgt_lang: str) -> str:
    """Offset-based script transliteration -- same algorithm as
    UnicodeIndicTransliterator.kt, ported from indic_nlp_library.

    Why this matters here, not just in the Kotlin app: IndicTrans2's own
    preprocessing (processor.pyx) transliterates every Indic-script sentence
    to a Devanagari pivot before tokenization, and transliterates the
    model's Devanagari output back to the target script afterward. The
    tokenizer vocab is 61.5% Devanagari and only ~0.1% Telugu/Bengali
    (measured, see translation/translation_state.md's "Package size"
    section) -- skipping this step doesn't just look different, it feeds
    the model text its vocabulary can barely represent. Any verify script
    that calls the tokenizer on raw native-script Telugu/Bengali text
    without this step first is testing something the real pipeline never
    does, and any surprising result would be an artifact of that gap, not
    a fact about the model.
    """
    if src_lang not in SCRIPT_RANGES or tgt_lang not in SCRIPT_RANGES:
        return text
    src_base = SCRIPT_RANGES[src_lang]
    tgt_base = SCRIPT_RANGES[tgt_lang]
    out = []
    for ch in text:
        offset = ord(ch) - src_base
        in_range = COORDINATED_RANGE_START <= offset <= COORDINATED_RANGE_END
        is_danda = ord(ch) in (DANDA, DOUBLE_DANDA)
        out.append(chr(tgt_base + offset) if in_range and not is_danda else ch)
    return "".join(out)


@dataclass
class LoadedModel:
    model: "object"
    tokenizer: "object"


def load_model_and_tokenizer(model_name_or_path: str, tokenizer_type: str) -> LoadedModel:
    """Load the HF checkpoint + a tokenizer that can build IndicTrans2 inputs.

    tokenizer_type:
      - "auto"               : AutoTokenizer(trust_remote_code=True) — try first.
      - "indictrans-toolkit" : IndicTransToolkit's tokenizer — the fallback
                                AI4Bharat's own model cards use when the bundled
                                remote-code tokenizer isn't self-sufficient.
    """
    import torch
    from transformers import AutoModelForSeq2SeqLM

    model = AutoModelForSeq2SeqLM.from_pretrained(
        model_name_or_path, trust_remote_code=True, torch_dtype=torch.float32
    )
    model.eval()  # disables dropout; matches the STT export lesson in spec #8.1

    if tokenizer_type == "indictrans-toolkit":
        try:
            from IndicTransToolkit import IndicTransTokenizer
        except ImportError:
            raise SystemExit(
                "IndicTransToolkit not installed. pip install -r requirements.txt"
            )
        tokenizer = IndicTransTokenizer(direction=_direction_from_checkpoint(model_name_or_path))
    else:
        from transformers import AutoTokenizer

        try:
            tokenizer = AutoTokenizer.from_pretrained(model_name_or_path, trust_remote_code=True)
        except Exception as e:  # noqa: BLE001 -- surface a clear next step, not a stack trace
            print(
                f"AutoTokenizer failed ({e}). Retry with "
                f"--tokenizer-type indictrans-toolkit after installing IndicTransToolkit.",
                file=sys.stderr,
            )
            raise

    return LoadedModel(model=model, tokenizer=tokenizer)


def _direction_from_checkpoint(model_name_or_path: str) -> str:
    if "en-indic" in model_name_or_path:
        return "en-indic"
    if "indic-en" in model_name_or_path:
        return "indic-en"
    raise SystemExit(
        "Can't infer direction from checkpoint name "
        f"'{model_name_or_path}'. Pass --direction explicitly."
    )


def greedy_decode_onnx(
    encoder_session,
    decoder_session,
    tokenizer,
    decoder_start_id: int,
    text: str,
    src_lang: str,
    tgt_lang: str,
    max_new_tokens: int = 128,
):
    """Reference greedy decode against the exported ONNX graphs.

    Deliberately KV-cache-free -- see the module docstring in
    export_indictrans2_onnx.py for why. This is the same decode loop
    OnnxMtAdapter.kt must replicate at runtime; keep them in lockstep if you
    change either one.

    decoder_start_id must come from the checkpoint's own
    model.config.decoder_start_token_id -- pass it in, don't assume it
    equals eos_token_id. It does for the en-indic checkpoint (both are 2,
    confirmed by reproducing model.generate() token-for-token), but that's a
    fact about this one checkpoint, not a rule to bake in here.
    """
    import numpy as np

    src_tag = flores_tag(src_lang)
    tgt_tag = flores_tag(tgt_lang)

    # Devanagari-pivot transliteration for te/bn -- see transliterate()'s
    # doc. No-op for hi (already Devanagari) and en (not an Indic script).
    pivoted_text = transliterate(text, src_lang, "hi")

    # Both tags prepended to the SOURCE text, matching IndicProcessor's real
    # preprocessing (processor.pyx's _preprocess: f"{src_lang} {tgt_lang}
    # {processed_sent}") -- not just the source tag. This was wrong in an
    # earlier version of this function; caught while wiring up the in-graph
    # tokenizer path in tools/tokenizer_graph.py, which reuses this format.
    src_text = f"{src_tag} {tgt_tag} {pivoted_text}"
    enc = tokenizer(src_text, return_tensors="np")
    input_ids = enc["input_ids"].astype(np.int64)
    attention_mask = enc["attention_mask"].astype(np.int64)

    (encoder_hidden_states,) = encoder_session.run(
        None, {"input_ids": input_ids, "attention_mask": attention_mask}
    )

    # decoder_input_ids seeded with ONLY decoder_start_token_id -- confirmed
    # by manually reproducing model.generate()'s output token-for-token with
    # a bare use_cache=False loop. No separate target-tag token goes to the
    # decoder; the target language is already encoded in the source sequence
    # (tokenizer_graph.py's module doc, points 3-4). An earlier version of
    # this function seeded [bos_id, tgt_tag_id], which was wrong on two
    # counts: decoder_start_token_id (config.json) is not bos_token_id here
    # (2 vs 0), and the tgt_tag_id token doesn't belong on the decoder side.
    eos_id = tokenizer.eos_token_id
    decoder_input_ids = np.array([[decoder_start_id]], dtype=np.int64)

    for _ in range(max_new_tokens):
        (logits,) = decoder_session.run(
            None,
            {
                "decoder_input_ids": decoder_input_ids,
                "encoder_hidden_states": encoder_hidden_states,
                "encoder_attention_mask": attention_mask,
            },
        )
        next_id = int(logits[0, -1].argmax())
        decoder_input_ids = np.concatenate(
            [decoder_input_ids, np.array([[next_id]], dtype=np.int64)], axis=1
        )
        if eos_id is not None and next_id == eos_id:
            break

    decoded = tokenizer.decode(decoder_input_ids[0], skip_special_tokens=True)
    return transliterate(decoded, "hi", tgt_lang)  # Devanagari pivot -> target script; no-op for hi/en


def model_size_mb(path: Path) -> float:
    return path.stat().st_size / (1024 * 1024)
