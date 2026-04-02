# Claude Code Task Queue

_Read this when you open Duchess. Start from the top. Mark tasks complete in `project-state.md` and update `handoff.md` when done._

> **Instructions**: Pick up the first `[ ] ACTIVE` task. When done: (1) update this file to `[x] DONE`, (2) update `project-state.md`, (3) update `handoff.md` with what changed, (4) commit `.memory/` changes, (5) move to next task.

---

## Priority 1 — `app-phone/` Android Companion App Scaffold

**Status**: `[x] DONE — completed 2026-04-01`  
**Assigned**: Claude Code  
**Reviewed by**: GitHub Copilot (after delivery)

### Goal
Scaffold the full `app-phone/` Android Gradle project for the Pixel 9 Fold companion app.
This is Tier 2 — the phone is the brain. Glasses are sensors/speakers only.

### Deliverable Checklist

**Gradle project structure:**
- [ ] `app-phone/settings.gradle.kts` — GitHub Packages Maven repo (reads `github_token` from `local.properties`), includes app module
- [ ] `app-phone/build.gradle.kts` — root build file
- [ ] `app-phone/libs.versions.toml` — mwdat 0.5.0 (`mwdat-core`, `mwdat-camera`, `mwdat-mockdevice`), Compose BOM, Kotlin, Hilt
- [ ] `app-phone/app/build.gradle.kts` — minSdk 30, Kotlin DSL, Compose enabled, Hilt, all DAT SDK deps, APPLICATION_ID meta-data
- [ ] `app-phone/app/src/main/AndroidManifest.xml` — BLE permissions, INTERNET, APPLICATION_ID = "0" for dev, intent-filter with `duchess://` scheme
- [ ] `app-phone/local.properties.example` — shows `github_token=ghp_your_token_here` placeholder

**Application entry point:**
- [ ] `app-phone/app/src/main/kotlin/com/duchess/companion/DuchessApplication.kt` — `Application` class, calls `Wearables.initialize(this)` at startup
- [ ] `app-phone/app/src/main/kotlin/com/duchess/companion/MainActivity.kt` — handles registration state from `Wearables.registrationState`, permission request flow using `RequestPermissionContract`

**Stream layer (Tier 2 camera feed from glasses):**
- [ ] `.../stream/StreamViewModel.kt` — `ViewModel` with `MutableStateFlow<StreamSessionState?>`, `MutableStateFlow<VideoFrame?>`, starts `StreamSession` via `Wearables.startStreamSession(AutoDeviceSelector(), StreamConfiguration(VideoQuality.MEDIUM, 24))`, uses `onSuccess`/`onFailure` for `capturePhoto()`
- [ ] `.../stream/StreamScreen.kt` — Compose screen displaying frame, a capture button, session state indicator

**BLE server (for glasses ↔ phone):**
- [ ] `.../ble/BleGattServer.kt` — GATT server skeleton, starts/stops with lifecycle, placeholder services/characteristics for alert delivery to glasses

**Gemma 3n inference (Tier 2 NLU):**
- [ ] `.../gemma/GemmaInferenceService.kt` — foreground Service skeleton, `StateFlow<GemmaState>` (IDLE, LOADING, READY, RUNNING, ERROR), placeholder `suspend fun analyze(frame: VideoFrame): String` that returns stub result

**Mesh connectivity (Tailscale):**
- [ ] `.../mesh/MeshManager.kt` — singleton `object`, placeholder `fun isConnected(): Boolean`, `fun broadcastAlert(alert: SafetyAlert)` stub

**Alert model:**
- [ ] `.../model/SafetyAlert.kt` — `data class SafetyAlert(val id: String, val violationType: String, val severity: Int, val zoneId: String, val timestamp: Long, val messageEn: String, val messageEs: String)` — NO worker identity fields (privacy)

