#!/usr/bin/env bash
# Compiles translation/kotlin/com/itantra/mt/*.kt against the REAL
# dependency jars (not stubs) and runs SmokeTest.kt against the result.
# No Gradle/Android module exists on this branch yet, so this is a
# standalone verification path -- see translation/translation_state.md.
#
# Downloads (first run only, cached under --cache-dir) real artifacts:
#   - kotlin-compiler (JetBrains GitHub release)
#   - com.microsoft.onnxruntime:onnxruntime (desktop jar -- same
#     ai.onnxruntime.* API surface as onnxruntime-android; only the native
#     library differs, which doesn't matter for compiling Kotlin source)
#   - com.microsoft.onnxruntime:onnxruntime-extensions-android (AAR;
#     classes.jar is extracted for ai.onnxruntime.extensions.OrtxPackage)
#   - org.json:json (real implementation; Android's own is a compile-time
#     stub only, this is what Android SDK's own compile classpath uses too)
#
# Usage: translation/kotlin_verify/verify.sh [--cache-dir DIR]
set -euo pipefail

CACHE_DIR="${1:-$HOME/.local/kotlin}"
[[ "${1:-}" == "--cache-dir" ]] && CACHE_DIR="$2"

KOTLIN_VERSION=2.0.21
ONNXRUNTIME_VERSION=1.19.2
ONNXRUNTIME_EXTENSIONS_VERSION=0.13.0
ORG_JSON_VERSION=20240303

KOTLINC="$CACHE_DIR/kotlinc/bin/kotlinc"
LIBS="$CACHE_DIR/libs"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SRC_DIR="$SCRIPT_DIR/../kotlin/com/itantra/mt"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

mkdir -p "$CACHE_DIR" "$LIBS"

if [[ ! -x "$KOTLINC" ]]; then
  echo "Downloading kotlin-compiler $KOTLIN_VERSION ..."
  curl -sL -o "$CACHE_DIR/kotlin-compiler.zip" \
    "https://github.com/JetBrains/kotlin/releases/download/v$KOTLIN_VERSION/kotlin-compiler-$KOTLIN_VERSION.zip"
  unzip -q -o "$CACHE_DIR/kotlin-compiler.zip" -d "$CACHE_DIR"
  rm "$CACHE_DIR/kotlin-compiler.zip"
fi

if [[ ! -f "$LIBS/onnxruntime.jar" ]]; then
  echo "Downloading onnxruntime $ONNXRUNTIME_VERSION (desktop jar, same Java API as onnxruntime-android) ..."
  curl -sL -o "$LIBS/onnxruntime.jar" \
    "https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime/$ONNXRUNTIME_VERSION/onnxruntime-$ONNXRUNTIME_VERSION.jar"
fi

if [[ ! -f "$LIBS/json.jar" ]]; then
  echo "Downloading org.json $ORG_JSON_VERSION ..."
  curl -sL -o "$LIBS/json.jar" \
    "https://repo1.maven.org/maven2/org/json/json/$ORG_JSON_VERSION/json-$ORG_JSON_VERSION.jar"
fi

if [[ ! -f "$LIBS/onnxruntime-extensions.jar" ]]; then
  echo "Downloading onnxruntime-extensions-android $ONNXRUNTIME_EXTENSIONS_VERSION (extracting classes.jar) ..."
  TMP_AAR="$BUILD_DIR/ortx.aar"
  curl -sL -o "$TMP_AAR" \
    "https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime-extensions-android/$ONNXRUNTIME_EXTENSIONS_VERSION/onnxruntime-extensions-android-$ONNXRUNTIME_EXTENSIONS_VERSION.aar"
  mkdir -p "$BUILD_DIR/aar"
  unzip -q -o "$TMP_AAR" classes.jar -d "$BUILD_DIR/aar"
  cp "$BUILD_DIR/aar/classes.jar" "$LIBS/onnxruntime-extensions.jar"
fi

CP="$LIBS/onnxruntime.jar:$LIBS/json.jar:$LIBS/onnxruntime-extensions.jar"

echo ""
echo "Compiling translation/kotlin/com/itantra/mt/*.kt + SmokeTest.kt ..."
"$KOTLINC" -classpath "$CP" -jvm-target 1.8 -d "$BUILD_DIR/classes" \
  "$SRC_DIR"/*.kt "$SCRIPT_DIR/SmokeTest.kt"
echo "Compile OK."

KOTLIN_STDLIB="$(find "$CACHE_DIR/kotlinc/lib" -name 'kotlin-stdlib.jar')"
echo ""
echo "Running SmokeTest ..."
java -classpath "$BUILD_DIR/classes:$CP:$KOTLIN_STDLIB" SmokeTestKt
