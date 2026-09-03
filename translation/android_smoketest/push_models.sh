#!/usr/bin/env bash
# Pushes the shipped MT bundle (not the fp32/debug intermediates) onto a
# connected device or running emulator, into this test app's own
# external-files directory -- no root/run-as needed, adb push can write
# there directly. Run AFTER `./gradlew :app:installDebugAndroidTest` (or
# just installDebug) so the app/package exists on the device first.
#
# Usage: translation/android_smoketest/push_models.sh [path-to-export-dir]
#   path-to-export-dir defaults to ~/itantra-mt-export (see
#   translation/translation_state.md for how that was produced --
#   tools/export_indictrans2_onnx.py + tools/quantize_and_verify.py).
set -euo pipefail

EXPORT_DIR="${1:-$HOME/itantra-mt-export}"
PACKAGE="com.itantra.mttest"
DEVICE_BASE="/sdcard/Android/data/$PACKAGE/files/mt"

if ! adb get-state >/dev/null 2>&1; then
  echo "No device/emulator detected (adb get-state failed). Connect one first." >&2
  exit 1
fi

for direction in en-indic indic-en; do
  src="$EXPORT_DIR/$direction"
  if [[ ! -f "$src/encoder.int8.onnx" ]]; then
    echo "Missing $src/encoder.int8.onnx -- did the export actually run for $direction?" >&2
    exit 1
  fi
  echo "Pushing $direction ..."
  adb shell mkdir -p "$DEVICE_BASE/$direction"
  for f in encoder.int8.onnx decoder.int8.onnx vocab_ids.json tgt_vocab.json; do
    adb push "$src/$f" "$DEVICE_BASE/$direction/$f"
  done
done

echo ""
echo "Done. Verify with: adb shell run-as $PACKAGE ls -la files/mt/en-indic files/mt/indic-en"
echo "(or, if run-as is unavailable on this build type: adb shell ls -la $DEVICE_BASE/en-indic $DEVICE_BASE/indic-en)"
