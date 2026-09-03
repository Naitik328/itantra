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

Tokenization is embedded in the encoder ONNX graph itself
(onnxruntime-extensions' SentencepieceTokenizer custom op, plus a Gather-based
vocabulary remap -- see tools/tokenizer_graph.py's module doc), not
implemented in Kotlin. Android has no first-party SentencePiece binding, and
IndicTransTokenizer's real behavior -- confirmed against the actual
tokenization_indictrans.py, config.json, and a live model.generate() run,
not guessed -- turned out to need more than a bare tokenizer call: its
vocabulary is a separate fairseq-style dictionary, not the raw sentencepiece
ids, and FLORES tags are inserted as literal ids rather than tokenized text.
See tokenizer_graph.py for exactly what that means and why.

Four output files per direction (extends spec #8.4 -- see
translation/README.md for why two directions each need their own set):
    encoder.onnx     -- raw text + 2 tag ids in, encoder_hidden_states out (tokenizer embedded)
    decoder.onnx     -- token ids in, logits out (no tokenizer -- operates on ids)
    vocab_ids.json   -- decoder_start_id, eos_id, per-language tag ids (dict.SRC-space)
    tgt_vocab.json   -- id-indexed piece-string array for Kotlin-side detokenization
                        (a plain string join -- see tokenizer_graph.py point 5; no
                        detokenizer.onnx needed)
(quantize_and_verify.py turns encoder/decoder.onnx into their .int8 counterparts)
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from indictrans_common import CHECKPOINTS, LANG_TAGS, LoadedModel, load_model_and_tokenizer, model_size_mb
from tokenizer_graph import (
    build_remap_table,
    build_tokenizer_bridge_graph,
    dump_tgt_vocab,
    dump_vocab_ids,
    fetch_repo_file,
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


def _consolidate_external_data(out_path: Path) -> None:
    """torch's newer (dynamo-based) ONNX exporter splits weights into a
    companion `<name>.onnx.data` file automatically once a graph is past
    some internal size threshold -- confirmed empirically: it did this for
    both the ~280MB encoder and ~550MB decoder here, well under any 2GB
    protobuf limit that would actually require it. Re-save as one
    self-contained file so downstream code (merge_tokenizer_into_encoder,
    quantize_dynamic, SHA256SUMS.txt, the bundle layout in spec #8.4) only
    ever has to deal with a single path per model, not a pair that must be
    kept together.
    """
    import onnx

    data_path = out_path.with_name(out_path.name + ".data")
    if not data_path.exists():
        return
    model = onnx.load(str(out_path), load_external_data=True)
    onnx.save(model, str(out_path), save_as_external_data=False)
    data_path.unlink()


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
    _consolidate_external_data(out_path)


def export_decoder(loaded: LoadedModel, out_path: Path, opset: int) -> None:
    import torch

    # This is the ENCODER's hidden size (what the decoder cross-attends to),
    # not the decoder's own -- they happen to match for this checkpoint
    # (both 512) but that's not guaranteed in general. IndicTransConfig
    # names it encoder_embed_dim, not the d_model/hidden_size most HF
    # configs use -- confirmed via AutoConfig.from_pretrained(...), not guessed.
    cfg = loaded.model.config
    hidden_size = getattr(cfg, "encoder_embed_dim", None) or getattr(cfg, "d_model", None) or cfg.hidden_size
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
    _consolidate_external_data(out_path)


def embed_tokenizer(
    model_name_or_path: str,
    plain_encoder_path: Path,
    encoder_path: Path,
    out_dir: Path,
    direction: str,
    lang_tags: dict[str, str],
    opset: int,
) -> None:
    import onnx

    print("Fetching dict.SRC.json / dict.TGT.json / model.SRC / model.TGT ...", file=sys.stderr)
    src_dict = json.loads(fetch_repo_file(model_name_or_path, "dict.SRC.json").read_text(encoding="utf-8"))
    tgt_dict = json.loads(fetch_repo_file(model_name_or_path, "dict.TGT.json").read_text(encoding="utf-8"))
    src_spm_path = fetch_repo_file(model_name_or_path, "model.SRC")

    print("Building the source-vocab remap table (spm-native id -> dict.SRC id) ...", file=sys.stderr)
    remap_table = build_remap_table(src_spm_path, src_dict)

    print("Building tokenizer bridge graph and merging with the encoder ...", file=sys.stderr)
    bridge = build_tokenizer_bridge_graph(src_spm_path.read_bytes(), remap_table, opset)
    # Saved standalone (not part of the shipped bundle) purely so
    # quantize_and_verify.py can run it in isolation and compare its
    # input_ids output against the real HF tokenizer's -- the merged
    # encoder.onnx only exposes encoder_hidden_states, not input_ids.
    onnx.save(bridge, str(out_dir / "tokenizer_bridge_debug.onnx"))
    plain_encoder = onnx.load(str(plain_encoder_path))
    merged = merge_tokenizer_into_encoder(bridge, plain_encoder)
    onnx.save(merged, str(encoder_path))
    plain_encoder_path.unlink()

    # Every language this project ships gets a dict.SRC-space tag id here,
    # regardless of direction: dict.SRC.json's language-tag entries are
    # shared across the whole IndicTrans2 family, not just the languages
    # this particular checkpoint's source/target actually vary over --
    # confirmed empirically (the en-indic checkpoint's own dict.SRC.json
    # contains "hin_Deva" even though Hindi is never that direction's
    # *source* language). Trying to guess a "relevant subset" per direction
    # was actual, tested-and-wrong code in an earlier version of this
    # function (KeyError: 'eng_Latn' on the en-indic direction, which needs
    # its own fixed source tag's id just as much as its target tags' ids).
    tag_ids = {}
    for iso, tag in lang_tags.items():
        if tag not in src_dict:
            print(f"NOTE: '{tag}' ({iso}) not in dict.SRC.json for {direction} -- "
                  f"skipping; OnnxMtAdapter can't use {iso} with this direction.", file=sys.stderr)
            continue
        tag_ids[tag] = src_dict[tag]

    dump_tgt_vocab(out_dir / "tgt_vocab.json", tgt_dict)
    return src_dict["</s>"], tag_ids


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
        help="Merge SentencePiece tokenization into encoder.onnx and emit tgt_vocab.json "
             "+ vocab_ids.json (default: on). Needs huggingface_hub + onnxruntime-extensions "
             "installed. Use --no-embed-tokenizer to fall back to the old plain ids-in graph.",
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
        eos_id, tag_ids = embed_tokenizer(
            model_name_or_path, plain_encoder_path, encoder_path, args.out, args.direction, LANG_TAGS, args.opset,
        )
        decoder_start_id = loaded.model.config.decoder_start_token_id
        vocab_ids_path = args.out / "vocab_ids.json"
        dump_vocab_ids(vocab_ids_path, decoder_start_id, eos_id, tag_ids)
        print(f"Wrote {vocab_ids_path}")
        print(f"Wrote {args.out / 'tgt_vocab.json'}")

    print(f"encoder.onnx: {model_size_mb(encoder_path):.1f} MB")
    print(f"decoder.onnx: {model_size_mb(decoder_path):.1f} MB")
    print("Next: tools/quantize_and_verify.py to produce the int8 files that ship "
          "and run the id-level tokenizer check against the real tokenizer.")


if __name__ == "__main__":
    main()
