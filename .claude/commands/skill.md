---
description: Create or update a skill and mirror it to both .github/skills/ and .claude/skills/
---

Create a new Duchess skill or update an existing one, automatically mirrored to both AI tool directories.

## Usage

```
/skill <skill-name> [description]
```

Example:
```
/skill edge-inference "Optimize and deploy ML inference on edge devices with LiteRT and ONNX"
/skill ppe-detection-pipeline "End-to-end PPE pipeline from glasses camera to worker alert"
```

## What this command does

1. Creates `.github/skills/<skill-name>/SKILL.md` with the Duchess SKILL format
2. Runs `scripts/mirror-skills.sh github` to mirror it to `.claude/skills/<skill-name>.md`
3. Updates `.claude/settings.json` to register the new skill
4. Confirms both files are in sync

## Skill file format

### `.github/skills/<name>/SKILL.md` (canonical)

```markdown
---
name: <name>
description: "<one-line description>"
---

# <Name>

## When to Use
- ...

## Procedure
1. ...

## Key Patterns
...

## Pitfalls
...
```

### `.claude/skills/<name>.md` (auto-generated mirror)

Generated automatically by `mirror-skills.sh` from the `.github` canonical.
Do not edit the `.claude` copy directly — edits will be overwritten on next sync.

## Mirror rules

| Source | Destination | When |
|--------|-------------|------|
| `.github/skills/*/SKILL.md` | `.claude/skills/*.md` | Skill created/updated in .github |
| `.claude/skills/*.md` | `.github/skills/*/SKILL.md` | Skill created directly in .claude |
| Either | Both | `scripts/mirror-skills.sh auto` |

The pre-commit hook runs `mirror-skills.sh check` — commits are blocked if skills are out of sync.
The post-merge hook runs `mirror-skills.sh auto` — pulls automatically keep skills in sync.

## Quickstart

```bash
# After creating a .github/skills/<name>/SKILL.md manually:
./scripts/mirror-skills.sh github

# After creating a .claude/skills/<name>.md manually:
./scripts/mirror-skills.sh claude

# Check for drift without modifying anything:
./scripts/mirror-skills.sh check
```
