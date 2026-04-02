# Duchess Companion App — Demo Setup Guide

## Prerequisites

| Requirement | Status |
|---|---|
| macOS with Apple Silicon (M-series) | Required for ARM64 emulator |
| Android Studio (Ladybug or newer) | Required |
| JDK 17 (Temurin) | Already installed |
| Android SDK Platform 35+ | Already installed |
| Gradle 8.11+ | Included via wrapper |

---

## Quick Start (5 minutes)

### Step 1: Open in Android Studio

1. Open **Android Studio**
2. **File → Open** → navigate to `/Users/soumitlahiri/Duchess/app-phone/`
3. Wait for Gradle sync to complete (1–2 minutes)
   - If prompted about Gradle wrapper, click **"Use Gradle wrapper"**
   - If prompted about JDK, select **Temurin 17**

### Step 2: Create an Emulator (if none exists)

1. **Tools → Device Manager** (or click the phone+tablet icon in the toolbar)
2. Click **"Create Virtual Device"**
3. Select hardware: **Pixel Fold** (search "Fold" in the list)
   - If Pixel Fold isn't available, use **Pixel 7 Pro** or any phone with API 30+
4. Select system image: **VanillaIceCream (API 36)** → arm64-v8a (should already be downloaded)
   - If not downloaded, click the download link next to it
5. Name: `Pixel Fold API 36` (or any name)
6. Click **Finish**

### Step 3: Run the App

1. In the toolbar, select your emulator from the device dropdown
2. Click the **green Run ▶️ button** (or press **Ctrl+R** / **^R**)
3. Wait for build + install (first build ~30s, subsequent ~5s)
4. The app launches with the **Duchess splash screen** (construction orange, shield icon)
5. After 2 seconds, auto-navigates to the **Dashboard**

---

## What You'll See (Demo Walkthrough)

### Splash Screen (auto, 2 seconds)
- Full-screen construction orange background
- Animated shield icon and "Duchess" branding
- "Construction Site Intelligence" tagline
- Auto-navigates to Dashboard

### Dashboard Tab
- **Safety Score gauge** — animated 270° arc showing 87/100
- **Zone cards** — 5 construction zones with scores, worker counts, active alerts
- **Quick stats row** — active workers (24), total alerts (9)
- **Connection chip** — yellow "Demo Mode" indicator
- **"View Glasses HUD"** card at bottom → opens HUD Simulator
- Tap any zone card → navigates to Alerts tab

### Stream Tab
- Simulated camera feed with charcoal background
- **4 detection bounding boxes** drawn on Canvas:
  - Green: HARDHAT ✓ (0.94), VEST ✓ (0.88)
  - Red pulsing: NO GLASSES ✗ (0.91)
  - Yellow: PERSON (0.76)
- Animated shimmer scan line
- FPS / inference time / battery badges
- Camera shutter button and Start/Stop toggle

### Alerts Tab
- **Filter chips**: All (9) / Critical (3) / Warning (3) / Info (3)
- Alert cards with **colored severity stripes** (red/orange/yellow)
- Zone badge chips on each card
- Time-ago labels (12m ago, 28m ago, etc.)
- **Tap any alert** → opens Alert Detail screen

### Alert Detail
- Severity header with colored background
- **Bilingual descriptions** — English and Spanish sections
- Detail rows: zone, timestamp, severity, violation type
- **Action buttons**: Acknowledge (navigates back with snackbar), Escalate (shows snackbar), Dismiss (navigates back with snackbar)

### Settings Tab
- **Device** section — connection status dot, "View Glasses HUD" link
- **Preferences** — language dropdown, notification toggle, nightly upload toggle
- **Safety** — detection sensitivity slider, alert sound toggle
- **About** — version 0.1.0, privacy policy, licenses

### HUD Simulator (from Dashboard or Settings)
- Canvas rendering of **640×360 glasses HUD display**
- Toggle between **Normal** (green "ALL CLEAR") and **Violation** (red "PPE ALERT") modes
- FPS slider (2–30 FPS)
- BLE connection toggle
- Battery slider
- Bilingual status bar (English + Spanish)
- Simulated detection bounding boxes with confidence scores

---

## Manual Actions Required

### 1. Verify `local.properties` exists
**Already done.** The file at `app-phone/local.properties` contains the GitHub PAT and SDK path. Verify:
```bash
cat app-phone/local.properties
```
Should show:
```
github_user=AlexiosBluffMara
github_token=github_pat_...
sdk.dir=/Users/soumitlahiri/Library/Android/sdk
```

