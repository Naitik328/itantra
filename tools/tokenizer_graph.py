"""Builds in-graph SentencePiece tokenizer/detokenizer ONNX nodes using
onnxruntime-extensions' custom ops (domain "ai.onnx.contrib"), and merges the
tokenizer into the plain encoder graph so the shipped model takes raw text
in and needs no tokenizer library on the Android side at all.

Why in-graph rather than a JNI/library binding: Android has no first-party
SentencePiece binding (this is the gap MtTokenizer.kt originally flagged).
onnxruntime-extensions ships a prebuilt Android AAR
(com.microsoft.onnxruntime:onnxruntime-extensions-android) with these ops
compiled in, so loading it as a custom-op library at OrtSession creation
time is the whole Android-side dependency -- see OnnxMtAdapter.kt.

Node schema below is taken directly from onnxruntime-extensions'
test/test_sentencepiece_ops.py (Microsoft, MIT licensed), not guessed:
  SentencepieceTokenizer:
    attr  model: base64-encoded raw sentencepiece .model bytes
    in    inputs(string[1]), nbest_size(int64), alpha(float),
          add_bos(bool), add_eos(bool), reverse(bool)
    out   ids(int32, ragged/flat), instance_indices(int64, row splits)
  SentencepieceDecoder:
    attr  model: base64-encoded raw sentencepiece .model bytes
    in    ids(int64, shape [n] or [1,n])
    out   text(string[1])

WHAT IS VERIFIED vs. WHAT IS NOT (read before trusting the defaults):
  - The node schema above: verified against upstream's own test suite.
  - model.SRC / model.TGT are raw sentencepiece proto files shipped in the
    IndicTrans2 HF repos: verified via the HF API's file listing (public
    even though file *downloads* are gated behind a license click-through).
  - add_bos / add_eos / whether IndicTrans2 needs the fairseq vocab-id shift
    (see sentencepiece_tokenizer.cc's `fairseq` remap -- common for
    fairseq-trained multilingual MT models): NOT verified. The actual
    tokenization_indictrans.py that would answer this is behind HF's gated
    download and could not be fetched in this environment. Defaults below
    are reasonable guesses, not confirmed facts -- see
    verify_tokenizer_ids() in quantize_and_verify.py, which is not optional:
    it fails loudly if these defaults produce ids that don't match the real
    HF tokenizer's output for the same string, and that must pass before
    trusting this graph in the app.
"""
from __future__ import annotations

import base64
import json
from pathlib import Path

from onnx import TensorProto, checker, helper, numpy_helper
import numpy as np


def fetch_spm_model_bytes(model_name_or_path: str, filename: str) -> bytes:
    """Download model.SRC or model.TGT directly -- these are the raw
    sentencepiece proto files, independent of the (gated, unreadable-by-us)
    custom tokenizer class that wraps them.
    """
    from huggingface_hub import hf_hub_download

    path = hf_hub_download(repo_id=model_name_or_path, filename=filename)
    return Path(path).read_bytes()


def _b64(data: bytes) -> bytes:
    return base64.b64encode(data)


def _scalar_initializer(name: str, dtype, value) -> TensorProto:
    return numpy_helper.from_array(np.array(value, dtype=dtype), name=name)


