# Session Handoff — Duchess

_Read this first at the start of every session. Updated automatically by post-commit hook._

---

## 2026-04-02 16:49 UTC — Commit fc1582b

**Branch**: `main`  
**Author**: AlexiosBluffMara  
**Message**: feat(phone): splash screen, alert actions, zone navigation, build polish

### Modules touched
- **app-phone/**: 9 file(s) changed

### Project snapshot
| Module | Files | Status |
|--------|-------|--------|
| app-phone/ | 1737 | Active (1737 files) |
| app-glasses/ | 29 | Active (29 files) |
| ml/ | 22 | Active (22 files) |
| cloud/ | 18 | Active (18 files) |

### Changed files
```
app-phone/app/src/main/kotlin/com/duchess/companion/MainActivity.kt
app-phone/app/src/main/kotlin/com/duchess/companion/alerts/AlertDetailScreen.kt
app-phone/app/src/main/kotlin/com/duchess/companion/ble/BleGattServer.kt
app-phone/app/src/main/kotlin/com/duchess/companion/dashboard/DashboardScreen.kt
app-phone/app/src/main/kotlin/com/duchess/companion/gemma/GemmaInferenceService.kt
app-phone/app/src/main/kotlin/com/duchess/companion/navigation/DuchessNavigation.kt
app-phone/app/src/main/kotlin/com/duchess/companion/splash/SplashScreen.kt
app-phone/app/src/main/res/values-es/strings.xml
app-phone/app/src/main/res/values/strings.xml
```

---

## 2026-04-02 16:35 UTC — Commit 3122ead

**Branch**: `main`  
**Author**: AlexiosBluffMara  
**Message**: feat(phone): demo UI — dashboard, alerts, settings, stream overlay, HUD simulator

### Modules touched
- **app-phone/**: 40 file(s) changed

### Project snapshot
| Module | Files | Status |
|--------|-------|--------|
| app-phone/ | 1630 | Active (1630 files) |
| app-glasses/ | 29 | Active (29 files) |
| ml/ | 22 | Active (22 files) |
| cloud/ | 18 | Active (18 files) |

### Changed files
```
app-phone/app/build.gradle.kts
app-phone/app/src/main/kotlin/com/duchess/companion/MainActivity.kt
app-phone/app/src/main/kotlin/com/duchess/companion/alerts/AlertDetailScreen.kt
app-phone/app/src/main/kotlin/com/duchess/companion/alerts/AlertListScreen.kt
app-phone/app/src/main/kotlin/com/duchess/companion/alerts/AlertsViewModel.kt
app-phone/app/src/main/kotlin/com/duchess/companion/dashboard/DashboardScreen.kt
app-phone/app/src/main/kotlin/com/duchess/companion/dashboard/DashboardViewModel.kt
app-phone/app/src/main/kotlin/com/duchess/companion/demo/DemoDataProvider.kt
app-phone/app/src/main/kotlin/com/duchess/companion/gemma/GemmaInferenceService.kt
app-phone/app/src/main/kotlin/com/duchess/companion/hud/HudSimulatorScreen.kt
app-phone/app/src/main/kotlin/com/duchess/companion/navigation/DuchessNavigation.kt
app-phone/app/src/main/kotlin/com/duchess/companion/settings/SettingsScreen.kt
app-phone/app/src/main/kotlin/com/duchess/companion/settings/SettingsViewModel.kt
app-phone/app/src/main/kotlin/com/duchess/companion/stream/StreamScreen.kt
app-phone/app/src/main/kotlin/com/meta/wearable/dat/camera/StreamSession.kt
app-phone/app/src/main/kotlin/com/meta/wearable/dat/camera/types/PhotoData.kt
app-phone/app/src/main/kotlin/com/meta/wearable/dat/camera/types/StreamConfiguration.kt
app-phone/app/src/main/kotlin/com/meta/wearable/dat/camera/types/StreamSessionState.kt
app-phone/app/src/main/kotlin/com/meta/wearable/dat/camera/types/VideoFrame.kt
app-phone/app/src/main/kotlin/com/meta/wearable/dat/camera/types/VideoQuality.kt
app-phone/app/src/main/kotlin/com/meta/wearable/dat/core/DatResult.kt
app-phone/app/src/main/kotlin/com/meta/wearable/dat/core/Wearables.kt
app-phone/app/src/main/kotlin/com/meta/wearable/dat/core/permissions/Permission.kt
app-phone/app/src/main/kotlin/com/meta/wearable/dat/core/permissions/PermissionStatus.kt
app-phone/app/src/main/kotlin/com/meta/wearable/dat/core/registration/RegistrationState.kt
app-phone/app/src/main/kotlin/com/meta/wearable/dat/core/selectors/AutoDeviceSelector.kt
app-phone/app/src/main/kotlin/com/meta/wearable/dat/mockdevice/MockDeviceKit.kt
app-phone/app/src/main/kotlin/com/meta/wearable/dat/mockdevice/api/MockRaybanMeta.kt
app-phone/app/src/main/res/drawable/ic_launcher_background.xml
app-phone/app/src/main/res/drawable/ic_launcher_foreground.xml
```

---

## 2026-04-02 15:36 UTC — Commit 99d809b

**Branch**: `main`  
**Author**: AlexiosBluffMara  
**Message**: feat(phone): MediaPipe Gemma model loading + nightly batch upload pipeline

### Modules touched
- **app-phone/**: 10 file(s) changed

### Project snapshot
| Module | Files | Status |
|--------|-------|--------|
| app-phone/ | 34 | Active (34 files) |
| app-glasses/ | 29 | Active (29 files) |
| ml/ | 22 | Active (22 files) |
| cloud/ | 18 | Active (18 files) |

### Changed files
```
app-phone/app/build.gradle.kts
app-phone/app/src/main/kotlin/com/duchess/companion/gemma/GemmaInferenceService.kt
app-phone/app/src/main/kotlin/com/duchess/companion/upload/BatchUploadScheduler.kt
app-phone/app/src/main/kotlin/com/duchess/companion/upload/NightlyBatchWorker.kt
app-phone/app/src/main/kotlin/com/duchess/companion/upload/UploadMetadata.kt
app-phone/app/src/main/res/values-es/strings.xml
app-phone/app/src/main/res/values/strings.xml
app-phone/app/src/test/kotlin/com/duchess/companion/gemma/GemmaInferenceServiceTest.kt
app-phone/app/src/test/kotlin/com/duchess/companion/upload/NightlyBatchWorkerTest.kt
app-phone/gradle/libs.versions.toml
```

---

## 2026-04-02 15:25 UTC — Commit 9c093db

**Branch**: `main`  
**Author**: AlexiosBluffMara  
**Message**: feat(shared): Phase 2 production hardening — temporal voting, BLE serialization, mesh wiring, CI/CD

### Modules touched
- **app-phone/**: 5 file(s) changed
- **app-glasses/**: 3 file(s) changed
- **infra**: 1 file(s) changed

### Project snapshot
| Module | Files | Status |
|--------|-------|--------|
| app-phone/ | 30 | Active (30 files) |
| app-glasses/ | 29 | Active (29 files) |
| ml/ | 22 | Active (22 files) |
| cloud/ | 18 | Active (18 files) |

### Changed files
```
.github/workflows/ci.yml
app-glasses/app/src/main/kotlin/com/duchess/glasses/ble/AlertDeserializer.kt
app-glasses/app/src/main/kotlin/com/duchess/glasses/ppe/TemporalVoter.kt
app-glasses/app/src/test/kotlin/com/duchess/glasses/ppe/TemporalVoterTest.kt
app-phone/app/src/main/kotlin/com/duchess/companion/ble/AlertSerializer.kt
app-phone/app/src/main/kotlin/com/duchess/companion/mesh/MeshManager.kt
app-phone/app/src/test/kotlin/com/duchess/companion/ble/AlertSerializerTest.kt
app-phone/app/src/test/kotlin/com/duchess/companion/gemma/GemmaInferenceServiceTest.kt
app-phone/app/src/test/kotlin/com/duchess/companion/mesh/MeshManagerTest.kt
```

---