### 2. Gradle Sync in Android Studio
After opening the project, Android Studio should auto-sync. If it doesn't:
- **File → Sync Project with Gradle Files** (or click the elephant icon with blue arrow)
- If you see "SDK location not found", add `sdk.dir` to `local.properties`

### 3. Install Command-Line Tools (optional, for CLI builds)
If you want to build from terminal instead of Android Studio:
```bash
cd /Users/soumitlahiri/Duchess/app-phone
export ANDROID_HOME=~/Library/Android/sdk
./gradlew assembleDebug
```
The APK will be at: `app/build/outputs/apk/debug/app-debug.apk`

### 4. Install on Physical Pixel Fold 9
1. Enable **Developer Options**: Settings → About Phone → tap "Build number" 7 times
2. Enable **USB Debugging**: Settings → Developer Options → USB Debugging → ON
3. Connect phone via USB-C cable
4. Accept the "Allow USB debugging?" prompt on the phone
5. In Android Studio, select your Pixel Fold from the device dropdown
6. Click Run ▶️

Or via CLI:
```bash
adb install app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.duchess.companion/.MainActivity
```

### 5. Switch Between Demo Mode and Real Glasses Mode
The app runs in `DEMO_MODE = true` by default. To test with real Meta glasses:

1. Open `app-phone/app/src/main/kotlin/com/duchess/companion/MainActivity.kt`
2. Change `const val DEMO_MODE = true` to `const val DEMO_MODE = false` on line ~79
3. Rebuild and run
4. The app will show the registration prompt instead of the demo dashboard
5. You'll need actual Meta Ray-Ban glasses with Developer Mode enabled

### 6. Change Language to Spanish
- On emulator: Settings → System → Languages → Add "Español" → drag it to the top
- The app will show all strings in Spanish automatically
- Or keep English and toggle the language dropdown in Settings (in-app, demo only)

---

## Troubleshooting

| Problem | Fix |
|---------|-----|
| Gradle sync fails with "Could not resolve" | Check `local.properties` has valid `github_token` and `sdk.dir` |
| "No JDK found" | Android Studio → Settings → Build → Gradle → set JDK to Temurin 17 |
| Emulator won't start | Device Manager → select device → Cold Boot Now |
| App crashes on launch | Run with Logcat open: filter by `com.duchess.companion` |
| APK too large (128MB) | This is debug APK with TFLite/MediaPipe libs — release build with ProGuard will be ~40MB |
| "Hilt component not available" | Clean build: `./gradlew clean assembleDebug` |

---

## Project Structure

```
app-phone/
├── app/src/main/
│   ├── kotlin/com/duchess/companion/
│   │   ├── MainActivity.kt          # Entry point + navigation scaffold
│   │   ├── DuchessApplication.kt    # Hilt + DAT SDK init
│   │   ├── splash/SplashScreen.kt   # Branded launch screen
│   │   ├── dashboard/               # Safety score, zones, stats
│   │   ├── stream/                  # Camera feed with detection overlay
│   │   ├── alerts/                  # Alert list + detail + actions
│   │   ├── settings/                # 4-section settings page
│   │   ├── hud/                     # Glasses HUD simulator
│   │   ├── demo/DemoDataProvider.kt # Mock data for demo mode
│   │   ├── navigation/              # Screen routes, bottom nav
│   │   ├── ble/                     # BLE GATT server
│   │   ├── gemma/                   # Gemma 4 inference service
│   │   ├── mesh/                    # Tailscale mesh manager
│   │   ├── upload/                  # Nightly batch upload
│   │   ├── model/SafetyAlert.kt    # Core alert data class
│   │   └── ui/theme/               # Material 3 construction theme
│   └── res/
│       ├── values/strings.xml       # English strings
│       ├── values-es/strings.xml    # Spanish strings
│       ├── values/themes.xml        # Base theme
│       ├── drawable/                # Launcher icon assets
│       └── mipmap-anydpi-v26/       # Adaptive icon
├── gradle/libs.versions.toml        # Version catalog
├── build.gradle.kts                 # Root build config
├── app/build.gradle.kts             # App dependencies
├── settings.gradle.kts              # Maven repos + module config
├── gradlew                          # Gradle wrapper
└── local.properties                 # PAT + SDK path (gitignored)
```