def build_tokenizer_bridge_graph(
    spm_model_bytes: bytes,
    add_bos: bool,
    add_eos: bool,
    fairseq_vocab_shift: bool,
    opset: int,
):
    """A small standalone ONNX graph: raw text in, int64 input_ids +
    attention_mask out, shaped [1, seq_len] -- ready to merge with the plain
    encoder graph from export_indictrans2_onnx.py's export_encoder().

    nbest_size/alpha/add_bos/add_eos/reverse are baked in as initializers
    (not exposed as graph inputs) so the merged model's only external input
    is the raw text string -- one tensor, not six.
    """
    nodes = []
    tok_inputs = ["raw_text", "nbest_size", "alpha", "add_bos", "add_eos", "reverse"]
    tok_outputs = ["token_ids_i32", "row_splits"]
    if fairseq_vocab_shift:
        tok_inputs.append("fairseq")

    nodes.append(
        helper.make_node(
            "SentencepieceTokenizer",
            inputs=tok_inputs,
            outputs=tok_outputs,
            model=_b64(spm_model_bytes),
            name="mt_tokenizer",
            domain="ai.onnx.contrib",
        )
    )

    # int32 -> int64, and add the leading batch=1 axis the encoder expects.
    nodes.append(helper.make_node("Cast", ["token_ids_i32"], ["token_ids_i64"], to=TensorProto.INT64))
    nodes.append(helper.make_node("Unsqueeze", ["token_ids_i64", "axis0"], ["input_ids"]))

    # attention_mask = ones_like(input_ids)
    nodes.append(helper.make_node("Shape", ["input_ids"], ["input_ids_shape"]))
    nodes.append(
        helper.make_node(
            "ConstantOfShape",
            ["input_ids_shape"],
            ["attention_mask"],
            value=numpy_helper.from_array(np.array([1], dtype=np.int64)),
        )
    )

    initializers = [
        _scalar_initializer("nbest_size", np.int64, 0),
        _scalar_initializer("alpha", np.float32, 1.0),
        _scalar_initializer("add_bos", bool, add_bos),
        _scalar_initializer("add_eos", bool, add_eos),
        _scalar_initializer("reverse", bool, False),
        numpy_helper.from_array(np.array([0], dtype=np.int64), name="axis0"),
    ]
    if fairseq_vocab_shift:
        initializers.append(_scalar_initializer("fairseq", bool, True))

    graph = helper.make_graph(
        nodes,
        "mt_tokenizer_bridge",
        inputs=[helper.make_tensor_value_info("raw_text", TensorProto.STRING, [1])],
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


def build_detokenizer_model(spm_model_bytes: bytes, fairseq_vocab_shift: bool, opset: int):
    """ids (int64, [1, n]) -> text (string, [1]). Standalone -- OnnxMtAdapter
    calls this once, after the greedy decode loop finishes producing ids.
    """
    inputs = ["ids"]
    if fairseq_vocab_shift:
        inputs.append("fairseq")

    node = helper.make_node(
        "SentencepieceDecoder",
        inputs=inputs,
        outputs=["text"],
        model=_b64(spm_model_bytes),
        name="mt_detokenizer",
        domain="ai.onnx.contrib",
    )
    graph_inputs = [helper.make_tensor_value_info("ids", TensorProto.INT64, [1, None])]
    initializers = []
    if fairseq_vocab_shift:
        initializers.append(_scalar_initializer("fairseq", bool, True))
        graph_inputs = graph_inputs[:1]  # fairseq is fed as an initializer, not an external input

    graph = helper.make_graph(
        [node],
        "mt_detokenizer",
        inputs=graph_inputs,
        outputs=[helper.make_tensor_value_info("text", TensorProto.STRING, [1])],
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

    return compose.merge_models(
        tokenizer_bridge_model,
        encoder_model,
        io_map=[("input_ids", "input_ids"), ("attention_mask", "attention_mask")],
    )


def dump_vocab_ids(out_path: Path, loaded_model, loaded_tokenizer, direction: str, lang_tags: dict[str, str]) -> None:
    """The only vocabulary knowledge OnnxMtAdapter.kt needs once tokenization
    is in-graph: bos/eos/decoder-start ids, and the target-side FLORES tag id
    for each language this direction can decode into (used to seed
    decoder_input_ids = [decoder_start_id, tgt_tag_id]).

    decoder_start_token_id comes from model.config when set (the
    authoritative source for encoder-decoder generation start, per HF's own
    generation contract) and falls back to bos_token_id otherwise -- do not
    hardcode a guess here if config disagrees.
    """
    config = loaded_model.config
    decoder_start_id = getattr(config, "decoder_start_token_id", None)
    if decoder_start_id is None:
        decoder_start_id = loaded_tokenizer.bos_token_id

    targets = [iso for iso in lang_tags if not (direction == "en-indic" and iso == "en")]
    if direction == "indic-en":
        targets = ["en"]

    data = {
        "direction": direction,
        "decoder_start_id": decoder_start_id,
        "eos_id": loaded_tokenizer.eos_token_id,
        "pad_id": loaded_tokenizer.pad_token_id,
        "lang_tag_ids": {
            lang_tags[iso]: loaded_tokenizer.convert_tokens_to_ids(lang_tags[iso]) for iso in targets
        },
    }
    out_path.write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
