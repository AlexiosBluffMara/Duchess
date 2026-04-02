#!/usr/bin/env bash
# build-phone.sh — Build the Duchess companion app for Pixel 9 Fold
#
# Usage:
#   ./scripts/build-phone.sh          # debug build (default)
#   ./scripts/build-phone.sh release  # release build (requires signing config)
#
# Prerequisites:
#   1. JDK 17+ in PATH (check: java -version)
#   2. app-phone/local.properties with github_token=ghp_...
#      (copy app-phone/local.properties.example and fill in your token)
#   3. Android SDK installed (ANDROID_HOME or ANDROID_SDK_ROOT set)
#
# Output APK:
#   debug:   app-phone/app/build/outputs/apk/debug/app-debug.apk
#   release: app-phone/app/build/outputs/apk/release/app-release.apk

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"
PHONE_DIR="$ROOT_DIR/app-phone"
BUILD_TYPE="${1:-debug}"

echo "=== Duchess Phone App Build ==="
echo "Build type: $BUILD_TYPE"
echo "Project dir: $PHONE_DIR"
echo ""

# Validate local.properties exists
if [[ ! -f "$PHONE_DIR/local.properties" ]]; then
    echo "ERROR: $PHONE_DIR/local.properties not found."
    echo ""
    echo "Create it from the template:"
    echo "  cp $PHONE_DIR/local.properties.example $PHONE_DIR/local.properties"
    echo ""
    echo "Then fill in your GitHub PAT with read:packages scope:"
    echo "  github_user=your-github-username"
    echo "  github_token=ghp_your_personal_access_token"
    echo ""
    echo "Get a PAT at: https://github.com/settings/tokens"
    echo "Required scope: read:packages"
    exit 1
fi

# Check for sdk.dir in local.properties; if missing, try ANDROID_HOME
if ! grep -q "sdk.dir" "$PHONE_DIR/local.properties"; then
    if [[ -n "${ANDROID_HOME:-}" ]]; then
        echo "sdk.dir=$ANDROID_HOME" >> "$PHONE_DIR/local.properties"
        echo "Added sdk.dir=$ANDROID_HOME to local.properties"
    elif [[ -n "${ANDROID_SDK_ROOT:-}" ]]; then
        echo "sdk.dir=$ANDROID_SDK_ROOT" >> "$PHONE_DIR/local.properties"
        echo "Added sdk.dir=$ANDROID_SDK_ROOT to local.properties"
    else
        echo "WARNING: sdk.dir not in local.properties and ANDROID_HOME not set."
        echo "Gradle may fail. Set ANDROID_HOME to your SDK directory."
    fi
fi

cd "$PHONE_DIR"

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
    echo "  APK: $PHONE_DIR/$APK_PATH ($SIZE)"
    echo ""
    echo "To install on connected Pixel 9 Fold:"
    echo "  adb install -r '$PHONE_DIR/$APK_PATH'"
    echo "  (or run: ./scripts/install-phone.sh)"
else
    echo ""
    echo "✗ Build failed — APK not found at expected path."
    echo "Check the output above for errors."
    exit 1
fi
