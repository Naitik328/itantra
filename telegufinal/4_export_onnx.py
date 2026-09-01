"""
Exports a trained Piper checkpoint to ONNX, using the legacy (non-dynamo)
TorchScript-based exporter -- newer PyTorch (2.9+) defaults to the new
torch.export-based exporter, which fails on this model's dynamic control
flow (rational_quadratic_spline in transforms.py). dynamo=False forces the
old, working path regardless of local PyTorch version.

Run from inside the piper1-gpl repo (needs piper.train.export_onnx importable):
    cd piper_work/piper1-gpl
    python 6_export_onnx.py --checkpoint <path/to/last.ckpt> --output-file <path/to/model.onnx>
"""
import argparse

import torch

from piper.train.export_onnx import main as export_onnx_main
import piper.train.export_onnx as export_onnx_module

# Patch the module's torch.onnx.export call to force dynamo=False.
_original_export = torch.onnx.export


def _patched_export(*args, **kwargs):
    kwargs.setdefault("dynamo", False)
    return _original_export(*args, **kwargs)


def main():
    torch.onnx.export = _patched_export
    export_onnx_main()


if __name__ == "__main__":
    main()
