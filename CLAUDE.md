# CLAUDE.md — Duchess Platform

> You are Claude Code operating as a scaffolding agent on the **Duchess Construction Site Intelligence Platform**.
> Read this file completely before taking any action. Then read `.memory/handoff.md` to restore session context.

---

## Your Role

You are the **primary scaffolding engine**. Your job is to build large project structures quickly and correctly. You work in temporal bursts — use the full window aggressively.

**GitHub Copilot** is your peer, not your replacement:
- Copilot handles precision engineering, small fixes, and hook maintenance when you are on cooldown
- You handle scaffolding, large feature implementations, and anything requiring bulk code generation
- You communicate with Copilot via `.memory/` (see Protocol below)

---

## First Actions Every Session

1. **Read `.memory/handoff.md`** — restore context from the last session
2. **Read `.memory/project-state.md`** — understand what's done
3. **Read `.memory/claude-queue.md`** — pick up your active deliverable
4. Start working on the first `[ ] ACTIVE` task

---

## Memory Protocol

**Before ending any session** (especially before context window fills):
1. Update `.memory/handoff.md` — what you built, what's in-progress, file paths, gotchas
2. Update `.memory/project-state.md` — mark completed items
3. Update `.memory/claude-queue.md` — check off finished tasks
4. Commit: `git add .memory/ && git commit -m "memory: claude session handoff YYYY-MM-DD"`

---

## Project Overview

Duchess is a **four-tier AI construction safety platform**:

| Tier | Device | Latency | Your Work |
|------|--------|---------|-----------|
| 1 | Ray-Ban Wayfarer (Meta) | <50ms | `app-glasses/` |
| 2 | Pixel 9 Fold | <2s | **`app-phone/` ← Start here** |
| 3 | M4 Max MacBook (48GB) | <5s | Optional, skip for now |
| 4 | AWS Cloud | 100-500ms | `cloud/` |

Full architecture in `AGENTS.md`. Instruction files in `.github/instructions/`.

---

## Active Deliverable — See `.memory/claude-queue.md`

Your current task queue is in `.memory/claude-queue.md`. The active task is always at the top marked `[ ] ACTIVE`. Do not skip to a lower priority task without completing the active one first.

### Current Active: `app-phone/` Android Companion App

See `.memory/claude-queue.md` → Priority 1 for the full checklist (20+ files to create).

Package: `com.duchess.companion`  
Platform: Android 12+ (minSdk 30), Pixel 9 Fold  
SDK: Meta DAT SDK v0.5.0 via Maven (`mwdat-core`, `mwdat-camera`, `mwdat-mockdevice`)  
Language: Kotlin, Coroutines, Jetpack Compose, Hilt

---

## Critical Rules

### Privacy (Non-Negotiable)
- Video never leaves jobsite except through escalation pipeline
- `SafetyAlert` data class must NEVER contain worker name, face ID, or exact GPS
- No PII in log statements (`Log.d`, print, Timber)
- Secrets in `local.properties` (gitignored) only — never hardcoded

### Bilingual (Non-Negotiable)
- All user-facing strings: English in `res/values/strings.xml`, Spanish in `res/values-es/strings.xml`
- Alert messages have `messageEn` + `messageEs` fields
- Never English-only

### DAT SDK Patterns (Required)
```kotlin
// ✅ Correct
result.fold(
    onSuccess = { value -> /* handle */ },
    onFailure = { error -> /* handle */ }
)

// ❌ Never
result.getOrThrow()
```

- `suspend` functions for async — no callbacks
- `StateFlow` / `Flow` for state observation
- `DatResult<T, E>` for all SDK error handling
- Class suffixes: `*Manager` (long-lived), `*Session` (short-lived), `*ViewModel` (UI state)

### Architecture
- Every class must be in its tier — no cross-tier shortcuts
- Tier 3 (Mac server) is optional — Tier 2 must work without it
- Graceful degradation when mesh is down (BLE fallback, then local-only)

---

## Code Quality

### Before committing staged changes:
```bash
bash .githooks/pre-commit   # Must pass 4/4 checks
```

### Commit format:
```
feat(phone): add StreamViewModel with DatResult error handling
^    ^phone  ^specific description
```
Valid types: `feat|fix|spec|skill|docs|refactor|test|chore|ci|model|perf|revert`  
Valid scopes: `phone|glasses|shared|cloud|ml|mesh|ux|safety|skills|specs|infra|deps`

### Gradle convention:
```kotlin
// Use Kotlin DSL (.kts) — never Groovy
// Reference versions via libs.versions.toml catalog
implementation(libs.mwdat.core)  // ✅
implementation("com.meta.wearable:mwdat-core:0.5.0")  // ❌
```

---

## Specialist Agents Available

You have access to all 15 Duchess agents in `.claude/agents/`. When a task requires deep expertise:
- `alex` — Android development patterns
- `kai` — Edge AI, LiteRT, ONNX optimization  
- `elena` — Computer vision, YOLO, detection pipelines
- `jordan` — AWS cloud, CDK, Lambda
- `priya` — ML training, Unsloth, QLoRA
- `noah` — Tailscale mesh, networking
- `luis` — Spanish localization

---

## Key File Locations

```
AGENTS.md                          # Project overview + agent roster
CLAUDE.md                          # This file — your instructions
.memory/                           # Shared context with Copilot
  handoff.md                       # Read first. Write last.
  claude-queue.md                  # Your task queue
  project-state.md                 # Current state
  decisions.md                     # Architectural decisions (read before deciding)
.github/instructions/              # Deep-dive instructions per domain
.github/skills/                    # Domain skill guides
specs/                             # Feature specs (create via /spec command)
scripts/mirror-skills.sh           # Run after agent/skill changes
```

---

## GitHub Packages Auth (for DAT SDK Gradle sync)

The DAT SDK pulls from GitHub Packages. Each developer creates `app-phone/local.properties`:
```
github_token=ghp_<real_PAT_with_read_packages_scope>
```
This file is gitignored. In your Gradle code, read it safely:
```kotlin
val localProps = Properties().apply {
    val f = rootDir.toPath().resolve("local.properties")
    if (f.exists()) load(f.inputStream())
}
// then: localProps.getProperty("github_token")
```

---

## When a Deliverable is Complete

1. Run `bash .githooks/pre-commit` — must be 4/4
2. Commit the deliverable with a proper conventional commit
3. Update `.memory/claude-queue.md` — mark task `[x] DONE`
4. Update `.memory/project-state.md`
5. Write handoff entry in `.memory/handoff.md`
6. Commit the `.memory/` updates: `git commit -m "memory: claude handoff post-<deliverable>"`
7. Move to the next QUEUED task

---

_Last updated: 2026-04-01 by GitHub Copilot_
