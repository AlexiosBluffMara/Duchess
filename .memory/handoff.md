# Session Handoff — Duchess

_Read this first at the start of every session. Update before session ends (before compaction)._

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
