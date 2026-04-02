---
description: Create a new feature spec from a natural language description
---

Create a new feature spec for Duchess using spec-driven development.

## Usage

```
/spec <feature-name> [description]
```

Example:
```
/spec ppe-detection "Real-time PPE violation detection pipeline from Ray-Ban camera to bilingual audio alert"
```

## What this command does

1. Reads `specs/_template.spec.md`
2. Creates `specs/<feature-name>.spec.md` with the description pre-filled
3. Identifies the correct tier placement based on the description
4. Pre-fills acceptance criteria stubs from the feature description
5. Flags which agents should review before implementation begins

## Spec-driven development workflow

```
1. /spec <name>           → Create the spec (Duke + domain agent review)
2. Spec approved          → Marked status: approved in frontmatter
3. Agent implements       → Code references spec AC numbers in PR description
4. Sam runs QA            → Verifies each AC is met
5. All ACs pass           → Spec status: implemented
```

## Rules

- **No code until spec is approved.** The pre-commit hook enforces this.
- Every spec needs at least one AC per tier it touches.
- All user-facing strings in the spec get bilingual entries (Luis fills them).
- Privacy checklist must be completed before status moves to `approved`.

## Agents who review specs

| Feature domain | Reviewer |
|---|---|
| Camera / streaming | Alex + Kai |
| PPE detection / ML | Elena + Carlos |
| Bilingual alerts | Luis + Maya |
| Cloud pipeline | Jordan |
| Network / mesh | Noah |
| Safety compliance | Carlos |
| Testing | Sam |

Duke approves all specs before implementation begins.
