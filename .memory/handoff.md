# Session Handoff — Duchess

_Read this first at the start of every session. Updated automatically by post-commit hook._

---

## 2026-04-03 02:36 UTC — Commit d1c14cf

**Branch**: `main`  
**Author**: AlexiosBluffMara  
**Message**: feat(phone): wire real Meta DAT SDK mwdat 0.5.0, remove 14 stubs

### Modules touched
- **app-phone/**: 20 file(s) changed

### Project snapshot
| Module | Files | Status |
|--------|-------|--------|
| app-phone/ | 2467 | Active (2467 files) |
| app-glasses/ | 33 | Active (33 files) |
| ml/ | 17 | Active (17 files) |
| cloud/ | 18 | Active (18 files) |

### Changed files
```
app-phone/app/build.gradle.kts
app-phone/app/src/main/kotlin/com/duchess/companion/MainActivity.kt
app-phone/app/src/main/kotlin/com/duchess/companion/gemma/GemmaInferenceEngine.kt
app-phone/app/src/main/kotlin/com/duchess/companion/stream/StreamScreen.kt
app-phone/app/src/main/kotlin/com/duchess/companion/stream/StreamViewModel.kt
app-phone/app/src/main/kotlin/com/duchess/companion/stream/VideoFrameExt.kt
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
```

---

## 2026-04-02 22:40 UTC — Commit c1f1739

**Branch**: `main`  
**Author**: AlexiosBluffMara  
**Message**: ci(infra): opt into Node.js 24 and add fetch-depth 0 to Pages workflow

### Modules touched
- **infra**: 1 file(s) changed

### Project snapshot
| Module | Files | Status |
|--------|-------|--------|
| app-phone/ | 1746 | Active (1746 files) |
| app-glasses/ | 33 | Active (33 files) |
| ml/ | 17 | Active (17 files) |
| cloud/ | 18 | Active (18 files) |

### Changed files
```
.github/workflows/pages.yml
```

---

## 2026-04-02 22:37 UTC — Commit d1dde36

**Branch**: `main`  
**Author**: AlexiosBluffMara  
**Message**: ci(infra): pass GITHUB_TOKEN to Jekyll build for github-metadata plugin

### Modules touched
- **infra**: 1 file(s) changed

### Project snapshot
| Module | Files | Status |
|--------|-------|--------|
| app-phone/ | 1746 | Active (1746 files) |
| app-glasses/ | 33 | Active (33 files) |
| ml/ | 17 | Active (17 files) |
| cloud/ | 18 | Active (18 files) |

### Changed files
```
.github/workflows/pages.yml
```

---

## 2026-04-02 21:55 UTC — Commit a5b04ca

**Branch**: `main`  
**Author**: AlexiosBluffMara  
**Message**: fix(glasses): correct LiteRT import packages and remove duplicate init code in PpeDetector

### Modules touched
- **app-glasses/**: 1 file(s) changed

### Project snapshot
| Module | Files | Status |
|--------|-------|--------|
| app-phone/ | 1746 | Active (1746 files) |
| app-glasses/ | 33 | Active (33 files) |
| ml/ | 17 | Active (17 files) |
| cloud/ | 18 | Active (18 files) |

### Changed files
```
app-glasses/app/src/main/kotlin/com/duchess/glasses/ppe/PpeDetector.kt
```

---

