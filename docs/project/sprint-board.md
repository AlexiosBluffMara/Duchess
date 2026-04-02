---
layout: default
title: "Sprint Board"
parent: Project Management
nav_order: 1
---

# Sprint Board
{: .no_toc }

Track progress across all Duchess sprints.
{: .fs-6 .fw-300 }

## Table of Contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## Current Sprint: Sprint 1 — Foundation (Apr 2–8)

**Sprint Goal:** Establish Gemma 4 research, hackathon strategy, project infrastructure, and GCP setup.

### Kanban Board

| Done ✅ | In Progress 🔄 | To Do 📋 |
|---------|----------------|----------|
| Gemma 4 research & model card analysis (3 pts) | GitHub Pages site setup (5 pts) | Create GCP project `duchess-hackathon` (3 pts) |
| Hackathon strategy document (5 pts) | Public README rewrite (3 pts) | Register team on Kaggle (1 pt) |
| Codebase migration Gemma 3n → 4 (8 pts) | | Install gcloud CLI + SDK (2 pts) |
| Local Ollama inference verified (3 pts) | | Cactus SDK research + prototype (5 pts) |
| Master stakeholder dossier (8 pts) | | Dataset download + curation start (3 pts) |
| Judge/stakeholder research (5 pts) | | Audit dependency licenses (2 pts) |
| Technology track tool research (3 pts) | | |

**Sprint Velocity:** 35 points planned, 27 completed

---

## Backlog

### Sprint 2: Integration (Apr 9–15) — 34 points planned

| ID | Ticket | Points | Owner | Priority | Status |
|----|--------|--------|-------|----------|--------|
| D-201 | Gemma 4 E2B integration in GemmaInferenceService | 8 | Alex | P0 | Not Started |
| D-202 | Multimodal vision input (camera frames → Gemma 4) | 5 | Alex + Kai | P0 | Not Started |
| D-203 | Native function calling for structured safety output | 5 | Alex | P0 | Not Started |
| D-204 | Cactus SDK integration for multi-tier routing | 5 | Alex + Kai | P1 | Not Started |
| D-205 | GCP Cloud Run API skeleton deployment | 3 | Jordan | P1 | Not Started |
| D-206 | GCP Firestore schema setup | 3 | Jordan | P1 | Not Started |
| D-207 | LiteRT migration (TFLite → LiteRT) | 3 | Kai | P1 | Not Started |
| D-208 | Zero-shot safety benchmarks (baseline) | 2 | Priya | P1 | Not Started |

### Sprint 3: Fine-Tuning (Apr 16–22) — 31 points planned

| ID | Ticket | Points | Owner | Priority | Status |
|----|--------|--------|-------|----------|--------|
| D-301 | Unsloth QLoRA: safety text adapter | 8 | Priya | P0 | Not Started |
| D-302 | Unsloth QLoRA: safety_vision adapter (multimodal) | 8 | Priya + Elena | P0 | Not Started |
| D-303 | Unsloth QLoRA: spanish_jargon adapter | 5 | Priya + Luis | P0 | Not Started |
| D-304 | Export adapters to GGUF + SafeTensors | 3 | Kai | P1 | Not Started |
| D-305 | Zero-shot vs fine-tuned benchmarks | 5 | Priya | P1 | Not Started |
| D-306 | Publish models to Hugging Face | 2 | Priya | P1 | Not Started |

### Sprint 4: End-to-End Demo (Apr 23–29) — 29 points planned

| ID | Ticket | Points | Owner | Priority | Status |
|----|--------|--------|-------|----------|--------|
| D-401 | Full pipeline: glasses → phone → cloud | 8 | Alex | P0 | Not Started |
| D-402 | Bilingual voice input demo (Gemma 4 audio) | 5 | Alex + Luis | P1 | Not Started |
| D-403 | llama.cpp on edge hardware demo | 5 | Kai | P1 | Not Started |
| D-404 | Ollama Mac server demo (Tier 3) | 3 | Kai | P2 | Not Started |
| D-405 | Vertex AI Gemma 4 31B endpoint | 5 | Jordan | P1 | Not Started |
| D-406 | LiteRT NPU benchmarks on Tensor G4 | 3 | Kai | P1 | Not Started |

### Sprint 5: Submission (Apr 30–May 7) — 26 points planned

| ID | Ticket | Points | Owner | Priority | Status |
|----|--------|--------|-------|----------|--------|
| D-501 | Record 3-min video demo on construction site | 13 | Duke + Maya | P0 | Not Started |
| D-502 | Write Kaggle writeup (≤1,500 words) | 5 | Wei + Duke | P0 | Not Started |
| D-503 | Architecture diagrams + cover image | 3 | Maya | P0 | Not Started |
| D-504 | Clean up GitHub repo (README, docs, LICENSE) | 3 | Taylor | P0 | Not Started |
| D-505 | Test live demo URL | 2 | Sam | P0 | Not Started |

---

## Burndown

```
Sprint 1 Burndown:
Day 1 (Apr 2): ████████████████████████████████░░░░░ 35 pts remaining
Day 2 (Apr 3): ████████████████████████░░░░░░░░░░░░░ 27 pts remaining  ← research done
Day 3 (Apr 4): ████████████████████░░░░░░░░░░░░░░░░░ 22 pts remaining  ← strategy docs
Day 4 (Apr 5): ░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░ TBD
```

---

## Definition of Done

Every ticket must satisfy all of the following before it can move to **Done ✅**:

- [ ] Code compiles with zero errors
- [ ] Unit tests pass
- [ ] No PII in log statements
- [ ] Bilingual strings (EN + ES) for all user-facing text
- [ ] Privacy rules enforced (video stays on-site, worker IDs anonymized)
- [ ] Conventional commit message
- [ ] Pre-commit hook passes (4/4 checks)
