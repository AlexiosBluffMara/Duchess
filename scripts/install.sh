#!/usr/bin/env bash
# install.sh — Build and install Duchess apps to connected Android devices
#
# Usage:
#   ./scripts/install.sh phone    # Pixel 9 Fold only
#   ./scripts/install.sh glasses  # Vuzix M400 only
#   ./scripts/install.sh all      # Both devices
#   ./scripts/install.sh          # Interactive device selection
#
# This script:
#   1. Detects connected Android devices via adb
#   2. Lets you select which device is which (Pixel vs. M400)
#   3. Builds the APK for each target
#   4. Installs + launches the app on each device
#
# Prerequisites:
#   - adb in PATH (included with Android SDK platform-tools)
#   - Devices connected via USB with USB debugging enabled
#   - Both devices in "adb devices" list

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"

TARGET="${1:-all}"

# --- Device detection ---
echo "=== Duchess Install Script ==="
echo ""
echo "Detecting connected devices..."
DEVICES=$(adb devices | grep -v "List of devices" | grep "device$" | awk '{print $1}')
DEVICE_COUNT=$(echo "$DEVICES" | grep -c . || true)

if [[ $DEVICE_COUNT -eq 0 ]]; then
    echo "✗ No Android devices found."
    echo ""
    echo "Troubleshooting:"
    echo "  1. Connect device via USB"
    echo "  2. Enable USB debugging: Settings → Developer Options → USB Debugging"
    echo "  3. Accept the 'Allow USB debugging?' prompt on the device"
    echo "  4. Run: adb devices"
    exit 1
fi

echo "Found $DEVICE_COUNT device(s):"
i=1
while IFS= read -r device; do
    MODEL=$(adb -s "$device" shell getprop ro.product.model 2>/dev/null | tr -d '\r' || echo "unknown")
    echo "  [$i] $device — $MODEL"
    ((i++))
done <<< "$DEVICES"
echo ""

# --- Helper: install to device ---
install_apk() {
    local DEVICE_ID="$1"
    local APK_PATH="$2"
    local APP_PACKAGE="$3"
    local APP_ACTIVITY="$4"

    echo "Installing to $DEVICE_ID..."
    adb -s "$DEVICE_ID" install -r "$APK_PATH"

    echo "Launching $APP_PACKAGE..."
    adb -s "$DEVICE_ID" shell am start -n "$APP_PACKAGE/$APP_ACTIVITY" || true
    echo "✓ Installed and launched on $DEVICE_ID"
}

# --- Phone install (Pixel 9 Fold) ---
install_phone() {
    echo "--- Pixel 9 Fold (Companion App) ---"

    # Build first
    "$SCRIPT_DIR/build-phone.sh"

    PHONE_APK="$ROOT_DIR/app-phone/app/build/outputs/apk/debug/app-debug.apk"
    if [[ ! -f "$PHONE_APK" ]]; then
        echo "✗ Phone APK not found after build."
        exit 1
    fi

    # Select device
    if [[ $DEVICE_COUNT -eq 1 ]]; then
        PHONE_DEVICE=$(echo "$DEVICES" | head -1)
    else
        echo "Which device is the Pixel 9 Fold? (enter number)"
        read -r SELECTION
        PHONE_DEVICE=$(echo "$DEVICES" | sed -n "${SELECTION}p")
    fi

    install_apk \
        "$PHONE_DEVICE" \
        "$PHONE_APK" \
        "com.duchess.companion" \
        "com.duchess.companion.MainActivity"
}

# --- Glasses install (Vuzix M400) ---
install_glasses() {
    echo "--- Vuzix M400 (Glasses App) ---"

    # Build first
    "$SCRIPT_DIR/build-glasses.sh"

    GLASSES_APK="$ROOT_DIR/app-glasses/app/build/outputs/apk/debug/app-debug.apk"
    if [[ ! -f "$GLASSES_APK" ]]; then
        echo "✗ Glasses APK not found after build."
        exit 1
    fi

    # Select device
    if [[ $DEVICE_COUNT -eq 1 ]]; then
        GLASSES_DEVICE=$(echo "$DEVICES" | head -1)
    else
        echo "Which device is the Vuzix M400? (enter number)"
        read -r SELECTION
        GLASSES_DEVICE=$(echo "$DEVICES" | sed -n "${SELECTION}p")
    fi

    install_apk \
        "$GLASSES_DEVICE" \
        "$GLASSES_APK" \
        "com.duchess.glasses" \
        "com.duchess.glasses.MainActivity"
}

# --- Execute target ---
case "$TARGET" in
    phone)   install_phone ;;
    glasses) install_glasses ;;
    all)
        install_phone
        echo ""
        install_glasses
        ;;
    *)
        echo "Unknown target: $TARGET"
        echo "Usage: $0 [phone|glasses|all]"
        exit 1
        ;;
esac

echo ""
echo "=== Done ==="
echo ""
echo "Next steps:"
echo "  Phone: Open Duchess app, navigate to Stream tab"
echo "  M400:  The glasses app auto-starts its camera pipeline"
echo "  Pair:  Make sure phone and M400 are BLE-paired (Bluetooth settings)"
