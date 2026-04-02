# Session Handoff — Duchess

_Read this first at the start of every session. Update before session ends (before compaction)._

---

## 2026-04-01 — Claude Code (Session 1)

### What was accomplished this session

**`app-phone/` Android companion app scaffold — COMPLETE** (23 files):

**Gradle project (5 files):**
- `settings.gradle.kts` — GitHub Packages Maven repo for DAT SDK, reads `github_token` from `local.properties`
- `build.gradle.kts` — root build file with plugin aliases
- `gradle/libs.versions.toml` — version catalog: mwdat 0.5.0, Compose BOM 2025.03.00, Hilt 2.55, Kotlin 2.1.10
- `local.properties.example` — placeholder for GitHub PAT
- `gradle.properties` — JVM args, AndroidX config

**App module (2 files):**
- `app/build.gradle.kts` — minSdk 30, compileSdk 35, Compose enabled, Hilt, DAT SDK deps, TFLite
- `app/src/main/AndroidManifest.xml` — BLE/INTERNET/FOREGROUND_SERVICE permissions, APPLICATION_ID meta-data, duchess:// scheme

**Application entry (2 files):**
- `DuchessApplication.kt` — @HiltAndroidApp, calls `Wearables.initialize(this)`
- `MainActivity.kt` — observes `Wearables.registrationState`, permission request flow, routes to StreamScreen when registered

**Stream layer (2 files):**
- `stream/StreamViewModel.kt` — StateFlow<StreamSessionState>, starts StreamSession via AutoDeviceSelector, DatResult.fold() for capturePhoto
- `stream/StreamScreen.kt` — Compose screen with frame display, capture button, session indicator, error/retry states

**BLE server (1 file):**
- `ble/BleGattServer.kt` — GATT server with Duchess service UUID, alert + status characteristics, lifecycle-aware start/stop

**Gemma 3n (1 file):**
- `gemma/GemmaInferenceService.kt` — foreground Service, StateFlow<GemmaState>, lazy model loading, stub `analyze(frame)` returning bilingual JSON

**Mesh (1 file):**
- `mesh/MeshManager.kt` — singleton, MeshState flow, `broadcastAlert(SafetyAlert)`, graceful degradation stubs

**Model (1 file):**
- `model/SafetyAlert.kt` — data class with NO worker identity fields (privacy rule)

**UI (4 files):**
- `ui/theme/Theme.kt` — Material 3, construction-orange #E65100 primary, light/dark/dynamic
- `ui/theme/Type.kt` — typography
- `res/values/strings.xml` — English strings (stream, alerts, BLE, Gemma, mesh)
- `res/values-es/strings.xml` — Spanish strings (same keys)

**DI (1 file):**
- `di/AppModule.kt` — Hilt module providing NotificationManager

**Tests (2 files):**
- `test/stream/StreamViewModelTest.kt` — 6 tests using Turbine for StateFlow testing
- `androidTest/MockDeviceKitTestBase.kt` — reusable base with MockDeviceKit.create(), pair(), grantAllPermissions(), reset()

**Spec (1 file):**
- `specs/stream.spec.md` — full spec for camera stream feature

**Pre-commit: 4/4 checks pass.**

### Currently in-progress / What Claude is assigned

Next up: Priority 2 — `app-glasses/` Vuzix M400 AOSP App Scaffold

### Staged/uncommitted changes

```
A  app-phone/ (23 files — full scaffold)
A  specs/stream.spec.md
```

### Key decisions made this session

- Used Kotlin 2.1.10 + Compose compiler built into KGP (no separate compose-compiler artifact)
- KSP over KAPT for Hilt annotation processing (faster builds)
- compileSdk 35 / targetSdk 35 for latest API level
- Dynamic color supported (Material You) with fallback to construction-orange theme

### Blockers / Gotchas