**UI theme:**
- [ ] `.../ui/theme/Theme.kt` — Material 3 theme, construction-orange primary color (#E65100)
- [ ] `.../ui/theme/Type.kt` — typography
- [ ] `app-phone/app/src/main/res/values/strings.xml` — bilingual: English strings
- [ ] `app-phone/app/src/main/res/values-es/strings.xml` — Spanish strings (same keys)

**Tests:**
- [ ] `.../test/stream/StreamViewModelTest.kt` — `@Test fun startStream_updatesState()` using MockDeviceKit pattern
- [ ] `.../androidTest/MockDeviceKitTestBase.kt` — reusable test base that pairs `MockRaybanMeta`, grants permissions, calls `reset()` in `@After`

### Code Conventions
- All async: `suspend` functions + coroutines. No callbacks.
- Error handling: `DatResult<T,E>` everywhere. Never `getOrThrow()`.
- State: `StateFlow` for UI state, `Flow` for streams.
- Classes: `*Manager` for long-lived, `*Session` for short-lived, `*ViewModel` for UI state.
- `SafetyAlert` must never contain worker identity (PII rule).

### When Done
1. Verify the project at least syncs (no compile errors, Gradle resolves — developer will add real `github_token` in `local.properties`)
2. Run `git add app-phone/ && bash .githooks/pre-commit`
3. Commit: `feat(phone): scaffold Android companion app with Meta DAT SDK`
4. Update `.memory/claude-queue.md` — mark this task `[x] DONE`
5. Update `.memory/project-state.md`
6. Update `.memory/handoff.md` with what was built
7. Move to Priority 2

---

## Priority 2 — `app-glasses/` Vuzix M400 AOSP App Scaffold

**Status**: `[ ] QUEUED — start after Priority 1 is done`  
**Assigned**: Claude Code

### Goal
Scaffold the `app-glasses/` AOSP Android app for the Vuzix M400 (AOSP Android 13, no Google Play Services).

### Deliverable Checklist
- [ ] `app-glasses/settings.gradle.kts` + `build.gradle.kts`
- [ ] `app-glasses/app/build.gradle.kts` — minSdk 33, NO Google Play Services deps, TFLite deps, no Compose (AOSP UI only)
- [ ] `app-glasses/app/src/main/AndroidManifest.xml` — CAMERA, BLE permissions, no GMS
- [ ] `GlassesApplication.kt` — Application init
- [ ] `MainActivity.kt` — boots the camera + BLE client
- [ ] `.../camera/CameraSession.kt` — Camera2 API `CameraDevice` + `ImageReader`, captures frames at 15 FPS, 640×480 (Vuzix resolution), emits `ImageProxy` via `Flow`
- [ ] `.../ppe/PpeDetector.kt` — TFLite `Interpreter` wrapper, loads `yolov8_nano_ppe.tflite` from assets (placeholder model file), `suspend fun detect(bitmap: Bitmap): List<Detection>` 
- [ ] `.../model/Detection.kt` — `data class Detection(val label: String, val confidence: Float, val bbox: RectF)`
- [ ] `.../ble/BleGattClient.kt` — BLE client connecting to phone's GATT server, receives alert payloads, parses to `SafetyAlert`
- [ ] `.../display/HudRenderer.kt` — Canvas-based overlay renderer on 640×360 Vuzix display, draws bounding boxes + bilingual alert text
- [ ] `assets/yolov8_nano_ppe.tflite` — empty placeholder file with comment (real model loaded at runtime)

### Code Conventions
- No coroutines for Camera2 (Camera2 uses executor/callback pattern) — wrap in `callbackFlow` or `suspendCancellableCoroutine`
- Battery: hold PARTIAL_WAKE_LOCK during active detection only
- TFLite: INT8 quantized, GPU delegate with NNAPI fallback

---

## Priority 3 — `ml/` Python Training Pipeline

**Status**: `[ ] QUEUED`  
**Assigned**: Claude Code

### Goal
Scaffold the ML training environment for Gemma 3n fine-tuning and YOLOv8-nano PPE adaptation.

### Deliverable Checklist
- [ ] `ml/pyproject.toml` — Poetry project, Python 3.11+, deps: unsloth, torch, transformers, datasets, tqdm, peft, wandb, ultralytics
- [ ] `ml/README.md` — setup instructions, hardware requirements (RTX 5090)
- [ ] `ml/scripts/train_gemma3n.py` — Unsloth `FastLanguageModel.from_pretrained("google/gemma-3n-e2b-it")`, QLoRA config (r=16, alpha=32, target: q_proj, k_proj, v_proj, o_proj), SFTTrainer setup with placeholder dataset
- [ ] `ml/scripts/prepare_dataset.py` — construction safety dataset prep: loads from HuggingFace placeholder, formats for instruction tuning `{"instruction": ..., "input": ..., "output": ...}` with English + Spanish pairs
- [ ] `ml/scripts/export_model.py` — exports adapter merged model to ONNX, then TFLite FP16 for Android deployment
- [ ] `ml/adapters/safety/config.json` — LoRA adapter config for safety domain
- [ ] `ml/adapters/spanish_jargon/config.json` — LoRA adapter config for construction Spanish
- [ ] `ml/eval/benchmark.py` — stub evaluation against iSafetyBench placeholders, outputs accuracy/latency table
- [ ] `ml/.env.example` — `WANDB_API_KEY=...`, `HF_TOKEN=...` (no real values)

---

## Priority 4 — `cloud/` AWS CDK Infrastructure

**Status**: `[ ] QUEUED`  
**Assigned**: Claude Code

### Goal
Scaffold the cloud infrastructure for the PPE escalation pipeline and nightly batch.

### Deliverable Checklist
- [ ] `cloud/pyproject.toml` — Poetry, AWS CDK v2, Python 3.11+
- [ ] `cloud/app.py` — CDK app entry point
- [ ] `cloud/stacks/duchess_stack.py` — main CDK stack:
  - S3 bucket (video storage, KMS encrypted, 90-day lifecycle)
  - DynamoDB table (alerts: id, timestamp, violationType, zoneId, severity, status, anonymizedWorkerId)
  - Lambda function (PPE escalation handler — receives from SQS, triggers Bedrock, writes to DynamoDB)
  - SQS queue (escalation queue, 5-min visibility timeout)
  - SageMaker endpoint placeholder (commented out — cost-conscious)
- [ ] `cloud/lambda/ppe_escalation/handler.py` — Lambda handler, reads from event, calls Bedrock Claude claude-3-5-sonnet, writes result to DynamoDB, NO worker PII in DynamoDB
- [ ] `cloud/README.md` — deploy instructions, `cdk deploy`, required env vars

---

## Completed Tasks

_(Move entries here when done)_

---

_Claude: When you're done with a deliverable, move it here and update handoff.md_
