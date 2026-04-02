# Duchess — Hardware Quickstart

Get both apps running on your Pixel 9 Fold and Vuzix M400 in ~30 minutes.

---

## What you need

| Item | Details |
|------|---------|
| Pixel 9 Fold | USB cable, Developer Options enabled |
| Vuzix M400 | USB cable, adb enabled |
| Meta Ray-Ban Wayfarer | Bluetooth pairing only — no USB needed |
| Mac / Linux terminal | adb, JDK 17, Android SDK |
| GitHub account | PAT with `read:packages` scope |

---

## Step 1 — Prerequisites (5 min)

### 1a. Check adb
```bash
adb version
# Should show: Android Debug Bridge version 1.x.x
```
If missing: Install Android platform-tools from `https://developer.android.com/tools/releases/platform-tools`

### 1b. Check JDK 17
```bash
java -version
# Should show: openjdk version "17" or "21"
```
If missing: `brew install openjdk@17` (Mac) or download from adoptium.net

### 1c. Create a GitHub PAT
1. Go to https://github.com/settings/tokens → "Generate new token (classic)"
2. Check **`read:packages`** scope (no other scopes needed)
3. Copy the token: `ghp_xxxxxxxxxxxxxxxxxxxx`

---

## Step 2 — Phone App (Pixel 9 Fold) Setup (10 min)

### 2a. Enable USB debugging on Pixel
1. Settings → About Phone → tap "Build Number" 7 times
2. Settings → Developer Options → USB Debugging: ON
3. Connect USB cable → tap "Allow" on the trust dialog

### 2b. Configure local.properties
```bash
cd app-phone
cp local.properties.example local.properties
```

Edit `app-phone/local.properties`:
```
sdk.dir=/Users/YOUR_USER/Library/Android/sdk   # or your SDK path
github_user=your-github-username
github_token=ghp_your_token_from_step_1c
```

### 2c. Build and install
```bash
./scripts/build-phone.sh
adb install -r app-phone/app/build/outputs/apk/debug/app-debug.apk
```

Or use the combined script:
```bash
./scripts/install.sh phone
```

### 2d. First launch
The app opens in **Demo Mode** (yellow dot in top bar). You'll see:
- Dashboard with zone safety scores
- Alerts tab with 9 sample PPE violations
- HUD Simulator tab showing what the glasses display looks like
- Stream tab (shows "Glasses disconnected" until paired)

No glasses hardware needed for this demo.

---

## Step 3 — Glasses App (Vuzix M400) Setup (10 min)

### 3a. Enable USB debugging on M400
The M400 boots to a custom launcher. To enable adb:
1. Go to Settings → Developer Options → USB Debugging: ON
2. Or: connect via USB, the device should appear in `adb devices`

### 3b. Build and install
```bash
./scripts/build-glasses.sh
```

The build will note if the PPE model is a placeholder stub.
For the demo, the **stub is fine** — the app runs in demo mode with synthetic detections.

Install to M400:
```bash
# Find M400 device ID:
adb devices
# Output example:
#   List of devices attached
#   192.168.1.100:5555  device   ← Pixel
#   ZV00123456          device   ← M400

adb -s ZV00123456 install -r app-glasses/app/build/outputs/apk/debug/app-debug.apk
```

### 3c. App launch on M400
The app auto-launches its camera pipeline. You'll see the HUD overlay on the M400 display:
- Green status bar: "All Clear / Sin alertas"
- Detection boxes drawn on camera feed (synthetic in demo mode)
- FPS counter and inference time in bottom bar

---

## Step 4 — Connect Meta Ray-Bans to Pixel (for live stream)

The Ray-Bans connect via the Meta DAT SDK over Bluetooth Classic.

### 4a. Pair Ray-Bans
1. On Pixel: Settings → Bluetooth → scan for "Ray-Ban Meta"
2. Pair the glasses — accept the pairing dialog on phone

### 4b. Enable live stream mode
1. Open Duchess app on Pixel
2. Go to Stream tab
3. Tap "Start Stream"
4. The glasses camera feed should appear after 3-5 seconds
5. The status dot in the top bar turns green

### 4c. Enable live AI inference
On the Stream screen (once connected):
1. Select a zone from the dropdown (e.g., "Zone A — Framing")
2. Toggle "AI Monitoring" ON
3. Gemma 4 E2B analyzes frames at 1 FPS
4. Violations appear in the Alerts tab in real time

**Note**: For AI inference you need either:
- The Gemma model downloaded (tap "Download Model" in Settings), OR
- Run in Demo Mode (AI returns synthetic violations — useful for demos)

---

## Step 5 — BLE alert flow (M400 receives alerts from Pixel)

When the Pixel detects a violation via Gemma or YOLO:
1. Pixel's `BleGattServer` broadcasts the alert to paired glasses
2. M400's `BleGattClient` receives it and updates the HUD
3. HUD shows: red alert banner + violation type in English + Spanish

For this to work:
- M400 must be BLE-connected to the Pixel (the app connects automatically after first pairing)
- The "BLE" dot in the M400 HUD should be green/white

---

## Demo Mode vs. Live Mode

| | Demo Mode | Live Mode |
|---|-----------|-----------|
| PPE model | Synthetic detections | Real YOLOv8-nano |
| Gemma inference | Synthetic alerts | Real Gemma 4 E2B |
| BLE | Simulated | Real BLE connection |
| Ray-Ban stream | Static placeholder | Live camera feed |
| Use for | Training, UI review, presentations | Active site monitoring |

**Demo mode is the default.** It's controlled by `DEMO_MODE = true` in `MainActivity.kt`.
Flip to `false` when testing with real hardware.

---

## Get a real PPE model (optional — for real detection)

Run the Python script to download and convert a real YOLOv8 PPE model:

```bash
# Install Python deps first:
pip install ultralytics onnx

# Download and convert:
python3 scripts/download-ppe-model.py

# Output: app-glasses/app/src/main/assets/yolov8_nano_ppe.tflite (~4MB)
# Then rebuild the glasses app:
./scripts/build-glasses.sh
./scripts/install.sh glasses
```

---

## Troubleshooting

| Problem | Fix |
|---------|-----|
| `adb: no devices/emulators found` | Enable USB debugging, accept trust dialog, try a different cable |
| `INSTALL_FAILED_CPU_ABI_INCOMPATIBLE` | Pixel is fine; M400 is ARM64 — ensure `abiFilters` includes `arm64-v8a` in build.gradle |
| `Could not resolve com.meta.wearable:mwdat-core` | Check `github_token` in `local.properties`; verify PAT has `read:packages` |
| App crashes on M400 at launch | Check logcat: `adb -s <device> logcat -s Duchess` |
| Ray-Bans won't stream | Confirm they're paired in Bluetooth settings AND connected in Meta's app |
| `model_setup_title` resource not found | Run `./gradlew assembleDebug` from project root, not `app/` subdirectory |

### Useful adb commands
```bash
# Watch logs from phone:
adb -s <pixel-id> logcat -s DuchessApp,GemmaEngine,BleServer

# Watch logs from M400:
adb -s <m400-id> logcat -s DuchessGlasses,PpeDetector,BleGattClient

# Take screenshot from phone:
adb -s <pixel-id> exec-out screencap -p > phone_screen.png

# Restart app on phone:
adb -s <pixel-id> shell am force-stop com.duchess.companion
adb -s <pixel-id> shell am start com.duchess.companion/.MainActivity

# Restart app on M400:
adb -s <m400-id> shell am force-stop com.duchess.glasses
adb -s <m400-id> shell am start com.duchess.glasses/.MainActivity
```