- `local.properties` still needs real `github_token` for Gradle sync to resolve DAT SDK
- StreamScreen references `latestFrame?.bitmap` — actual VideoFrame API field name needs verification against DAT SDK 0.5.0 docs
- GemmaInferenceService needs actual MediaPipe LLM Inference API integration (currently stub)

### What to pick up next

1. Commit this deliverable
2. Move to Priority 2: `app-glasses/` Vuzix M400 scaffold

---

## 2026-04-01 — GitHub Copilot

### What was accomplished this session

**Infrastructure complete** — The full Duchess project scaffolding is done:
- 15 specialist agents created (duke, alex, carlos, elena, jordan, kai, luis, maya, mei, noah, priya, raj, sam, taylor, wei) — in both `.github/agents/` and `.claude/agents/`
- 22 skills synced (15 Duchess + 7 Meta DAT SDK skills)
- 9 instruction files live in `.github/instructions/`
- Bidirectional mirror: `scripts/mirror-skills.sh` — syncs .github ↔ .claude for skills AND agents, rebuilds `settings.json` via Python json.dump
- Git hooks: `.githooks/pre-commit` (4 checks: mirror, PII, secrets, spec), `commit-msg` (conventional format), `post-merge` (auto-sync)
- Spec template at `specs/_template.spec.md`
- Slash commands: `.claude/commands/build.md`, `spec.md`, `skill.md`
- Claude Code CLI v2.1.90 installed
- gh CLI v2.89.0 installed
- gh Copilot CLI v1.0.15 installed
- `CLAUDE.md` created — Claude Code's operating instructions at repo root
- `.memory/` system created — shared context bridge between Copilot and Claude

**Pre-commit fixed**: Old `github_token\s*=\s*...` broad pattern replaced with exact `ghp_[A-Za-z0-9]{36}` and placeholder filter. Hook passes 4/4.

### Currently in-progress / What Claude is assigned

**Claude's active deliverable: `app-phone/` Android companion app scaffold** (see `.memory/claude-queue.md` for full spec).  
This is the Pixel 9 Fold companion app using Meta DAT SDK v0.5.0.

Claude has NOT started yet — this is the first assignment. When Claude opens this repo, it should read `CLAUDE.md` for full instructions, then immediately begin `app-phone/`.

### Staged/uncommitted changes

```
A  .claude/agents/ (15 files — all agents mirrored)
M  .claude/settings.json (22 skills + 15 agents)
M  scripts/mirror-skills.sh (agent sync added)
A  CLAUDE.md
A  .memory/ (all new files)
```

Push these with: `git commit -m "feat(infra): add Copilot↔Claude memory system + CLAUDE.md + agent mirrors"`

### Key decisions made this session

- Claude Code is temporal (not request-based) — use aggressively for scaffolding during active windows
- Copilot is always-on for precision fixes when Claude cools off
- `.memory/` is git-tracked — both tools share context via file system + git push
- Hardware confirmed: Pixel 9 Fold (primary Android), Ray-Ban Wayfarer (Tier 1), M4 Max (Tier 3), RTX 5090 (training)

### Blockers / Gotchas

- `local.properties` file needs to be created by the developer with `github_token=ghp_<real_PAT>` before any Gradle sync will work for Meta DAT SDK. The PAT needs `read:packages` scope on GitHub.
- VS Code's Source Control panel may show stale pre-commit errors — the actual hook passes 4/4. No action needed.
- The `mirror-skills.sh` agent sync section prints some `zsh: command not found: #` noise to terminal (comment lines being exec'd) — cosmetic only, sync completes correctly.

### What to pick up next

1. Claude builds `app-phone/` (see queue)
2. Copilot reviews output for Kotlin idiomatics / DAT SDK conventions
3. Create `local.properties.example` once DAT SDK setup is confirmed working

---

_Add new entries at the TOP of this section, newest first. Keep total under 200 lines — archive old entries to `handoff-archive.md` when needed._
