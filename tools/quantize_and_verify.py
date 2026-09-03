#!/usr/bin/env python3
"""Quantize exported IndicTrans2 ONNX graphs to int8 and sanity-check them.

Run after export_indictrans2_onnx.py, in the same HF-connected environment
(verification loads the original PyTorch checkpoint to compare against).
See docs/ITANTRA_INTEGRATION_SPEC.md #8.3 (quantization policy) and #8.1
(the STT export pipeline this mirrors -- "verify" step 5 there is the same
idea: decode real inputs through the exported artifact and cross-check).

Policy: int8 dynamic quantization, standard for seq2seq (spec #8.3) --
unlike TTS, MT is not exempted from quantization.

If export_indictrans2_onnx.py ran with --embed-tokenizer (the default),
verification here does something extra and non-optional: it checks that the
in-graph SentencepieceTokenizer + vocab-remap's output ids for each test
sentence exactly match the real HF tokenizer's ids for the same string. A
mismatch here means the model is silently seeing different tokens than it
was trained on, which is worse than an error: it looks like it's working
and produces fluent-but-wrong translations. See tools/tokenizer_graph.py's
module doc for exactly what's being checked and why it was needed at all
(IndicTransTokenizer's vocabulary is not the raw sentencepiece vocabulary).
"""
from __future__ import annotations

import argparse
import hashlib
import json
import sys
from pathlib import Path

from indictrans_common import (
    CHECKPOINTS,
    flores_tag,
    greedy_decode_onnx,
    load_model_and_tokenizer,
    model_size_mb,
)


def quantize(src: Path, dst: Path) -> None:
    from onnxruntime.quantization import QuantType, quantize_dynamic

    quantize_dynamic(
        model_input=str(src),
        model_output=str(dst),
        weight_type=QuantType.QInt8,
        op_types_to_quantize=["MatMul"],  # same MatMul-only rule as STT, spec #8.3
    )


