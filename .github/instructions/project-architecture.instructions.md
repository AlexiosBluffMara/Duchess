---
description: "Use when working on the four-tier inference architecture, tier boundaries, data flow between tiers, or system-level design decisions. Covers Tier 1 (glasses), Tier 2 (phone), Tier 3 (Mac server), Tier 4 (cloud)."
applyTo: "**"
---

# Duchess Platform Architecture

## Four-Tier Inference Hierarchy

Every feature, model, and data flow MUST respect these tier boundaries:

### Tier 1: On-Glass Edge (Vuzix M400)
- **Latency**: <50ms
- **Models**: YOLOv8-nano, MobileNet PPE classifier
- **Capability**: Real-time PPE detection, barcode scanning, basic classification
- **Constraint**: 6GB RAM (500MB ML budget), 750mAh battery, no Google Play Services
- **Communication**: BLE 5.0 to companion phone only
- **Privacy**: All processing local, no network access

### Tier 2: Companion Smartphone
- **Latency**: <2s
- **Models**: Gemma 4 E2B (2.3B effective params)
- **Capability**: NLU, bilingual alerts, PPE confirmation, triage decisions
- **Constraint**: Battery life matters, but more headroom than glasses
- **Communication**: BLE to glasses, Tailscale mesh to other phones/servers, HTTPS to cloud
- **Privacy**: On-device except when escalating to cloud

### Tier 3: Local Mac Server (Optional)
- **Latency**: <5s
- **Models**: Qwen2.5-VL-7B or 72B via MLX
- **Capability**: Complex scene analysis, multi-worker video fusion
- **Constraint**: M3 Ultra, 512GB RAM, always-on power
- **Communication**: Tailscale mesh, aggregates from all phones
- **Privacy**: All processing on-premises, never leaves jobsite
- **Note**: May not be present on all sites — system must work without it

### Tier 4: Cloud (AWS)
- **Latency**: 100-500ms
- **Services**: Bedrock, SageMaker, IoT TwinMaker
- **Capability**: Nightly batch analysis, escalated PPE assessment, multi-site orchestration
- **Constraint**: Cost per inference, internet required
- **Communication**: HTTPS over Tailscale exit node
- **Privacy**: Video encrypted in transit, anonymized before processing

## Data Flow Rules

1. **Video never leaves the jobsite** unless:
   - Gemma 4 (Tier 2) triggers an immediate PPE escalation
   - Nightly batch upload runs after shift ends
2. **Escalation is always upward** (Tier 1→2→4, skip Tier 3 for PPE escalation)
3. **Tier 3 is optional** — the system must fully function with only Tiers 1, 2, and 4
4. **Every feature must define its tier** — no "it runs somewhere" ambiguity
5. **Graceful degradation**: If a higher tier is unavailable, lower tiers continue independently

## Worker Types

- **Glasses + Phone**: Full Tier 1 + Tier 2 capability, AR HUD alerts
- **Phone Only**: Tier 2 alerts via phone notification, mesh participant, no AR
- **ALL workers must have the phone app installed**
