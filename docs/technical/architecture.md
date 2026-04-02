---
layout: default
title: "System Architecture"
parent: Technical
nav_order: 2
---

# System Architecture
{: .no_toc }

Duchess is a four-tier AI construction safety platform that runs inference from the edge of a worker's glasses to the cloud — detecting PPE violations, hazards, and safety risks in real time.
{: .fs-6 .fw-300 }

<details open markdown="block">
  <summary>Table of contents</summary>
  {: .text-delta }
1. TOC
{:toc}
</details>

---

## Four-Tier Architecture Overview

Every model, feature, and data flow in Duchess maps to exactly one tier. There is no ambiguity about where code runs.

```mermaid
graph TB
    subgraph T1["Tier 1 · Ray-Ban Meta Glasses"]
        G_CAM[Camera 504×896] --> G_YOLO[YOLOv8-nano]
        G_YOLO --> G_MOB[MobileNet PPE]
        G_MIC[Microphone] --> G_VAD[Voice Activity Detection]
    end

    subgraph T2["Tier 2 · Pixel 9 Fold"]
        P_GEMMA[Gemma 4 E2B]
        P_BLE[BLE GATT Server]
        P_MESH[Tailscale Mesh]
        P_ALERT[Alert Manager]
    end

    subgraph T3["Tier 3 · M4 Max MacBook · Optional"]
        M_GEMMA[Gemma 4 26B MoE]
        M_OLLAMA[Ollama Runtime]
        M_FUSION[Multi-Worker Fusion]
    end

    subgraph T4["Tier 4 · Google Cloud"]
        C_VERTEX[Vertex AI · Gemma 4 31B]
        C_RUN[Cloud Run API]
        C_STORE[Cloud Storage]
        C_FIRE[Firestore]
    end

    T1 -->|"BLE 5.0"| T2
    T2 -->|"WireGuard Mesh"| T3
    T2 -->|"HTTPS · PPE Escalation"| T4
    T3 -->|"Batch Upload"| T4
```

| Tier | Device | Latency Target | Primary Role |
|------|--------|---------------|--------------|
| **1** | Ray-Ban Meta Glasses | < 50 ms | Real-time PPE detection, barcode scanning |
| **2** | Pixel 9 Fold | < 2 s | NLU, bilingual alerts, triage decisions |
| **3** | M4 Max MacBook | < 5 s | Complex scene analysis, multi-worker fusion |
| **4** | Google Cloud | 100–500 ms | Nightly batch, escalated inference, multi-site orchestration |

---

## PPE Detection Pipeline

The critical path from camera frame to worker alert. This is the core safety loop.

```mermaid
flowchart TD
    A[Camera Frame Captured] --> B{YOLOv8-nano\nPPE Detection}
    B -->|"Confidence > 90%\nPPE Present"| C[✅ No Violation]
    B -->|"Confidence < 90%\nor Missing PPE"| D[Send Frame to Phone]
    D --> E{Gemma 4 E2B\nVision Analysis}
    E -->|"False Positive"| F[Dismiss · Log]
    E -->|"Confirmed Violation"| G[Immediate Cloud Escalation]
    G --> H{Gemma 4 31B\nFull Assessment}
    H -->|"Confirmed"| I[Human Reviewer]
    I -->|"Verified"| J[🚨 Alert via Mesh\nEN + ES]
    I -->|"False Positive"| K[Dismiss · Retrain Signal]
    H -->|"Inconclusive"| L[Queue for\nNightly Batch]
```

### Latency Budget

```mermaid
gantt
    title PPE Violation → Alert Delivery (Worst Case)
    dateFormat X
    axisFormat %s ms

    section Tier 1
    YOLOv8-nano detection       :0, 40
    BLE frame transfer          :40, 120

    section Tier 2
    Gemma 4 E2B analysis        :120, 1800
    Cloud escalation upload     :1800, 2200

    section Tier 4
    Gemma 4 31B assessment      :2200, 2700
    Human reviewer queue        :2700, 5000

    section Delivery
    Mesh alert broadcast        :5000, 5050
```

> **Target**: Under 5 seconds from frame capture to alert delivery for confirmed violations. Human review adds variable latency.

---

## Data Flow Rules

- **Video NEVER leaves the jobsite** unless escalated through the PPE pipeline OR nightly batch upload
- **Escalation is always upward**: Tier 1 → 2 → 4 (skip Tier 3 for PPE)
- **Tier 3 is optional** — the system fully functions with only Tiers 1, 2, and 4
- **Worker identifiers anonymized** before any data reaches cloud services
- **Location stripped** to zone-level granularity (not exact GPS)
- **No PII in logs** — not in CloudWatch, not in local logs, not in crash reports
- **Bilingual alerts** — every alert includes both English (`messageEn`) and Spanish (`messageEs`)

---

## Mesh Network Topology