def sha256sum(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def write_sha256sums(out_dir: Path, files: list[Path]) -> None:
    lines = [f"{sha256sum(p)}  {p.name}" for p in files]
    (out_dir / "SHA256SUMS.txt").write_text("\n".join(lines) + "\n")


def load_test_pairs(path: Path) -> list[tuple[str, str]]:
    pairs = []
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        src, _, ref = line.partition("\t")
        if not ref:
            raise SystemExit(f"Malformed line in {path} (expected 'source<TAB>reference'): {line!r}")
        pairs.append((src, ref))
    return pairs


def _extensions_session_options():
    import onnxruntime as ort
    from onnxruntime_extensions import get_library_path

    opts = ort.SessionOptions()
    opts.register_custom_ops_library(get_library_path())
    return opts


def verify_tokenizer_ids(out_dir: Path, tokenizer, src_lang: str, tgt_lang: str, test_file: Path) -> None:
    """The mandatory check described in the module docstring. Raises
    SystemExit (not a warning) on the first mismatch -- this is a
    correctness gate, not a nice-to-have.
    """
    import numpy as np
    import onnxruntime as ort

    bridge_path = out_dir / "tokenizer_bridge_debug.onnx"
    vocab_ids_path = out_dir / "vocab_ids.json"
    if not bridge_path.exists():
        print(f"No {bridge_path.name} found -- skipping tokenizer id verification "
              f"(export must have run with --no-embed-tokenizer).", file=sys.stderr)
        return

    vocab_ids = json.loads(vocab_ids_path.read_text(encoding="utf-8"))
    session = ort.InferenceSession(str(bridge_path), sess_options=_extensions_session_options())
    src_tag = flores_tag(src_lang)
    tgt_tag = flores_tag(tgt_lang)
    src_tag_id = vocab_ids["lang_tag_ids"][src_tag]
    tgt_tag_id = vocab_ids["lang_tag_ids"][tgt_tag]
    eos_id = vocab_ids["eos_id"]

    pairs = load_test_pairs(test_file)
    print(f"\nVerifying in-graph tokenizer ids against the real HF tokenizer ({len(pairs)} sentences)...")
    for src, _ref in pairs:
        graph_ids, _attn = session.run(
            None,
            {
                "raw_text": np.array([src], dtype=object),
                "src_tag_id": np.array([src_tag_id], dtype=np.int64),
                "tgt_tag_id": np.array([tgt_tag_id], dtype=np.int64),
                "eos_id_const": np.array([eos_id], dtype=np.int64),
            },
        )
        graph_ids = graph_ids[0].tolist()

        # Both tags, matching IndicProcessor's real preprocessing format
        # (processor.pyx's _preprocess) -- see indictrans_common.py's note.
        full_text = f"{src_tag} {tgt_tag} {src}"
        expected_ids = tokenizer(full_text, return_tensors="np")["input_ids"][0].tolist()

        if graph_ids != expected_ids:
            raise SystemExit(
                f"TOKENIZER ID MISMATCH for {full_text!r}:\n"
                f"  in-graph : {graph_ids}\n"
                f"  reference: {expected_ids}\n"
                f"The vocab remap table or tag/eos ids in vocab_ids.json don't "
                f"match this checkpoint's real tokenizer. Do not ship "
                f"encoder.onnx with mismatched tokenization -- re-check "
                f"tools/tokenizer_graph.py's build_remap_table() against the "
                f"real dict.SRC.json for this checkpoint."
            )
    print("Tokenizer ids match the reference tokenizer for every test sentence.")


def verify(
    out_dir: Path,
    model_name_or_path: str,
    tokenizer_type: str,
    src_lang: str,
    tgt_lang: str,
    test_file: Path,
    embed_tokenizer: bool,
) -> None:
    import onnxruntime as ort

    print(f"Loading tokenizer from {model_name_or_path} for verification ...", file=sys.stderr)
    loaded = load_model_and_tokenizer(model_name_or_path, tokenizer_type)

    if embed_tokenizer:
        verify_tokenizer_ids(out_dir, loaded.tokenizer, src_lang, tgt_lang, test_file)

    decoder_session = ort.InferenceSession(str(out_dir / "decoder.int8.onnx"))

    pairs = load_test_pairs(test_file)
    if not pairs:
        raise SystemExit(f"No test sentence pairs found in {test_file}")

    decoder_start_id = loaded.model.config.decoder_start_token_id

    print(f"\n{'source':40} | {'expected':30} | got")
    print("-" * 100)
    mismatches = 0
    for src, ref in pairs:
        if embed_tokenizer:
            got = greedy_decode_onnx_embedded(out_dir, decoder_session, src, src_lang, tgt_lang)
        else:
            encoder_session = ort.InferenceSession(str(out_dir / "encoder.int8.onnx"))
            got = greedy_decode_onnx(
                encoder_session, decoder_session, loaded.tokenizer, decoder_start_id, src, src_lang, tgt_lang
            )
        flag = "" if ref.strip().lower() in got.strip().lower() else "  <-- MISMATCH (eyeball this, not a hard fail)"
        if flag:
            mismatches += 1
        print(f"{src[:40]:40} | {ref[:30]:30} | {got}{flag}")

    print(f"\n{len(pairs) - mismatches}/{len(pairs)} contained the expected reference substring.")
    print("A quantized seq2seq model paraphrasing correctly-meaning output is normal;")
    print("read the outputs, don't just count mismatches.")


def detokenize(tgt_vocab: list[str], ids: list[int], eos_id: int) -> str:
    """IndicTransTokenizer.convert_tokens_to_string, exactly -- a plain
    string join, not a SentencePiece decode (tokenizer_graph.py's module
    doc, point 5). OnnxMtAdapter.kt must do the same three operations.
    """
    pieces = [tgt_vocab[i] for i in ids if i != eos_id and 0 <= i < len(tgt_vocab)]
    return "".join(pieces).replace("▁", " ").strip()


def greedy_decode_onnx_embedded(out_dir: Path, decoder_session, text: str, src_lang: str, tgt_lang: str,
                                 max_new_tokens: int = 128) -> str:
    """Same shape as greedy_decode_onnx() in indictrans_common.py, but for
    the tokenizer-embedded pipeline: the encoder takes raw text plus tag ids
    (no pre-tokenization needed), and detokenize() (a plain string join,
    see above) replaces tokenizer.decode(). This is the reference
    OnnxMtAdapter.kt's decode loop must match -- keep them in lockstep.
    """
    import numpy as np
    import onnxruntime as ort

    vocab_ids = json.loads((out_dir / "vocab_ids.json").read_text(encoding="utf-8"))
    tgt_vocab = json.loads((out_dir / "tgt_vocab.json").read_text(encoding="utf-8"))
    src_tag = flores_tag(src_lang)
    tgt_tag = flores_tag(tgt_lang)

    opts = _extensions_session_options()
    encoder_session = ort.InferenceSession(str(out_dir / "encoder.int8.onnx"), sess_options=opts)

    src_tag_id = vocab_ids["lang_tag_ids"][src_tag]
    tgt_tag_id = vocab_ids["lang_tag_ids"][tgt_tag]
    eos_id = vocab_ids["eos_id"]
    decoder_start_id = vocab_ids["decoder_start_id"]

    # The merged encoder.onnx only exposes encoder_hidden_states --
    # attention_mask was consumed internally by io_map during merge_models()
    # and is no longer a graph output (confirmed empirically: onnx.compose
    # drops any m1 output named in io_map from the merged graph's outputs).
    # Build the mask the same way OnnxMtAdapter.kt does: from the hidden
    # state's own sequence-length dimension, all-ones (no padding, batch=1).
    (encoder_hidden_states,) = encoder_session.run(
        None,
        {
            "raw_text": np.array([text], dtype=object),
            "src_tag_id": np.array([src_tag_id], dtype=np.int64),
            "tgt_tag_id": np.array([tgt_tag_id], dtype=np.int64),
            "eos_id_const": np.array([eos_id], dtype=np.int64),
        },
    )
    attention_mask = np.ones((1, encoder_hidden_states.shape[1]), dtype=np.int64)

    # decoder_input_ids seeded with ONLY decoder_start_token_id -- see
    # indictrans_common.py's greedy_decode_onnx for the full explanation.
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
        decoder_input_ids = np.concatenate([decoder_input_ids, np.array([[next_id]], dtype=np.int64)], axis=1)
        if next_id == eos_id:
            break

    return detokenize(tgt_vocab, decoder_input_ids[0].tolist(), eos_id)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--in-dir", type=Path, required=True, help="Dir containing encoder.onnx / decoder.onnx.")
    parser.add_argument("--out-dir", type=Path, default=None, help="Defaults to --in-dir.")
    parser.add_argument("--direction", choices=sorted(CHECKPOINTS), required=True)
    parser.add_argument("--model-name-or-path", default=None, help="Override the default AI4Bharat checkpoint id.")
    parser.add_argument("--tokenizer-type", choices=["auto", "indictrans-toolkit"], default="auto")
    parser.add_argument("--src-lang", required=True, help="e.g. hi, te, en")
    parser.add_argument("--tgt-lang", required=True, help="e.g. en, hi, te, bn")
    parser.add_argument(
        "--test-file", type=Path, default=None,
        help="TSV of 'source<TAB>reference' lines. Skips verification if omitted.",
    )
    parser.add_argument("--skip-verify", action="store_true")
    parser.add_argument(
        "--embed-tokenizer", action=argparse.BooleanOptionalAction, default=True,
        help="Must match what export_indictrans2_onnx.py was run with (default: on).",
    )
    args = parser.parse_args()

    out_dir = args.out_dir or args.in_dir
    out_dir.mkdir(parents=True, exist_ok=True)

    encoder_src = args.in_dir / "encoder.onnx"
    decoder_src = args.in_dir / "decoder.onnx"
    if not encoder_src.exists() or not decoder_src.exists():
        raise SystemExit(f"Missing encoder.onnx/decoder.onnx in {args.in_dir}. Run export_indictrans2_onnx.py first.")

    encoder_dst = out_dir / "encoder.int8.onnx"
    decoder_dst = out_dir / "decoder.int8.onnx"

    print(f"Quantizing {encoder_src} -> {encoder_dst}", file=sys.stderr)
    quantize(encoder_src, encoder_dst)
    print(f"Quantizing {decoder_src} -> {decoder_dst}", file=sys.stderr)
    quantize(decoder_src, decoder_dst)

    print(f"encoder: {model_size_mb(encoder_src):.1f} MB -> {model_size_mb(encoder_dst):.1f} MB")
    print(f"decoder: {model_size_mb(decoder_src):.1f} MB -> {model_size_mb(decoder_dst):.1f} MB")

    shipped_files = [encoder_dst, decoder_dst]
    if args.embed_tokenizer:
        vocab_ids_src = args.in_dir / "vocab_ids.json"
        tgt_vocab_src = args.in_dir / "tgt_vocab.json"
        if not vocab_ids_src.exists() or not tgt_vocab_src.exists():
            raise SystemExit(
                f"Missing vocab_ids.json/tgt_vocab.json in {args.in_dir} -- "
                f"re-run export_indictrans2_onnx.py, or pass --no-embed-tokenizer "
                f"if this direction intentionally used the old plain graphs."
            )
        # Plain data, not weights -- not quantized, just copied + hashed.
        vocab_ids_dst = out_dir / "vocab_ids.json"
        tgt_vocab_dst = out_dir / "tgt_vocab.json"
        if vocab_ids_src != vocab_ids_dst:
            vocab_ids_dst.write_text(vocab_ids_src.read_text(encoding="utf-8"), encoding="utf-8")
            tgt_vocab_dst.write_text(tgt_vocab_src.read_text(encoding="utf-8"), encoding="utf-8")
        shipped_files += [vocab_ids_dst, tgt_vocab_dst]

    write_sha256sums(out_dir, shipped_files)
    print(f"Wrote {out_dir / 'SHA256SUMS.txt'} (spec #4.6 -- verify every download against this).")

    if args.skip_verify or args.test_file is None:
        print("\nSkipping verification (no --test-file given). Do this before shipping the model.")
        return

    model_name_or_path = args.model_name_or_path or CHECKPOINTS[args.direction]
    verify(out_dir, model_name_or_path, args.tokenizer_type, args.src_lang, args.tgt_lang,
           args.test_file, args.embed_tokenizer)


if __name__ == "__main__":
    main()
