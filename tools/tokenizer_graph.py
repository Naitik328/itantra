"""Builds an in-graph SentencePiece tokenizer node (onnxruntime-extensions'
`SentencepieceTokenizer` custom op, domain "ai.onnx.contrib") and merges it
into the plain encoder graph, so the shipped model takes raw text in and
Android needs no SentencePiece library of its own -- see OnnxMtAdapter.kt.

GROUND TRUTH, not guessed -- confirmed 2026-09-03 against the real
tokenization_indictrans.py, config.json, and a live model.generate() run
with real HF access (previously blocked by the gated download; see the
`translation-mt` branch history for the earlier, wrong, guessed version of
this file):

1. IndicTransTokenizer's vocabulary is NOT the raw SentencePiece vocabulary.
   dict.SRC.json / dict.TGT.json are separate fairseq-style dictionaries
   (<s>=0, <pad>=1, </s>=2, <unk>=3, then real pieces) that do not match the
   .SRC/.TGT sentencepiece models' own internal piece ids in any way --
   measured directly: of 88 sampled real pieces, only 1 had matching ids
   between the two numbering schemes, and the rest showed no constant
   offset. Piece *strings* are stable across both; only their *ids* differ.
   A remap table (spm-native id -> dict id), built once at export time from
   the real dict.SRC.json / dict.TGT.json, is unavoidable.
2. dict.SRC.json also contains ~1900 entries with no corresponding spm
   piece at all (special tokens, FLORES language tags, fairseq placeholders
   like "madeupword0000") -- these are exactly the tokens
   tokenization_indictrans.py inserts as *literal* ids rather than through
   spm.EncodeAsPieces, and confirms tags must never be run through the
   tokenizer op as text.
3. Encoder input format, confirmed via tok(...)["input_ids"] on a real
   sentence: [src_tag_id, tgt_tag_id, <spm-encoded, dict-remapped piece
   ids for the sentence text ONLY>, eos_id]. No BOS. Both tag ids come from
   dict.SRC.json (yes, even the *target* language tag -- it's a second
   token in the *source* sequence, not a decoder-side token; see point 4).
4. Decoder seeding, confirmed by manually reproducing model.generate()'s
   output token-for-token with a bare `use_cache=False` loop: just
   `[decoder_start_token_id]` (config.json's value, 2 here -- which is
   `</s>`, NOT `bos_token_id`). No separate target-tag token is fed to the
   decoder; the target language is fully determined by the tag already
   present in the source sequence (point 3).
5. Detokenization does not need SentencePiece's decoder at all.
   IndicTransTokenizer.convert_tokens_to_string is just
   `"".join(tokens).replace("▁", " ").strip()` -- a plain string join.
   So instead of a second custom-op graph, export dumps a flat
   id-indexed piece-string array (tgt_vocab.json) and OnnxMtAdapter.kt does
   this trivial join itself; no detokenizer.onnx, no second custom op,
   fewer things that can silently disagree with the real tokenizer.

Given all of the above, add_bos/add_eos/reverse and any "fairseq" vocab
shift are no longer guessed flags -- they're fixed: add_bos=False,
add_eos=False (the encoder's dict-space eos is appended explicitly to match
build_inputs_with_special_tokens exactly, not sourced from spm's own,
different, eos id), reverse=False, and no built-in "fairseq" remap (that
op attribute implements one specific fixed permutation formula; the real
remap here is an arbitrary corpus-frequency-sorted one that only a full
lookup table, not a formula, can reproduce).

Node schema (SentencepieceTokenizer inputs/outputs/attrs) is still taken
from onnxruntime-extensions' own test/test_sentencepiece_ops.py (Microsoft,
MIT licensed), unchanged from before.
"""
from __future__ import annotations

import base64
import json
from pathlib import Path

import numpy as np
from onnx import TensorProto, helper, numpy_helper


