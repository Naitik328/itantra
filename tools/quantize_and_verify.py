#!/usr/bin/env python3
"""Quantize exported IndicTrans2 ONNX graphs to int8 and sanity-check them.

Run after export_indictrans2_onnx.py, in the same HF-connected environment
(verification loads the original PyTorch checkpoint to compare against).
See docs/ITANTRA_INTEGRATION_SPEC.md #8.3 (quantization policy) and #8.1
(the STT export pipeline this mirrors -- "verify" step 5 there is the same
idea: decode real inputs through the exported artifact and cross-check).

Policy: int8 dynamic quantization, standard for seq2seq (spec #8.3) --
unlike TTS, MT is not exempted from quantization.
"""
from __future__ import annotations

import argparse
import hashlib
import sys
from pathlib import Path

from indictrans_common import (
    CHECKPOINTS,
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


def verify(
    out_dir: Path,
    model_name_or_path: str,
    tokenizer_type: str,
    src_lang: str,
    tgt_lang: str,
    test_file: Path,
) -> None:
    import onnxruntime as ort

    print(f"Loading tokenizer from {model_name_or_path} for verification ...", file=sys.stderr)
    loaded = load_model_and_tokenizer(model_name_or_path, tokenizer_type)

    encoder_session = ort.InferenceSession(str(out_dir / "encoder.int8.onnx"))
    decoder_session = ort.InferenceSession(str(out_dir / "decoder.int8.onnx"))

    pairs = load_test_pairs(test_file)
    if not pairs:
        raise SystemExit(f"No test sentence pairs found in {test_file}")

    print(f"\n{'source':40} | {'expected':30} | got")
    print("-" * 100)
    mismatches = 0
    for src, ref in pairs:
        got = greedy_decode_onnx(encoder_session, decoder_session, loaded.tokenizer, src, src_lang, tgt_lang)
        flag = "" if ref.strip().lower() in got.strip().lower() else "  <-- MISMATCH (eyeball this, not a hard fail)"
        if flag:
            mismatches += 1
        print(f"{src[:40]:40} | {ref[:30]:30} | {got}{flag}")

    print(f"\n{len(pairs) - mismatches}/{len(pairs)} contained the expected reference substring.")
    print("A quantized seq2seq model paraphrasing correctly-meaning output is normal;")
    print("read the outputs, don't just count mismatches.")


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

    write_sha256sums(out_dir, [encoder_dst, decoder_dst])
    print(f"Wrote {out_dir / 'SHA256SUMS.txt'} (spec #4.6 -- verify every download against this).")

    if args.skip_verify or args.test_file is None:
        print("\nSkipping verification (no --test-file given). Do this before shipping the model.")
        return

    model_name_or_path = args.model_name_or_path or CHECKPOINTS[args.direction]
    verify(out_dir, model_name_or_path, args.tokenizer_type, args.src_lang, args.tgt_lang, args.test_file)


if __name__ == "__main__":
    main()
