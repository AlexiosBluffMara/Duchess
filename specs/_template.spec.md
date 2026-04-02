# Spec Template
# Copy this file to specs/<feature-name>.spec.md and fill in each section.
# The pre-commit hook requires this file to exist before any feature Kotlin
# files under that name can be committed.
#
# Filename convention:  specs/<feature-name>.spec.md
# Examples:             specs/stream.spec.md
#                       specs/ppe-detection.spec.md
#                       specs/bilingual-alerts.spec.md

---
title: "<Feature Name>"
tier: "<phone | glasses | shared | cloud | ml>"
status: "draft"          # draft | approved | implemented | deprecated
authors: ["<agent-name>"]
created: "<YYYY-MM-DD>"
updated: "<YYYY-MM-DD>"
---

## Overview

<!-- One paragraph. What does this feature do and why does it exist? 
     Focus on the user/worker outcome, not the implementation. -->

## Tier Placement

<!-- Which tier(s) does this feature run on? Justify the placement against
     the latency and privacy constraints from project-architecture.instructions.md -->

| Tier | Component | Rationale |
|------|-----------|-----------|
| Tier 2 (Phone) | ... | ... |

## Acceptance Criteria

<!-- Each criterion must be testable. Write them as "Given / When / Then" 
     or as a checklist. Sam (QA) owns verifying these. -->

- [ ] **AC-1**: Given ... When ... Then ...
- [ ] **AC-2**: Given ... When ... Then ...
- [ ] **AC-3**: ...

## Inputs & Outputs

| Direction | Data | Format | Source / Destination |
|-----------|------|--------|----------------------|
| Input  | ... | ... | ... |
| Output | ... | ... | ... |

## Latency & Performance Budget

| Constraint | Target | Hard Limit |
|------------|--------|------------|
| End-to-end latency | ... | ... |
| Memory footprint | ... | ... |
| Battery impact | ... | ... |

## Privacy & Security Checklist

<!-- From data-privacy.instructions.md — required for every feature. -->

- [ ] No PII in log statements
- [ ] Worker identifiers anonymized before cloud
- [ ] Video data encrypted in transit (WireGuard / TLS)
- [ ] Location data not persisted to disk
- [ ] No secrets in code (stored in local.properties or Secrets Manager)

## Bilingual Requirements

<!-- All user-facing strings must support English and Spanish.
     List the strings this feature introduces. Luis owns the translations. -->

| Key | English | Spanish |
|-----|---------|---------|
| `alert_<feature>_title` | ... | ... |
| `alert_<feature>_body`  | ... | ... |

## Open Questions

<!-- List unresolved decisions. Each should be assigned to an agent. -->

- [ ] **Q1**: ... (owner: @<agent>)

## Out of Scope

<!-- Be explicit about what this spec does NOT cover to prevent scope creep. -->

- ...

## Implementation Notes

<!-- Optional: hints for the implementing agent (Alex, Kai, Elena, etc.).
     Not prescriptive — the agent chooses the implementation. -->

---
*Spec approved by Duke before implementation begins.*