All on-site devices communicate over a Tailscale WireGuard mesh. Workers with only phones are first-class participants — **ALL workers have the companion app installed**.

```mermaid
graph TD
    subgraph "Construction Site Mesh"
        W1["👷 Worker 1\nGlasses + Phone"]
        W2["👷 Worker 2\nPhone Only"]
        W3["👷 Worker 3\nGlasses + Phone"]
        W4["👷 Worker 4\nPhone Only"]
        MAC["💻 Mac Server"]
    end

    W1 <-->|"WireGuard\n<10ms"| W2
    W1 <-->|"WireGuard"| MAC
    W2 <-->|"WireGuard"| W3
    W3 <-->|"WireGuard"| W4
    W4 <-->|"WireGuard"| MAC
    MAC -->|"Tailscale Exit Node\nHTTPS"| CLOUD["☁️ Google Cloud"]
```

### Mesh Latency Tiers

| Path | Latency | Use Case |
|------|---------|----------|
| Direct peer-to-peer | < 10 ms | Alert delivery between nearby workers |
| Relay through peer | 10–30 ms | Alert delivery across site zones |
| DERP fallback | 50–150 ms | Off-mesh or degraded connectivity |
| Cloud round-trip | 100–500 ms | PPE escalation, batch upload |

### Alert Delivery via Mesh

```mermaid
sequenceDiagram
    participant G as 👓 Glasses (Tier 1)
    participant P1 as 📱 Phone - Worker 1 (Tier 2)
    participant Cloud as ☁️ Cloud (Tier 4)
    participant Mesh as 🔗 WireGuard Mesh
    participant P2 as 📱 Phone - Worker 2
    participant P3 as 📱 Phone - Worker 3

    G->>P1: BLE: PPE violation frame
    P1->>P1: Gemma 4 E2B confirms violation
    P1->>Cloud: HTTPS: Escalate for assessment
    Cloud->>Cloud: Gemma 4 31B confirms
    Cloud->>P1: Confirmed alert payload
    P1->>Mesh: Broadcast alert (geospatial targeting)
    Mesh->>P2: Alert (nearest worker, EN+ES)
    Mesh->>P3: Alert (in-zone worker, EN+ES)
```

---

## Device Specifications

| Spec | Ray-Ban Meta | Pixel 9 Fold | M4 Max MacBook |
|------|-------------|-------------|----------------|
| **SoC** | Snapdragon AR1 Gen1 | Tensor G4 | Apple M4 Max |
| **RAM** | 6 GB (500 MB ML budget) | 12 GB | 48 GB unified |
| **Battery** | 750 mAh | 4,650 mAh | Always-on |
| **Camera** | 12 MP, 504×896 @24 fps via DAT SDK | 50 MP rear, 10 MP front | N/A |
| **Display** | Heads-up projection | 7.6" inner / 6.3" outer | 16" Liquid Retina XDR |
| **Connectivity** | BLE 5.0 to phone only | BLE, WiFi, 5G, UWB | WiFi 6E, Thunderbolt |
| **ML Accelerator** | Hexagon DSP | Edge TPU (Gemma-optimized) | Neural Engine (16-core) |
| **OS** | Meta OS | Android 15 | macOS Sequoia |

### ML Model Allocation

```mermaid
pie title Glasses RAM Budget (6 GB)
    "OS + System" : 2500
    "App Runtime" : 1500
    "YOLOv8-nano (INT8)" : 300
    "MobileNet PPE (FP16)" : 150
    "Frame Buffers" : 50
    "Headroom" : 1500
```

---

## Privacy Architecture

Data classification and boundary enforcement. Nothing crosses from "On-Site Only" to the cloud without transformation.

```mermaid
flowchart LR
    subgraph "ON-SITE ONLY"
        RAW[Raw Video]
        WORKER_ID[Worker Identity]
        GPS_EXACT[Exact GPS]
        FACE[Face Data]
    end

    subgraph "LEAVES SITE (Encrypted)"
        ANON[Anonymized Alerts]
        ZONE[Zone-Level Location]
        VIOLATION[Violation Type + Severity]
        ENCRYPTED_VID[KMS-Encrypted Video Clip]
    end

    RAW -->|"PPE Escalation Only"| ENCRYPTED_VID
    WORKER_ID -->|"Pseudonymized"| ANON
    GPS_EXACT -->|"Stripped"| ZONE
```

### Data Retention

| Data Type | Storage | Encryption | Retention |
|-----------|---------|------------|-----------|
| Raw video | On-device only | Device encryption | Deleted after upload or 7 days |
| Cloud video | Cloud Storage (KMS) | AES-256 | 90 days |
| Alert metadata | Firestore | Google-managed | 1 year |
| Worker location | Memory only | WireGuard in transit | Session only |
| Safety reports | Firestore + Cloud Storage | Google-managed | 3 years |
| Model weights | Artifact Registry | Encrypted at rest | Indefinite |

