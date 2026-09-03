#!/usr/bin/env python3
"""Export an IndicTrans2-Distilled checkpoint to ONNX (encoder + decoder).

Run this on Colab/Kaggle/local with huggingface.co access -- not in a
sandboxed environment. See docs/CLAUDE.md #4 (build order, task 11) and
docs/ITANTRA_INTEGRATION_SPEC.md #7.4, #8.1.

Design decision -- no KV cache.
IndicTrans2's HF checkpoint is a custom (trust_remote_code) encoder-decoder,
not a stock MarianMT/MBart class, so a control-flow-correct "merged decoder
with optional past_key_values" ONNX graph (the pattern optimum uses for
stock architectures) isn't something you can trust torch.onnx.export to
trace correctly here without per-op verification against real weights,
which this environment cannot do. Given the spec's own framing --
"chat messages are short, beam width 1-2, cap max_length" (#7.4) -- the
decoder instead recomputes the full prefix every step. That's O(n^2) over a
sequence of maybe 30-60 tokens, which is a rounding error next to the
KV-cache engineering risk. If on-device benchmarking (spec #3.2) shows this
is actually too slow, revisit with a KV-cache export -- don't guess now.

Tokenization is embedded in the ONNX graph itself (onnxruntime-extensions'
SentencepieceTokenizer/SentencepieceDecoder custom ops), not implemented in
Kotlin -- Android has no first-party SentencePiece binding, and a hand-rolled
one risks silently mismatching what the model was trained on. See
tools/tokenizer_graph.py's module doc for the exact node schema (verified
against onnxruntime-extensions' own test suite) and, importantly, for what
is and is NOT verified about IndicTrans2's specific add_bos/add_eos/fairseq
settings -- the real tokenization_indictrans.py that would answer that
definitively sits behind a gated HF download this environment couldn't
reach. quantize_and_verify.py's id-level check is not optional as a result.

Three output files per direction (extends spec #8.4 -- see
translation/README.md for why two directions each need their own pair):
    encoder.onnx       -- raw text in, encoder_hidden_states out (tokenizer embedded)
    decoder.onnx        -- token ids in, logits out (no tokenizer -- operates on ids)
    detokenizer.onnx    -- token ids in, text out
    vocab_ids.json       -- decoder_start_id, eos_id, pad_id, per-language tag ids
                            (the only vocabulary facts OnnxMtAdapter.kt needs)
(quantize_and_verify.py turns encoder/decoder.onnx into their .int8 counterparts)
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

from indictrans_common import CHECKPOINTS, LANG_TAGS, LoadedModel, load_model_and_tokenizer, model_size_mb
from tokenizer_graph import (
    build_detokenizer_model,
    build_tokenizer_bridge_graph,
    dump_vocab_ids,
    fetch_spm_model_bytes,
    merge_tokenizer_into_encoder,
)


class _DecoderNoCache:
    """Wraps the HF model so torch.onnx.export sees a plain
    (decoder_input_ids, encoder_hidden_states, encoder_attention_mask) -> logits
    function, with use_cache forced off. Mirrors greedy_decode_onnx() in
    indictrans_common.py -- keep the two in lockstep.
    """

    def __init__(self, hf_model):
        import torch.nn as nn

        class _Module(nn.Module):
            def __init__(self, hf_model):
                super().__init__()
                self.hf_model = hf_model

            def forward(self, decoder_input_ids, encoder_hidden_states, encoder_attention_mask):
                from transformers.modeling_outputs import BaseModelOutput

                out = self.hf_model(
                    decoder_input_ids=decoder_input_ids,
                    encoder_outputs=BaseModelOutput(last_hidden_state=encoder_hidden_states),
                    attention_mask=encoder_attention_mask,
                    use_cache=False,
                )
                return out.logits

        self.module = _Module(hf_model)


def export_encoder(loaded: LoadedModel, out_path: Path, opset: int) -> None:
    import torch

    encoder = loaded.model.get_encoder()
    encoder.eval()

    dummy_input_ids = torch.randint(low=4, high=1000, size=(1, 7), dtype=torch.long)
    dummy_attention_mask = torch.ones_like(dummy_input_ids)

    torch.onnx.export(
        encoder,
        (dummy_input_ids, dummy_attention_mask),
        str(out_path),
        input_names=["input_ids", "attention_mask"],
        output_names=["encoder_hidden_states"],
        dynamic_axes={
            "input_ids": {0: "batch", 1: "src_seq_len"},
            "attention_mask": {0: "batch", 1: "src_seq_len"},
            "encoder_hidden_states": {0: "batch", 1: "src_seq_len"},
        },
        opset_version=opset,
        do_constant_folding=True,
    )


def export_decoder(loaded: LoadedModel, out_path: Path, opset: int) -> None:
    import torch

    hidden_size = loaded.model.config.d_model if hasattr(loaded.model.config, "d_model") else loaded.model.config.hidden_size
    wrapper = _DecoderNoCache(loaded.model).module
    wrapper.eval()

    dummy_decoder_ids = torch.randint(low=4, high=1000, size=(1, 3), dtype=torch.long)
    dummy_encoder_hidden = torch.randn(1, 7, hidden_size, dtype=torch.float32)
    dummy_encoder_mask = torch.ones(1, 7, dtype=torch.long)

    torch.onnx.export(
        wrapper,
        (dummy_decoder_ids, dummy_encoder_hidden, dummy_encoder_mask),
        str(out_path),
        input_names=["decoder_input_ids", "encoder_hidden_states", "encoder_attention_mask"],
        output_names=["logits"],
        dynamic_axes={
            "decoder_input_ids": {0: "batch", 1: "tgt_seq_len"},
            "encoder_hidden_states": {0: "batch", 1: "src_seq_len"},
            "encoder_attention_mask": {0: "batch", 1: "src_seq_len"},
            "logits": {0: "batch", 1: "tgt_seq_len"},
        },
        opset_version=opset,
        do_constant_folding=True,
    )


def embed_tokenizer(
    loaded: LoadedModel,
    model_name_or_path: str,
    plain_encoder_path: Path,
    encoder_path: Path,
    detokenizer_path: Path,
    add_bos: bool,
    add_eos: bool,
    fairseq_vocab_shift: bool,
    opset: int,
) -> None:
    import onnx

    print("Fetching model.SRC / model.TGT (raw sentencepiece files) ...", file=sys.stderr)
    src_spm = fetch_spm_model_bytes(model_name_or_path, "model.SRC")
    tgt_spm = fetch_spm_model_bytes(model_name_or_path, "model.TGT")

    print("Building tokenizer bridge graph and merging with the encoder ...", file=sys.stderr)
    bridge = build_tokenizer_bridge_graph(src_spm, add_bos, add_eos, fairseq_vocab_shift, opset)
    # Saved standalone (not part of the shipped bundle) purely so
    # quantize_and_verify.py can run it in isolation and compare its
    # input_ids output against the real HF tokenizer's -- the merged
    # encoder.onnx only exposes encoder_hidden_states, not input_ids.
    onnx.save(bridge, str(encoder_path.parent / "tokenizer_bridge_debug.onnx"))
    plain_encoder = onnx.load(str(plain_encoder_path))
    merged = merge_tokenizer_into_encoder(bridge, plain_encoder)
    onnx.save(merged, str(encoder_path))
    plain_encoder_path.unlink()

    print("Building detokenizer graph ...", file=sys.stderr)
    detok = build_detokenizer_model(tgt_spm, fairseq_vocab_shift, opset)
    onnx.save(detok, str(detokenizer_path))


def introspect(loaded: LoadedModel, direction: str) -> None:
    cfg = loaded.model.config
    n_params = sum(p.numel() for p in loaded.model.parameters())
    print(f"direction:        {direction}")
    print(f"model class:      {type(loaded.model).__name__}")
    print(f"tokenizer class:  {type(loaded.tokenizer).__name__}")
    print(f"vocab size:       {getattr(cfg, 'vocab_size', 'unknown')}")
    print(f"d_model/hidden:   {getattr(cfg, 'd_model', getattr(cfg, 'hidden_size', 'unknown'))}")
    print(f"params:           {n_params / 1e6:.1f}M")
    print()
    print("Encoder I/O   : input_ids, attention_mask -> encoder_hidden_states")
    print("Decoder I/O   : decoder_input_ids, encoder_hidden_states, "
          "encoder_attention_mask -> logits  (no KV cache, see module docstring)")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument(
        "--direction", choices=sorted(CHECKPOINTS), required=True,
        help="en-indic or indic-en. Both are needed for hi/te; only en-indic is needed for bn (spec #4.1).",
    )
    parser.add_argument(
        "--model-name-or-path", default=None,
        help="Override the default AI4Bharat checkpoint id (e.g. a local export dir).",
    )
    parser.add_argument(
        "--tokenizer-type", choices=["auto", "indictrans-toolkit"], default="auto",
        help="Fall back to indictrans-toolkit if AutoTokenizer(trust_remote_code=True) doesn't self-load.",
    )
    parser.add_argument("--out", type=Path, required=True, help="Output directory for encoder.onnx / decoder.onnx.")
    parser.add_argument("--opset", type=int, default=17)
    parser.add_argument(
        "--introspect-only", action="store_true",
        help="Load the checkpoint and print shapes/config; skip the actual export.",
    )
    parser.add_argument(
        "--embed-tokenizer", action=argparse.BooleanOptionalAction, default=True,
        help="Merge SentencePiece tokenization into encoder.onnx and emit detokenizer.onnx "
             "+ vocab_ids.json (default: on). Needs huggingface_hub + onnxruntime-extensions "
             "installed. Use --no-embed-tokenizer to fall back to the old plain graphs.",
    )
    parser.add_argument(
        "--add-bos", action=argparse.BooleanOptionalAction, default=False,
        help="UNVERIFIED (see module docstring) -- whether the source-side SentencepieceTokenizer "
             "should prepend BOS. Confirm with quantize_and_verify.py before trusting the default.",
    )
    parser.add_argument(
        "--add-eos", action=argparse.BooleanOptionalAction, default=True,
        help="UNVERIFIED (see module docstring) -- whether the source-side SentencepieceTokenizer "
             "should append EOS. Confirm with quantize_and_verify.py before trusting the default.",
    )
    parser.add_argument(
        "--fairseq-vocab-shift", action=argparse.BooleanOptionalAction, default=False,
        help="UNVERIFIED -- whether IndicTrans2's HF vocab is offset from the raw sentencepiece "
             "vocab the way fairseq-derived tokenizers (e.g. XLM-R, NLLB) typically are. "
             "See sentencepiece_tokenizer.cc's 'fairseq' remap. Confirm before trusting the default.",
    )
    args = parser.parse_args()

    model_name_or_path = args.model_name_or_path or CHECKPOINTS[args.direction]

    print(f"Loading {model_name_or_path} ...", file=sys.stderr)
    loaded = load_model_and_tokenizer(model_name_or_path, args.tokenizer_type)

    if args.introspect_only:
        introspect(loaded, args.direction)
        return

    args.out.mkdir(parents=True, exist_ok=True)
    encoder_path = args.out / "encoder.onnx"
    decoder_path = args.out / "decoder.onnx"

    plain_encoder_path = args.out / "encoder_plain.onnx" if args.embed_tokenizer else encoder_path
    print(f"Exporting encoder -> {plain_encoder_path}", file=sys.stderr)
    export_encoder(loaded, plain_encoder_path, args.opset)

    print(f"Exporting decoder -> {decoder_path}", file=sys.stderr)
    export_decoder(loaded, decoder_path, args.opset)

    if args.embed_tokenizer:
        detokenizer_path = args.out / "detokenizer.onnx"
        embed_tokenizer(
            loaded, model_name_or_path, plain_encoder_path, encoder_path, detokenizer_path,
            args.add_bos, args.add_eos, args.fairseq_vocab_shift, args.opset,
        )
        vocab_ids_path = args.out / "vocab_ids.json"
        dump_vocab_ids(vocab_ids_path, loaded.model, loaded.tokenizer, args.direction, LANG_TAGS)
        print(f"detokenizer.onnx: {model_size_mb(detokenizer_path):.1f} MB")
        print(f"Wrote {vocab_ids_path}")

    print(f"encoder.onnx: {model_size_mb(encoder_path):.1f} MB")
    print(f"decoder.onnx: {model_size_mb(decoder_path):.1f} MB")
    print("Next: tools/quantize_and_verify.py to produce the int8 files that ship "
          "-- and, if --embed-tokenizer was on, to check the add_bos/add_eos/"
          "fairseq_vocab_shift defaults against the real tokenizer before trusting them.")


if __name__ == "__main__":
    main()
