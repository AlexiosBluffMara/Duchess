# Session Handoff — Duchess

_Read this first at the start of every session. Updated automatically by post-commit hook._

---

## 2026-04-02 20:09 UTC — Commit 100d1ea

**Branch**: `main`  
**Author**: AlexiosBluffMara  
**Message**: chore: update subproject commit reference in adoring-jackson worktree

### Modules touched
- **infra**: 1 file(s) changed

### Project snapshot
| Module | Files | Status |
|--------|-------|--------|
| app-phone/ | 1744 | Active (1744 files) |
| app-glasses/ | 29 | Active (29 files) |
| ml/ | 17 | Active (17 files) |
| cloud/ | 18 | Active (18 files) |

### Changed files
```
.claude/worktrees/adoring-jackson
```

---

## 2026-04-02 19:55 UTC — Commit 3c2ef09

**Branch**: `claude/adoring-jackson`  
**Author**: AlexiosBluffMara  
**Message**: ci(infra): rename secret GITHUB_PACKAGES_TOKEN → GH_PACKAGES_TOKEN

### Modules touched
- **infra**: 1 file(s) changed

### Project snapshot
| Module | Files | Status |
|--------|-------|--------|
| app-phone/ | 75 | Active (75 files) |
| app-glasses/ | 29 | Active (29 files) |
| ml/ | 17 | Active (17 files) |
| cloud/ | 12 | Active (12 files) |

### Changed files
```
.github/workflows/ci.yml
```

---

## 2026-04-02 19:54 UTC — Commit b69b637

**Branch**: `claude/adoring-jackson`  
**Author**: AlexiosBluffMara  
**Message**: feat(shared): inference pipeline, hardware deploy scripts, secrets management

### Modules touched
- **app-phone/**: 16 file(s) changed
- **app-glasses/**: 2 file(s) changed
- **infra**: 5 file(s) changed

### Project snapshot
| Module | Files | Status |
|--------|-------|--------|
| app-phone/ | 75 | Active (75 files) |
| app-glasses/ | 29 | Active (29 files) |
| ml/ | 17 | Active (17 files) |
| cloud/ | 12 | Active (12 files) |

### Changed files
```
.github/workflows/ci.yml
.memory/handoff.md
QUICKSTART.md
app-glasses/app/src/main/kotlin/com/duchess/glasses/ppe/PpeDetector.kt
app-glasses/settings.gradle.kts
app-phone/app/src/main/kotlin/com/duchess/companion/MainActivity.kt
app-phone/app/src/main/kotlin/com/duchess/companion/alerts/AlertsViewModel.kt
app-phone/app/src/main/kotlin/com/duchess/companion/gemma/GemmaInferenceEngine.kt
app-phone/app/src/main/kotlin/com/duchess/companion/gemma/GemmaInferenceService.kt
app-phone/app/src/main/kotlin/com/duchess/companion/gemma/GemmaModelManager.kt
app-phone/app/src/main/kotlin/com/duchess/companion/gemma/ModelSetupScreen.kt
app-phone/app/src/main/kotlin/com/duchess/companion/navigation/DuchessNavigation.kt
app-phone/app/src/main/kotlin/com/duchess/companion/stream/InferencePipelineCoordinator.kt
app-phone/app/src/main/kotlin/com/duchess/companion/stream/StreamViewModel.kt
app-phone/app/src/main/res/values-es/strings.xml
app-phone/app/src/main/res/values/strings.xml
app-phone/app/src/test/kotlin/com/duchess/companion/gemma/GemmaInferenceEngineTest.kt
app-phone/app/src/test/kotlin/com/duchess/companion/gemma/GemmaInferenceServiceTest.kt
app-phone/app/src/test/kotlin/com/duchess/companion/stream/InferencePipelineCoordinatorTest.kt
app-phone/local.properties.example
app-phone/settings.gradle.kts
scripts/build-glasses.sh
scripts/build-phone.sh
scripts/download-ppe-model.py
scripts/install.sh
```

---

## 2026-04-02 — Real-time Inference Pipeline (Claude Code Session)

**Branch**: `claude/adoring-jackson`

### What Was Built

**Feature: End-to-end real-time inference pipeline** connecting glass camera frames → Gemma 4 E2B vision → bilingual SafetyAlerts → BLE HUD push.

#### Root cause fixed
`GemmaInferenceService.buildSafetyPrompt()` was sending only frame dimensions (text) to Gemma. The actual bitmap was NEVER passed to the vision model. Fixed by using MediaPipe's session API with `session.addImage(MPImage)`.

#### New files
- `gemma/GemmaInferenceEngine.kt` — Injectable `@Singleton` with real inference logic:
  - `setMaxImages(1)` in `LlmInferenceOptions` to enable vision input
  - `session.addImage(BitmapImageBuilder(bitmap).build())` → actual multimodal
  - `extractJson()` strips markdown fences from model output (Gemma wraps JSON in ``` sometimes)
  - `coerceIn()` clamps severity/confidence to valid ranges
  - `internal` methods for testability
- `stream/InferencePipelineCoordinator.kt` — `@Singleton` bridge:
  - 1 FPS throttle with double-checked locking under `Mutex`
  - Confidence filter: drops results < 0.5
  - Emits `SafetyAlert` via `SharedFlow<SafetyAlert>(replay=0, extraBufferCapacity=32)`
  - BLE push for severity >= 3 (serious+) via `BleGattServer.sendAlert()`
  - `emitManualAlert()` for worker-triggered hazard reports

#### Modified files
- `GemmaInferenceService.kt` — Slimmed to foreground service lifecycle wrapper; delegates all inference to `GemmaInferenceEngine` via `@Inject`
- `AlertsViewModel.kt` — Added `addAlert()` + `observeInferenceAlerts()` subscribing to `coordinator.alertFlow`; caps list at 500 alerts
- `StreamViewModel.kt` — Added `InferencePipelineCoordinator` injection, `inferenceEnabled: StateFlow<Boolean>`, `currentZoneId: StateFlow<String>`, and frame routing in `collectFrames()` via non-blocking `viewModelScope.launch {}`

#### New tests
- `GemmaInferenceEngineTest.kt` — 25 tests: constants, parseGemmaOutput (7 cases including severity/confidence clamping), extractJson (6 cases including markdown fences), SAFETY_PROMPT content, PII guardrail
- `InferencePipelineCoordinatorTest.kt` — 20 tests: throttling, confidence filter, alert emission, BLE routing, manual alerts, `toSafetyAlert()` extension function
- `GemmaInferenceServiceTest.kt` — Trimmed to test GemmaState and constants only (logic moved to engine)

### Architecture Notes
- `GemmaInferenceEngine` solves the "can't @Inject a Service" problem — other singletons inject the engine directly
- `GemmaInferenceService` is still needed for `START_STICKY` + foreground notification
- Inference pipeline is opt-in: `StreamViewModel.setInferenceEnabled(true)` must be called from UI
- Zone selection: `StreamViewModel.setCurrentZone("zone-A-framing")` labels all subsequent alerts

### What's Next
- Wire `StreamScreen.kt` to show the new `inferenceEnabled` toggle and zone picker UI
- Connect `GemmaInferenceService` lifecycle to `MainActivity` (start/stop with app)
- Test on real Pixel 9 Fold with Gemma 4 E2B model file in assets

---