---

## Technology Stack

```mermaid
graph LR
    subgraph "Edge (Glasses)"
        LiteRT[LiteRT Runtime]
        CAM2[Camera2 API]
        BLE_C[BLE Client]
    end

    subgraph "Phone"
        GEMMA_P[Gemma 4 E2B]
        COMPOSE[Jetpack Compose]
        HILT[Hilt DI]
        CACTUS[Cactus SDK]
        DAT[Meta DAT SDK]
        BLE_S[BLE GATT Server]
        TS_P[Tailscale]
    end

    subgraph "Mac Server"
        OLLAMA[Ollama]
        LLAMA_CPP[llama.cpp]
        TS_M[Tailscale]
    end

    subgraph "Cloud"
        VERTEX[Vertex AI]
        CLOUD_RUN[Cloud Run]
        FIRESTORE[Firestore]
        GCS[Cloud Storage]
        PUBSUB[Pub/Sub]
    end
```

### Key Dependencies

| Layer | Framework | Version | Purpose |
|-------|-----------|---------|---------|
| Glasses | LiteRT | 1.1+ | YOLOv8-nano, MobileNet inference |
| Glasses | Meta DAT SDK | 0.5.0 | Camera streaming from Ray-Ban Meta |
| Phone | Gemma 4 E2B | — | On-device NLU + vision (2.3B effective params) |
| Phone | Cactus SDK | latest | Local LLM runtime for Gemma |
| Phone | Jetpack Compose | BOM 2024+ | UI framework |
| Phone | Hilt | 2.51+ | Dependency injection |
| Phone | Tailscale | — | WireGuard mesh networking |
| Mac | Ollama | latest | Gemma 4 26B MoE runtime |
| Cloud | Vertex AI | — | Gemma 4 31B hosted inference |
| Cloud | Cloud Run | — | Serverless API endpoints |

---

## Graceful Degradation

The system is designed to keep workers safe even when tiers go offline.

| Tier Unavailable | Impact | Behavior |
|-----------------|--------|----------|
| **Tier 4** (Cloud) | No nightly batch, no escalation confirmation | Phone handles all decisions locally, alerts still delivered via mesh |
| **Tier 3** (Mac) | No complex scene analysis | Phone-only inference — already the default path |
| **Tier 2** (Phone down) | No Gemma 4 NLU | Glasses continue YOLO detection, cache alerts locally |
| **Tier 1** (Glasses off) | No AR, no pre-detection | Phone still receives mesh alerts, manual reporting available |

```mermaid
stateDiagram-v2
    [*] --> FullSystem: All tiers online

    FullSystem --> NoCloud: Tier 4 offline
    FullSystem --> NoMac: Tier 3 offline
    FullSystem --> NoPhone: Tier 2 offline
    FullSystem --> NoGlasses: Tier 1 offline

    NoCloud: Phone-Local Decisions
    NoCloud --> FullSystem: Cloud restored

    NoMac: Phone-Only Inference (Default)
    NoMac --> FullSystem: Mac restored

    NoPhone: Glasses Cache + YOLO Only
    NoPhone --> FullSystem: Phone restored

    NoGlasses: Mesh Alerts + Manual Reports
    NoGlasses --> FullSystem: Glasses restored

    NoCloud --> Isolated: All tiers offline
    NoPhone --> Isolated: All tiers offline
    Isolated: Local YOLO Only · No Alerts
    Isolated --> FullSystem: Connectivity restored
```

> **Design principle**: A worker wearing glasses with a charged phone always has safety coverage, even with zero network connectivity. YOLO runs fully on-device.

---

## Deployment Topology

```mermaid
C4Context
    title Duchess Deployment — Single Construction Site

    Person(worker_ar, "AR Worker", "Glasses + Phone")
    Person(worker_phone, "Phone Worker", "Phone Only")
    Person(supervisor, "Site Supervisor", "Phone + Dashboard")

    System_Boundary(site, "Construction Site") {
        System(glasses, "Ray-Ban Meta", "Tier 1 · Edge inference")
        System(phone, "Pixel 9 Fold", "Tier 2 · Gemma 4 E2B")
        System(mac, "M4 Max MacBook", "Tier 3 · Optional server")
    }

    System_Ext(cloud, "Google Cloud", "Tier 4 · Vertex AI + Cloud Run")

    Rel(worker_ar, glasses, "Wears")
    Rel(worker_ar, phone, "Carries")
    Rel(worker_phone, phone, "Carries")
    Rel(supervisor, phone, "Monitors")
    Rel(glasses, phone, "BLE 5.0")
    Rel(phone, mac, "WireGuard mesh")
    Rel(mac, cloud, "HTTPS via exit node")
    Rel(phone, cloud, "PPE escalation")
```

---

_Last updated: {{ "now" | date: "%Y-%m-%d" }}_
