---
description: "Use when developing Android code for the Vuzix M400 glasses. Covers AOSP constraints, Camera2 API, TFLite integration, BLE client, voice commands, battery optimization, 640x360 display, sideloading."
applyTo: "app-glasses/**"
---

# Vuzix M400 Glasses Development

## Critical Constraints

1. **No Google Play Services** — AOSP only. No Firebase, no Play Services ML Kit, no Google Maps, no FCM push notifications. Everything must use AOSP APIs or bundled libraries.
2. **6GB RAM** — After Android OS, ~3.5GB available. ML models capped at 500MB total.
3. **750mAh battery** — Design for 4-hour active use. Every wake lock, every camera frame, every inference counts.
4. **640x360 OLED** — Maximum 4 words per alert. Large icons. Dark background (saves OLED power).
5. **No touch during use** — Workers wear gloves. Voice commands and head gestures only.
6. **Camera2 API** — Not CameraX (requires Play Services). Handle lifecycle carefully.

## Development Rules

- Use Kotlin with coroutines for all async operations
- Minimum SDK: 33 (Android 13)
- Use foreground services for continuous ML inference (must show persistent notification)
- BLE client mode — glasses connect to phone, phone acts as GATT server
- All strings in `res/values/strings.xml` (EN) and `res/values-es/strings.xml` (ES)
- Test on actual Vuzix M400 hardware — emulator does not match real performance
- Profile memory with Android Studio Profiler before every PR
- Voice commands registered via Vuzix SDK, not Android's built-in speech recognition

## Battery Optimization Patterns

```kotlin
// DO: Battery-aware ML scheduling
val mode = BatteryAwareScheduler.getCurrentMode()
val fps = when (mode) {
    InferenceMode.FULL -> 10
    InferenceMode.REDUCED -> 5
    InferenceMode.MINIMAL -> 2
    InferenceMode.SUSPENDED -> 0
}

// DO: Release camera when not needed
override fun onPause() {
    cameraDevice.close()
    super.onPause()
}

// DON'T: Hold permanent wake locks
// DON'T: Run inference at full FPS when battery < 20%
// DON'T: Keep BLE scanning when already connected
```

## APK Delivery

- Build signed release APK via GitHub Actions
- Deploy via ADB sideload or MDM (no Play Store)
- OTA update mechanism through companion phone app (phone downloads update, pushes to glasses via BLE/WiFi Direct)
