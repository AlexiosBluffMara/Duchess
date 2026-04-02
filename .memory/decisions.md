# Architectural Decisions — Duchess

_Use this to avoid relitigating decided questions. Add entries when a significant technical decision is made._

---

## ADR-001: Four-Tier Architecture is Canonical
**Date**: 2026-03-xx  
**Decision**: All features must declare their tier (1–4). No "it runs somewhere" ambiguity.  
**Rationale**: Latency budgets, privacy boundaries, and battery constraints differ radically per tier.  
**Consequences**: Every spec must have a "Tier Placement" section.

---

## ADR-002: Video Never Leaves Jobsite Except Via PPE Escalation
**Date**: 2026-03-xx  
**Decision**: Raw video is on-device only. Cloud-bound video requires: (a) Gemma 4 triggers PPE escalation, OR (b) nightly batch upload after shift ends.  
**Rationale**: HIPAA compliance, union contracts, worker privacy, data sovereignty.  
**Consequences**: Any cloud upload path must go through the escalation pipeline with human-in-loop.

---

## ADR-003: Primary Android Dev Device = Pixel 9 Fold
**Date**: 2026-03-xx  
**Decision**: Pixel 9 Fold is the primary companion phone. iPhone 16 Pro Max is Phase 2 supervisor dashboard only.  
**Rationale**: Meta DAT SDK is Android-first. Pixel runs stock Android — best for SDK compatibility. Fold form factor suits supervisor use case well.

---

## ADR-004: Meta DAT SDK via Maven (Not Submodule)
**Date**: 2026-03-xx  
**Decision**: Use Maven Packages from `https://maven.pkg.github.com/facebook/meta-wearables-dat-android`. No git submodule.  
**Rationale**: SDK is distributed as compiled AAR packages only. Source not available.  
**Consequences**: Each developer needs a GitHub PAT with `read:packages` in `local.properties` as `github_token=ghp_...`. This file is gitignored.

---

## ADR-005: Bilingual Support is Non-Negotiable
**Date**: 2026-03-xx  
**Decision**: All user-facing strings in English AND Spanish. Alerts in both languages. No English-only launches.  
**Rationale**: Construction workforce demographics, equity, OSHA requirements on multi-language sites.  
**Consequences**: All alert text, HUD overlays, push notifications must use string resources with `es` locale.

---

## ADR-006: Tier 3 (Mac Server) is Optional
**Date**: 2026-03-xx  
**Decision**: System must function fully with only Tiers 1, 2, and 4. Tier 3 (Qwen2.5-VL on M4 Max) is an enhancement.  
**Rationale**: Not all jobsites will have a local Mac. Graceful degradation is required.  
**Consequences**: Tier 2 (Gemma 4) must be capable of triage decisions independently.

---

## ADR-007: Claude Code for Scaffolding, Copilot for Fine-Tuning
**Date**: 2026-04-01  
**Decision**: Claude Code (temporal usage) handles big scaffolding tasks. GitHub Copilot handles precision engineering, small fixes, hook maintenance when Claude is on cooldown.  
**Rationale**: Claude has time-based usage (not request-based) — slam it with scaffolding work during active windows. Copilot is always-on for targeted work.  
**Consequences**: Claude has a prioritized task queue in `.memory/claude-queue.md`. Both tools share context via `.memory/handoff.md`.

---

## ADR-008: DatResult Over Exceptions
**Date**: 2026-04-01  
**Decision**: All DAT SDK calls use `DatResult<T, E>` — never `getOrThrow()` or try/catch around SDK methods.  
**Rationale**: DAT SDK conventions require typed error handling. Both success and failure paths must be explicit.  
**Consequences**: All SDK-wrapping code uses `.fold()`, `.onSuccess {}`, `.onFailure {}`.

---

## ADR-009: Gemma 4 Runs Entirely On-Device (Tier 2)
**Date**: 2026-04-01 (updated 2026-04-02)  
**Decision**: Gemma 4 E2B (2.3B effective params, 5.1B with embeddings) runs on-device on the companion phone. No cloud API calls for primary NLU/triage.  
**Rationale**: Latency (<2s requirement), privacy (video frames can't leave mesh), offline operation requirement. Gemma 4 E2B adds native vision+audio, function calling, and 128K context over Gemma 3n.  
**Consequences**: Model must be bundled/downloaded to device at app setup. ML framework: MediaPipe LLM Inference API, LiteRT, or llama.cpp Android. Multimodal vision input enables direct PPE detection from camera frames.

---

## ADR-010: Hackathon Priorities Are the Golden Standard
**Date**: 2026-04-02  
**Decision**: All project priorities are now governed by HACKATHON_STRATEGY.md for the Kaggle "Gemma 4 Good Hackathon" ($200K prizes, deadline May 18, 2026).  
**Rationale**: The hackathon is the single best opportunity to validate and showcase Duchess. $80K+ addressable across Main, Impact, and Special Technology tracks.  
**Consequences**: Every sprint, every feature, every PR must be evaluated against hackathon value. Non-hackathon work is deferred. Video demo + writeup + live demo are mandatory deliverables.

---

## ADR-011: Gemma 4 26B MoE Replaces Qwen2.5-VL for Tier 3
**Date**: 2026-04-02  
**Decision**: Gemma 4 26B MoE (3.8B active params, 82.6% MMLU Pro) replaces Qwen2.5-VL-7B/72B as the Tier 3 local server model.  
**Rationale**: Same model family as Tier 2 (Gemma 4) — unified fine-tuning pipeline. 3.8B active of 25.2B total runs fast on M4 Max. Supports 256K context for multi-worker video fusion. Required for Ollama Prize track.  
**Consequences**: Tier 3 uses Ollama/MLX for inference. Unsloth fine-tuning targets E2B first, 26B MoE as stretch goal.
