#!/usr/bin/env bash
# build-glasses.sh — Build the Duchess glasses app for Vuzix M400
#
# Usage:
#   ./scripts/build-glasses.sh         # debug build (default)
#   ./scripts/build-glasses.sh release # release build
#
# Prerequisites:
#   1. JDK 17+ in PATH
#   2. Android SDK with compileSdk 33 (Vuzix M400 runs AOSP Android 13)
#   3. The real yolov8_nano_ppe.tflite in app-glasses/app/src/main/assets/
#      (run scripts/download-ppe-model.py to get it, or use the stub for demo mode)
#
# Output APK:
#   debug: app-glasses/app/build/outputs/apk/debug/app-debug.apk
#
# NOTE: The Vuzix M400 uses AOSP Android 13 with NO Google Play Services.
# This app can ONLY be installed via adb sideloading — it's not on the Play Store.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"
GLASSES_DIR="$ROOT_DIR/app-glasses"
BUILD_TYPE="${1:-debug}"

echo "=== Duchess Glasses App Build (Vuzix M400) ==="
echo "Build type: $BUILD_TYPE"
echo "Project dir: $GLASSES_DIR"
echo ""

# Check model file
MODEL_PATH="$GLASSES_DIR/app/src/main/assets/yolov8_nano_ppe.tflite"
if [[ -f "$MODEL_PATH" ]]; then
    MODEL_SIZE=$(wc -c < "$MODEL_PATH")
    if [[ $MODEL_SIZE -lt 102400 ]]; then
        echo "NOTE: yolov8_nano_ppe.tflite is a placeholder stub ($MODEL_SIZE bytes)."
        echo "      The app will build and run in DEMO MODE (synthetic detections)."
        echo "      For real PPE detection, run: python3 scripts/download-ppe-model.py"
        echo ""
    else
        SIZE_MB=$((MODEL_SIZE / 1048576))
        echo "✓ Real PPE model present (${SIZE_MB}MB)"
    fi
else
    echo "WARNING: yolov8_nano_ppe.tflite not found at $MODEL_PATH"
    echo "         Build may fail. Run: python3 scripts/download-ppe-model.py"
fi

# Add sdk.dir if needed
if [[ ! -f "$GLASSES_DIR/local.properties" ]]; then
    if [[ -n "${ANDROID_HOME:-}" ]]; then
        echo "sdk.dir=$ANDROID_HOME" > "$GLASSES_DIR/local.properties"
    elif [[ -n "${ANDROID_SDK_ROOT:-}" ]]; then
        echo "sdk.dir=$ANDROID_SDK_ROOT" > "$GLASSES_DIR/local.properties"
    fi
fi

cd "$GLASSES_DIR"

echo "Running Gradle $BUILD_TYPE build..."
./gradlew "assemble$(tr '[:lower:]' '[:upper:]' <<< "${BUILD_TYPE:0:1}")${BUILD_TYPE:1}" \
    --no-daemon \
    --stacktrace \
    2>&1 | tail -50

APK_PATH="app/build/outputs/apk/$BUILD_TYPE/app-$BUILD_TYPE.apk"
if [[ -f "$APK_PATH" ]]; then
    SIZE=$(du -h "$APK_PATH" | cut -f1)
    echo ""
    echo "✓ Build successful!"
    echo "  APK: $GLASSES_DIR/$APK_PATH ($SIZE)"
    echo ""
    echo "To install on connected Vuzix M400:"
    echo "  adb -s <M400-device-id> install -r '$GLASSES_DIR/$APK_PATH'"
    echo "  (or run: ./scripts/install-glasses.sh)"
    echo ""
    echo "To find device ID: adb devices"
else
    echo ""
    echo "✗ Build failed — APK not found."
    exit 1
fi
