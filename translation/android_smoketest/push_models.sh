#!/usr/bin/env bash
# Pushes the shipped MT bundle (not the fp32/debug intermediates) onto a
# connected device or running emulator, into this test app's own
# external-files directory -- no root/run-as needed, adb push can write
# there directly. Run AFTER `./gradlew :app:installDebugAndroidTest` (or
# just installDebug) so the app/package exists on the device first.
#
# Usage: translation/android_smoketest/push_models.sh [path-to-export-dir] [--skip-checksum]
#   path-to-export-dir defaults to ~/itantra-mt-export (see
#   translation/translation_state.md for how that was produced --
#   tools/export_indictrans2_onnx.py + tools/quantize_and_verify.py).
set -euo pipefail

SKIP_CHECKSUM=false
EXPORT_DIR="$HOME/itantra-mt-export"
for arg in "$@"; do
  case "$arg" in
    --skip-checksum) SKIP_CHECKSUM=true ;;
    *) EXPORT_DIR="$arg" ;;
  esac
done

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

  # Verify BEFORE pushing -- a truncated/corrupted transfer onto this
  # machine (pendrive, cloud sync, whatever) looks exactly like a healthy
  # push and only fails later as an opaque ORT_INVALID_PROTOBUF on-device.
  # Catch it here instead. Pass --skip-checksum to bypass (e.g. iterating
  # on a file you already know is fine and haven't re-copied).
  if [[ "$SKIP_CHECKSUM" == false ]]; then
    echo "Verifying checksums for $direction ..."
    if ! (cd "$src" && sha256sum -c SHA256SUMS.txt --ignore-missing); then
      echo "" >&2
      echo "CHECKSUM MISMATCH in $src -- do not push these files." >&2
      echo "This means the copy onto THIS machine is corrupted (not the" >&2
      echo "original export) -- re-transfer from the source, don't retry" >&2
      echo "the push. See translation/TRANSLATION_INTEGRATION_ISSUES.md #4" >&2
      echo "for a real instance of exactly this." >&2
      exit 1
    fi
  fi

  echo "Pushing $direction ..."
  adb shell mkdir -p "$DEVICE_BASE/$direction"
  for f in encoder.int8.onnx decoder.int8.onnx vocab_ids.json tgt_vocab.json; do
    adb push "$src/$f" "$DEVICE_BASE/$direction/$f"
  done
done

# adb shell mkdir -p creates directories owned by shell at mode 770 by
# default -- the app's own uid can't traverse into them, so a plain
# File(...).exists() check inside the app silently reports "models not
# found" instead of a permission error. Files themselves are already
# world-writable from adb push; it's specifically the directory mode that
# blocks it. See translation/TRANSLATION_INTEGRATION_ISSUES.md #3.
echo "Fixing directory permissions (adb push leaves them at mode 770, unreadable by the app) ..."
adb shell chmod 777 "$DEVICE_BASE" "$DEVICE_BASE/en-indic" "$DEVICE_BASE/indic-en"

echo ""
echo "Done. Verify with: adb shell run-as $PACKAGE ls -la files/mt/en-indic files/mt/indic-en"
echo "(or, if run-as is unavailable on this build type: adb shell ls -la $DEVICE_BASE/en-indic $DEVICE_BASE/indic-en)"
