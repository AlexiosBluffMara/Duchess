# Project State — Duchess

_Last updated: 2026-04-01 | Updated by: Claude Code_

## Infrastructure Status

| Component | Status | Notes |
|-----------|--------|-------|
| Agent ecosystem (15 agents) | ✅ Complete | `.github/agents/`, `.claude/agents/` |
| Skill library (22 skills) | ✅ Complete | `.github/skills/`, `.claude/skills/` |
| Instruction files (9) | ✅ Complete | `.github/instructions/` |
| Git hooks system | ✅ Complete | `.githooks/` — pre-commit (4 checks), commit-msg, post-merge |
| Skill/agent mirror script | ✅ Complete | `scripts/mirror-skills.sh` — bidirectional sync |
| Spec template | ✅ Complete | `specs/_template.spec.md` |
| Slash commands | ✅ Complete | `.claude/commands/` — build, spec, skill |
| AGENTS.md | ✅ Complete | Root-level universal agent instructions |
| README.md | ✅ Complete | Project overview |
| Claude Code CLI | ✅ Installed | v2.1.90 at `/opt/homebrew/bin/claude` |
| gh CLI + gh copilot | ✅ Installed | v2.89.0 + Copilot 1.0.15 |
| CLAUDE.md | ✅ Complete | Claude Code's auto-loaded instructions |
| `.memory/` system | ✅ Complete | Shared Copilot ↔ Claude context bridge |

## App Development Status

| Component | Status | Branch | Notes |
|-----------|--------|--------|-------|
| `app-phone/` Android scaffold | ✅ Complete | claude/keen-lalande | 23 files: DAT SDK + Gemma 3n + BLE + Compose + Hilt |
| `app-glasses/` Vuzix AOSP scaffold | ✅ Complete | claude/keen-lalande | Camera2 + TFLite + BLE client + HUD renderer |
| `ml/` Python training pipeline | ✅ Complete | claude/keen-lalande | Unsloth QLoRA, Gemma 3n, YOLOv8-nano, export pipeline |
| `cloud/` AWS CDK scaffold | ✅ Complete | claude/keen-lalande | S3, DynamoDB, SQS, Lambda+Bedrock, CDK v2 |
| iOS supervisor dashboard | 🔲 Not started | — | Phase 2, iPhone 16 Pro Max |

## Hardware Confirmed

| Tier | Device | Role |
|------|--------|------|
| 1 | Ray-Ban Wayfarer (Meta) | Sensor/speaker, DAT SDK streaming |
| 2 | Google Pixel 9 Fold (primary) | Android companion app, Gemma 3n |
| 2 | iPhone 16 Pro Max | iOS supervisor dashboard (Phase 2) |
| 3 | M4 Max MacBook Pro (48GB) | Local Qwen2.5-VL server, MLX |
| Training | RTX 5090 (64GB/8TB) | Unsloth QLoRA fine-tuning |

## Known Issues / Blockers

- `local.properties` with `github_token=ghp_...` must be created by each dev (gitignored, not yet done)
- Android Studio project not yet created — Claude is scaffolding it
- VS Code shows stale pre-commit errors (from old hook version) — actual hook passes 4/4

## Next Actions

1. **Claude**: Build `app-phone/` Android Gradle project (see `claude-queue.md`)
2. **Copilot**: Review Claude's output, wire up any missing Kotlin idioms
3. **Both**: Create `local.properties.template` once DAT SDK token pattern is confirmed
