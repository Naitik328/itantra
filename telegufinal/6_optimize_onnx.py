"""
Applies ONNX Runtime graph optimization to the exported model.

This is step 9 of the documented pipeline, which had never actually been run
for either Hindi or English -- both shipped models were plain exports.

Uses ORT_ENABLE_EXTENDED, not ORT_ENABLE_ALL. ORT_ENABLE_ALL performs
hardware-specific fusions that are only valid on the machine that ran the
optimization, so a model optimized that way on this Windows desktop could
misbehave or fail to load on an Android device. EXTENDED is portable.

Be honest about the payoff: it is small. On en_US-hfc_female-medium (same
VITS architecture, same size class) it measured generation 420 ms -> 389 ms
(~7%) at num_threads=4. Expect a similar small win here, not a big one --
the real latency wins are num_threads and not reloading the model per
utterance (see ../INTEGRATION.md).

NOTE ON QUANTIZATION: do not int8-quantize this model. It was measured on
this project already (benchmark_results.csv): a quantized Piper VITS ran
0.69 s -> 2.18 s, i.e. 3x SLOWER, because the HiFi-GAN decoder's Conv layers
have no efficient int8 CPU kernel. It also barely shrinks (63 -> 60 MB on
current onnxruntime).

Run AFTER 7_add_sherpa_metadata.py, so the metadata exists to carry over.

Usage:
    python 8_optimize_onnx.py te_IN-female-medium.onnx
"""
import os
import shutil
import sys

import onnx
import onnxruntime as ort

src = sys.argv[1] if len(sys.argv) > 1 else "te_IN-female-medium.onnx"
tmp = src + ".opt.tmp"

print(f"optimizing {src} (ORT_ENABLE_EXTENDED)...")
so = ort.SessionOptions()
so.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_EXTENDED
so.optimized_model_filepath = tmp
ort.InferenceSession(src, so, providers=["CPUExecutionProvider"])

# ORT can drop metadata_props when it re-serializes; carry them over if so.
m_src = onnx.load(src, load_external_data=False)
m_opt = onnx.load(tmp, load_external_data=False)
present = {kv.key for kv in m_opt.metadata_props}
required = {kv.key for kv in m_src.metadata_props}
missing = required - present

if missing:
    print(f"re-injecting dropped metadata: {sorted(missing)}")
    for kv in m_src.metadata_props:
        if kv.key in missing:
            e = m_opt.metadata_props.add()
            e.key, e.value = kv.key, kv.value
    onnx.save(m_opt, tmp)
else:
    print(f"metadata survived: {sorted(present)}")

if "sample_rate" not in {kv.key for kv in onnx.load(tmp, load_external_data=False).metadata_props}:
    raise SystemExit("ABORT: sample_rate missing — sherpa-onnx would crash on init.")

shutil.move(tmp, src)
print(f"done: {src} ({os.path.getsize(src) / 1e6:.1f} MB)")
print("Re-test with try_it_yourself.py before committing.")
