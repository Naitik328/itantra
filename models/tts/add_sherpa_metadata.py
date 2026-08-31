"""
Injects sherpa-onnx's required metadata into a Piper-exported ONNX model,
and generates the tokens.txt file sherpa-onnx expects alongside it.

Based on sherpa-onnx's official scripts/piper/add_meta_data.py
(https://github.com/k2-fsa/sherpa-onnx), adapted to read config values
directly instead of via CLI args/iso639, since we already know the
language/voice for each of our fine-tunes.
"""
import argparse
import json
import os

import onnx


def load_config(config_path):
    with open(config_path, "r", encoding="utf-8") as f:
        return json.load(f)


def generate_tokens(config, out_path):
    id_map = config["phoneme_id_map"]
    with open(out_path, "w", encoding="utf-8") as f:
        for s, i in id_map.items():
            if s == "\n":
                continue
            if isinstance(i, list):
                i = i[0]
            f.write(f"{s} {i}\n")
    print(f"Generated {out_path}")


def add_meta_data(onnx_path, meta_data):
    model = onnx.load(onnx_path)
    while len(model.metadata_props):
        model.metadata_props.pop()
    for key, value in meta_data.items():
        meta = model.metadata_props.add()
        meta.key = key
        meta.value = str(value)
    onnx.save(model, onnx_path)
    print(f"Patched metadata into {onnx_path}: {meta_data}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--onnx", required=True, help="Path to the .onnx file (patched in place)")
    parser.add_argument("--config", required=True, help="Path to the matching .onnx.json file")
    parser.add_argument("--language", required=True, help="Full language name, e.g. Hindi, English")
    args = parser.parse_args()

    config = load_config(args.config)

    tokens_path = os.path.join(os.path.dirname(args.onnx) or ".", "tokens.txt")
    generate_tokens(config, tokens_path)

    sample_rate = config["audio"]["sample_rate"]
    if sample_rate == 22500:
        sample_rate = 22050

    voice = config.get("espeak", {}).get("voice") or config.get("lang_code")

    meta_data = {
        "model_type": "vits",
        "comment": "piper",
        "language": args.language,
        "voice": voice,
        "version": 1,
        "has_espeak": 1,
        "has_g2pw": 0,
        "n_speakers": config["num_speakers"],
        "sample_rate": sample_rate,
    }
    add_meta_data(args.onnx, meta_data)


if __name__ == "__main__":
    main()
