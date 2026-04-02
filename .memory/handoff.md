# Session Handoff — Duchess

_Read this first at the start of every session. Updated automatically by post-commit hook._

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

## 2026-04-02 14:43 UTC — Commit 872feab

**Branch**: `main`  
**Author**: AlexiosBluffMara  
**Message**: fix(shared): BLE UUID mismatch, ML test mocking, QA audit

### Modules touched
- **app-glasses/**: 2 file(s) changed
- **ml/**: 2 file(s) changed

### Project snapshot
| Module | Files | Status |
|--------|-------|--------|
| app-phone/ | 28 | Active (28 files) |
| app-glasses/ | 26 | Active (26 files) |
| ml/ | 22 | Active (22 files) |
| cloud/ | 18 | Active (18 files) |

### Changed files
```
.memory/handoff.md
.memory/project-state.md
app-glasses/app/src/main/kotlin/com/duchess/glasses/ble/BleGattClient.kt
app-glasses/app/src/test/kotlin/com/duchess/glasses/ble/BleGattClientTest.kt
ml/pyproject.toml
ml/tests/conftest.py
```

---

## 2026-04-02 — Copilot (Duke) Production Hardening Session

**What happened**: Claude Code hit API limits repeatedly during Phase 2 production hardening. Multiple agent runs (Maya, Luis, Alex, Kai, Priya, Jordan, Carlos, Sam, Noah, Elena) were launched but most stalled or lost their changes when context was compacted. Only the Phase 1 scaffold commits and the Copilot wiring commit (6261100) actually persisted.

### What Copilot (Duke) actually did this session:

**1. BLE UUID Critical Fix** (BleGattClient.kt)
- Glasses had INVALID UUIDs: `0000DCSS-0000-1000-8000-00805F9B34FB` ("DCSS" is not hex)
- Phone had CORRECT UUIDs: `d0c5e550-0001-4b6e-a5a0-b0b0b0b0b0b0`
- Fixed glasses to match phone. Added cross-module UUID verification test.
- Without this fix, BLE connection between glasses and phone would NEVER work.

**2. ML Test Mocking** (ml/tests/conftest.py, ml/pyproject.toml)
- All 79 ML tests now pass without torch/transformers/datasets installed
- Priya agent added conditional sys.modules mocking for 11 heavy packages
- Tests run in 0.04s on dev laptop

**3. Full QA Audit** (Sam agent)
- 229 total tests across all modules
- Cloud: 41/41 passing
- ML: 79/79 passing
- Key gaps: MeshManager.broadcastAlert() is a TODO stub, no temporal voting tests, no cross-module BLE integration test, no Gemma lifecycle tests

### What's still TODO (Phase 2):
- [ ] Wire MeshManager.broadcastAlert() with actual Tailscale integration
- [ ] Add temporal voting to PpeDetector (3/5 frames must agree before escalation)
- [ ] Add PII structural test to GemmaAnalysisResult
- [ ] BLE payload serialization contract test (phone ↔ glasses)
- [ ] GemmaInferenceService model loading (MediaPipe LLM Inference API)
- [ ] Offline-first queue-and-retry for alert delivery
- [ ] Nightly batch upload pipeline implementation
- [ ] GitHub Actions CI/CD (Taylor agent)

---