def fetch_repo_file(model_name_or_path: str, filename: str) -> Path:
    from huggingface_hub import hf_hub_download

    return Path(hf_hub_download(repo_id=model_name_or_path, filename=filename))


def _b64(data: bytes) -> bytes:
    return base64.b64encode(data)


def _scalar_initializer(name: str, dtype, value) -> TensorProto:
    return numpy_helper.from_array(np.array(value, dtype=dtype), name=name)


def build_remap_table(spm_model_path: Path, side_dict: dict[str, int]) -> np.ndarray:
    """remap[native_spm_id] = dict-space id, with <unk> fallback for the
    ~5% of spm pieces fairseq's frequency-sorted dictionary dropped (see
    module docstring, point 2). Built from the real files, not derived.
    """
    import sentencepiece as spm

    sp = spm.SentencePieceProcessor(model_file=str(spm_model_path))
    unk_id = side_dict["<unk>"]
    table = np.empty(sp.get_piece_size(), dtype=np.int64)
    for i in range(sp.get_piece_size()):
        table[i] = side_dict.get(sp.id_to_piece(i), unk_id)
    return table


def build_tokenizer_bridge_graph(spm_model_bytes: bytes, remap_table: np.ndarray, opset: int):
    """text + src_tag_id + tgt_tag_id -> int64 input_ids [1, n] + attention_mask [1, n].

    input_ids = [src_tag_id, tgt_tag_id, <remapped spm pieces for text>, eos_id]
    -- see module docstring point 3. eos_id is baked in as a constant here
    because it is fixed per direction (dict.SRC.json's "</s>"), passed in via
    `remap_table`'s caller instead of re-derived -- see embed_tokenizer().
    """
    nodes = [
        helper.make_node(
            "SentencepieceTokenizer",
            inputs=["raw_text", "nbest_size", "alpha", "add_bos", "add_eos", "reverse"],
            outputs=["piece_ids_i32", "row_splits"],
            model=_b64(spm_model_bytes),
            name="mt_tokenizer",
            domain="ai.onnx.contrib",
        ),
        helper.make_node("Cast", ["piece_ids_i32"], ["piece_ids_i64"], to=TensorProto.INT64),
        helper.make_node("Gather", ["remap_table", "piece_ids_i64"], ["dict_piece_ids"]),
        helper.make_node("Unsqueeze", ["dict_piece_ids", "axis0"], ["dict_piece_ids_2d"]),
        # src_tag_id/tgt_tag_id/eos_id_const are already rank-1 shape [1]
        # (one value each) -- one Unsqueeze takes them to [1, 1], matching
        # dict_piece_ids_2d's [1, n] for the Concat below.
        helper.make_node("Unsqueeze", ["src_tag_id", "axis0"], ["src_tag_2d"]),
        helper.make_node("Unsqueeze", ["tgt_tag_id", "axis0"], ["tgt_tag_2d"]),
        helper.make_node("Unsqueeze", ["eos_id_const", "axis0"], ["eos_2d"]),
        helper.make_node(
            "Concat",
            ["src_tag_2d", "tgt_tag_2d", "dict_piece_ids_2d", "eos_2d"],
            ["input_ids"],
            axis=1,
        ),
        helper.make_node("Shape", ["input_ids"], ["input_ids_shape"]),
        helper.make_node(
            "ConstantOfShape",
            ["input_ids_shape"],
            ["attention_mask"],
            value=numpy_helper.from_array(np.array([1], dtype=np.int64)),
        ),
    ]

    initializers = [
        _scalar_initializer("nbest_size", np.int64, 0),
        _scalar_initializer("alpha", np.float32, 1.0),
        _scalar_initializer("add_bos", bool, False),  # ground truth point 3/4 -- no BOS, ever
        _scalar_initializer("add_eos", bool, False),  # eos appended explicitly in dict-space instead
        _scalar_initializer("reverse", bool, False),
        numpy_helper.from_array(np.array([0], dtype=np.int64), name="axis0"),
        numpy_helper.from_array(remap_table, name="remap_table"),
    ]

    graph = helper.make_graph(
        nodes,
        "mt_tokenizer_bridge",
        inputs=[
            helper.make_tensor_value_info("raw_text", TensorProto.STRING, [1]),
            helper.make_tensor_value_info("src_tag_id", TensorProto.INT64, [1]),
            helper.make_tensor_value_info("tgt_tag_id", TensorProto.INT64, [1]),
            helper.make_tensor_value_info("eos_id_const", TensorProto.INT64, [1]),
        ],
        outputs=[
            helper.make_tensor_value_info("input_ids", TensorProto.INT64, [1, None]),
            helper.make_tensor_value_info("attention_mask", TensorProto.INT64, [1, None]),
        ],
        initializer=initializers,
    )
    model = helper.make_model(
        graph,
        opset_imports=[helper.make_opsetid("", opset), helper.make_opsetid("ai.onnx.contrib", 1)],
    )
    model.ir_version = 8
    return model


