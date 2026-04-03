---
layout: default
title: "Team & Roles"
parent: Project Management
nav_order: 2
---

# Team & Roles
{: .no_toc }

The people and agents behind Duchess.
{: .fs-6 .fw-300 }

## Table of Contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## Core Team

**Bhattacharya, Lahiri** — Illinois State University / Alexios Bluff Mara LLC

---

## Academic Advisors

- **Dr. Mangolika Bhattacharya** — IoT, digital twins, AI for engineering. Co-author on vision transformers for engineering applications.
- **Dr. Haiyan Sally Xie** — Construction digital twins, BIM, computer vision. Landmark paper "Construction with Digital Twin Information Systems" (791 citations).

---

## AI Agent Team

15 specialist agents coordinated by **Duke**:

| Agent | Role | Primary Deliverables |
|-------|------|---------------------|
| **Duke** | Project Coordinator | Strategy, delegation, bilingual QA |
| **Alex** | Android Developer | Phone app, glasses app, Kotlin |
| **Carlos** | Safety Officer | OSHA compliance, safety protocol |
| **Elena** | CV Engineer | YOLOv8, PPE detection |
| **Jordan** | Cloud Engineer | GCP infrastructure, Vertex AI |
| **Kai** | Edge AI Specialist | LiteRT, ONNX, quantization |
| **Luis** | Bilingual Specialist | EN/ES localization |
| **Maya** | UX/AR Designer | AR HUD, phone UI |
| **Mei** | Structural Engineer | Engineering analysis |
| **Noah** | Network Engineer | Tailscale mesh |
| **Priya** | ML Engineer | Unsloth fine-tuning |
| **Raj** | Field Superintendent | Jobsite operations |
| **Sam** | QA Engineer | Testing strategy |
| **Taylor** | DevOps Engineer | CI/CD, deployment |
| **Wei** | Research Scientist | Papers, grants |

---

## Looking to Recruit

We're looking for **one additional software developer** to join the team (max 5 members allowed by hackathon rules).

**Ideal candidate:**
- Experience with Android/Kotlin OR Python ML
- Interest in construction safety / social impact
- Available through May 18, 2026
- Contact: [Open an issue on the repo](https://github.com/duchess-platform/duchess/issues)

---

## How We Work

- **Conventional commits** — `feat(phone): ...`, `fix(ml): ...`
- **Pre-commit hooks** enforce code quality (4/4 checks must pass)
- **Memory system** (`.memory/`) for cross-session context between agents
- **Weekly sprint cadence** with burndown tracking
- **All user-facing features must be bilingual** (EN + ES)
- **Privacy-first** — no PII in logs, video stays on-site, worker IDs anonymized before cloud