def merge_tokenizer_into_encoder(tokenizer_bridge_model, encoder_model):
    from onnx import compose

    # merge_models requires matching IR versions; the bridge graph is built
    # standalone (so it can be run alone for verification -- see
    # quantize_and_verify.py's verify_tokenizer_ids), so align it to
    # whatever IR version the actual torch.onnx.export output used rather
    # than hardcoding one.
    tokenizer_bridge_model.ir_version = encoder_model.ir_version
    merged = compose.merge_models(
        tokenizer_bridge_model,
        encoder_model,
        io_map=[("input_ids", "input_ids"), ("attention_mask", "attention_mask")],
    )

    # merge_models concatenates opset_import from both models without
    # deduplicating -- both graphs import domain='' at the same version, so
    # the merged model ends up with two identical entries. Confirmed this
    # breaks onnxruntime's quantizer ("Failed to find proper ai.onnx
    # domain") even though onnx.checker accepts it; not a spec violation
    # onnx itself objects to, just something downstream tooling chokes on.
    best_version_by_domain: dict[str, int] = {}
    for oi in merged.opset_import:
        best_version_by_domain[oi.domain] = max(best_version_by_domain.get(oi.domain, 0), oi.version)
    del merged.opset_import[:]
    for domain, version in best_version_by_domain.items():
        entry = merged.opset_import.add()
        entry.domain = domain
        entry.version = version

    return merged


def dump_vocab_ids(out_path: Path, decoder_start_id: int, eos_id: int, lang_tag_ids: dict[str, int]) -> None:
    """The only vocabulary knowledge OnnxMtAdapter.kt needs for the encoder
    side: decoder_start_id (fed alone as decoder_input_ids[0], point 4),
    the dict.SRC-space eos id appended to every encoder input, and the
    dict.SRC-space id for every FLORES tag this direction's source sequence
    can contain (both the fixed source-language tag and every target
    language tag this direction can decode into -- both live in dict.SRC,
    point 3).
    """
    data = {"decoder_start_id": decoder_start_id, "eos_id": eos_id, "lang_tag_ids": lang_tag_ids}
    out_path.write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def dump_tgt_vocab(out_path: Path, tgt_dict: dict[str, int]) -> None:
    """id-indexed piece-string array for Kotlin-side detokenization (module
    docstring point 5): index i holds the piece string for dict.TGT id i.
    Detokenizing is then just "".join(pieces).replace("▁", " ").strip()
    over the pieces named by the decoder's output ids -- no SentencePiece
    library needed on the Android side for this direction.
    """
    max_id = max(tgt_dict.values())
    table: list[str] = [""] * (max_id + 1)
    for piece, idx in tgt_dict.items():
        table[idx] = piece
    out_path.write_text(json.dumps(table, ensure_ascii=False), encoding="utf-8")
