# Duchess — Gemma 4 Good Hackathon: Master Strategy & Stakeholder Dossier

> **Competition**: [Gemma 4 Good Hackathon](https://www.kaggle.com/competitions/gemma-4-good-hackathon)  
> **Total Prizes**: $200,000  
> **Real Deadline**: May 18, 2026 (11:59 PM UTC)  
> **Internal Deadline**: May 7, 2026 (11 days early — no excuses)  
> **Team**: Duchess (max 5 members)  
> **Repo**: AlexiosBluffMara/Duchess  
> **Document Owner**: Duke (Project Coordinator)

---

## Table of Contents

1. [Stakeholder Breakdown](#1-stakeholder-breakdown)
2. [Academic Advisors Deep Dive](#2-academic-advisors-deep-dive)
3. [Special Technology Track: Tool & Framework Profiles](#3-special-technology-track-tool--framework-profiles)
4. [Google Cloud Migration Strategy](#4-google-cloud-migration-strategy)
5. [Rules Compliance Assessment](#5-rules-compliance-assessment)
6. [Free Compute & Cloud Credits](#6-free-compute--cloud-credits)
7. [Datasets for Fine-Tuning](#7-datasets-for-fine-tuning)
8. [Benchmark Analysis & Model Arena](#8-benchmark-analysis--model-arena)
9. [Model Release Strategy](#9-model-release-strategy)
10. [Prize Pool Gamification Matrix](#10-prize-pool-gamification-matrix)
11. [Revised Timeline (Internal Deadline: May 7)](#11-revised-timeline-internal-deadline-may-7)
12. [Production Scaling Strategy](#12-production-scaling-strategy)

---

## 1. Stakeholder Breakdown

### 1.1 Competition Sponsor

**Google LLC** — 1600 Amphitheatre Parkway, Mountain View, CA 94043

### 1.2 Named Judges

These are the individuals explicitly listed as judges on the competition page. Every design decision should be optimized for their backgrounds.

| Name | Role | Organization | Kaggle Profile | Key Interests |
|------|------|-------------|----------------|---------------|
| **Glenn Cameron** | Competition Host | Google / Kaggle | [gcameron](https://www.kaggle.com/gcameron) | Kaggle competition hosting, Google developer ecosystem |
| **Kristen Quan** | Competition Host | Google / Kaggle | [kristenquan](https://www.kaggle.com/kristenquan) | Featured Competition Host (2+ years), hackathon operations |
| **Gusthema (Luiz Gustavo Martins)** | Product Manager, Gemma | Google, London | [gusthema](https://www.kaggle.com/gusthema) | "Making Gemma models amazing for everyone!" — Notebooks Master (Rank 348 of 60K+), 10 gold medals, 1,118 discussions, 36 code notebooks, 16 models, 10 competitions. Follows Unsloth AI, szelee, DavidTroxellUCLA, Surya Bhupatiraju |
| **Ian Ballantyne** | Competition Administration | Google / Kaggle | [ianballantyne](https://www.kaggle.com/ianballantyne) | Competition citation author, operational role |

### 1.3 Citation Authors (Likely Extended Jury or Organizers)

These individuals are credited in the competition citation, suggesting organizational involvement even if not listed as primary judges.

| Name | Role | Organization | Profile | Key Interests |
|------|------|-------------|---------|---------------|
| **Omar Sanseviero** | Developer Experience Lead | Google DeepMind, Zürich | [osanseviero](https://www.kaggle.com/osanseviero), [hackerllama](https://osanseviero.github.io/hackerllama/) | **KEY STAKEHOLDER.** Drives developer success for AI Studio, Gemini API, and Gemma. Previously Chief Llama Officer / Head of Platform & Community at Hugging Face. Scaled HF Spaces from 500 → 400K demos. Led partnerships with Meta, Google, open-source communities. Led launches for Gemini 3, Gemma, MedGemma, EmbeddingGemma. Strong open ML advocate. 325 Kaggle followers, 11 models, 4 competitions. Based in Zürich (lived in Mexico, Peru, Panama → Spanish speaker). |
| **María Cruz** | Competition Administration | Google / Kaggle | [mariacruz](https://www.kaggle.com/mariacruz) | Operational role |
| **Olivier Lacombe** | Competition Administration | Google / Kaggle | [olivierlacombe](https://www.kaggle.com/olivierlacombe) | Operational role |

### 1.4 Strategic Implications for Judges

1. **Gusthema is the gatekeeper.** As Gemma PM, he cares about:
   - Novel, creative uses of Gemma's unique features (vision, audio, function calling, thinking mode)
   - Real-world deployment, not toy demos
   - He follows **Unsloth AI** on Kaggle — strong signal to invest in the Unsloth Prize
   - Kaggle Notebooks Master → appreciates well-documented, reproducible notebooks

2. **Omar Sanseviero is the DX champion.** He cares about:
   - Developer experience and integration quality
   - Open source and community contribution
   - He's a **Spanish speaker** (from Latin America) → our bilingual EN/ES story will resonate deeply
   - He values seamless tool integration (HF Spaces, spaCy, Sentence Transformers → Cactus, Ollama integration)
   - His team works on GDM open model efforts → publish our fine-tuned model publicly

3. **Collectively**, the judging panel cares about:
   - Impact & Vision (40 points) — **Does this solve a real problem?** → 1,056 construction deaths/year
   - Video storytelling (30 points) — **Can you make me care in 3 minutes?** → Bilingual worker safety story
   - Technical depth (30 points) — **Did you use Gemma 4's unique features?** → Vision + audio + function calling + thinking mode + 140 languages

---

## 2. Academic Advisors Deep Dive

### 2.1 Dr. Mangolika Bhattacharya (M. Bhattacharya)

**Affiliation**: Likely University of Limerick or Waterford IT (based on co-author network with Penica, O'Brien, O'Connell — Irish institutions)  
**Google Scholar ID**: [OQP7I-MAAAAJ](https://scholar.google.com/citations?user=OQP7I-MAAAAJ)

**Research Areas**: IoT, digital twins, smart manufacturing, AI for engineering, access control systems, additive manufacturing QC

**Key Publications** (most relevant to Duchess):

| Year | Title | Venue | Citations | Relevance to Duchess |
|------|-------|-------|-----------|---------------------|
| 2025 | "Evaluating Vision Transformers for Robust Feature Extraction in Engineering Applications" | IEEE Symposium on AI-Driven CV | — | **DIRECT.** ViTs for infrastructure inspection under varying viewpoints/lighting/occlusions. Validates our multi-view PPE approach. |
| 2025 | "Advancing Interoperable IoT-based Access Control Systems: A Unified Security Approach" | IEEE Access | 6 | IoT security in diverse environments. Parallels our WireGuard mesh + ACL approach. |
| 2024 | "AI-driven Real-time Failure Detection in Additive Manufacturing" | Procedia Computer Science | 15 | Real-time AI detection on constrained devices. Validates our edge inference architecture. |
| 2024 | "Artificial Intelligence and NLP for Quality Control and Management" | IEEE (co-authored with HS Xie) | — | AI/NLP for quality control → similar to our Gemma 4 NLU safety assessment. |
| 2023 | "Digital Twins: Enabling Interoperability in Smart Manufacturing Networks" | Telecom (MDPI) | 61 | Digital twin interoperability, directly relevant to our IoT TwinMaker roadmap. |
| 2023 | "Adaptable Decision Making Chatbot System: Unlocking Interoperability" | IEEE ISSC | 13 | Chatbot for manufacturing decisions. Parallels our Gemma 4 NLU-driven safety triage. |

**Why she matters**: Her work bridges **AI/computer vision for industrial environments** with **IoT/digital twin infrastructure** — exactly the intersection Duchess lives in. A citation to or collaboration reference with her work strengthens our academic credibility in the Safety & Trust and Global Resilience tracks.

### 2.2 Dr. Haiyan Sally Xie (HS Xie)

**Affiliation**: University of Cambridge (based on Brilakis co-authorship), now likely at a US/UK institution  
**Google Scholar**: Search "HS Xie" + "Brilakis" or "construction"

**Research Areas**: Construction digital twins, BIM, computer vision for AEC, reality capture, bridge deterioration prediction, AI for construction education

**Key Publications** (most relevant to Duchess):

| Year | Title | Venue | Citations | Relevance to Duchess |
|------|-------|-------|-----------|---------------------|
| 2020 | **"Construction with Digital Twin Information Systems"** | Data-centric Engineering (Cambridge) | **791** | **LANDMARK PAPER.** Defines the vision of digital twin for construction using computer vision, IoT, 5G, and ML. Co-authored with Sacks and **Ioannis Brilakis** (Cambridge construction AI lab). Duchess is essentially implementing this vision with Gemma 4. |
| 2025 | "Exploring the Role of AI in Supporting Construction Education" | IEEE ICAIE | 1 | AI + robotics in construction education. Supports our Future of Education prize narrative. |
| 2025 | "Algorithm Comparison for Real-Time Indoor Environment Digital Twin using BIM" | IEEE CSCI | — | Real-time DT creation integrating diverse datasets with BIM. Relevant to our Tier 3 scene analysis. |
| 2025 | "Evaluating Vision Transformers for Robust Feature Extraction in Engineering Applications" | IEEE (co-authored with Bhattacharya) | — | ViTs for engineering visual inspection. Validates our vision pipeline design. |
| 2024 | "Improving Prediction Accuracy of Bridge Element Deterioration for Decision Support" | IEEE PRAI | — | ML/DL for infrastructure monitoring. Validates our multi-tier inference approach. |
| 2024 | "AI and NLP for Quality Control and Management" | IEEE (co-authored with Bhattacharya) | — | AI/NLP for quality management in construction. |
| 2021 | "Reality Capture: Photography, Videos, Laser Scanning and Drones" | Springer (Industry 4.0 for the Built Environment) | 17 | Comprehensive treatment of visual data capture for construction — exactly our Tier 1 camera pipeline. |

**Why she matters**: Sally Xie's landmark paper on construction digital twins (791 citations) is essentially the academic foundation of what Duchess is building. Her work with **Ioannis Brilakis** at Cambridge — one of the most cited researchers in construction AI — gives our project massive academic credibility. Citing her work in our Kaggle writeup and YouTube video creates an implicit endorsement of our approach.

### 2.3 Cross-Publication Network

Bhattacharya and Xie are **co-authors** on multiple papers (2024-2025). They share the research network around:
- Vision Transformers for engineering
- AI/NLP for quality management
- Real-time digital twins

This is not coincidental — they represent a cohesive research community focused on **AI for construction and engineering environments**, which is exactly Duchess's domain.

---

## 3. Special Technology Track: Tool & Framework Profiles

### 3.1 Cactus ($10K Prize)

> "For the best local-first mobile or wearable application that intelligently routes tasks between models"

**What it is**: Cactus is an **on-device AI runtime with intelligent cloud fallback**. Y Combinator backed. Built by alumni from Oxford, DeepRender, Salesforce, Google, AWS, MIT.

**Core Capabilities**:
- **Hybrid inference**: Runs models on-device (phones, laptops, wearables) with automatic cloud handoff for complex tasks
- **Cactus Hybrid Router**: Monitors input complexity in real-time. Simple tasks → on-device (<120ms). Complex tasks → cloud
- **Privacy-first**: Lock to on-device-only for HIPAA/GDPR compliance. Zero data retention.
- **Cross-platform SDK**: iOS, Android, macOS, wearables from single API
- **C++ engine**: Zero-copy memory mapping, quantized models, hardware-specific acceleration
- **Automatic quality routing**: For audio — clear speech on-device, noisy speech → cloud. For LLM — simple commands on-device, complex reasoning → cloud.

**Key stats**: 4.6K+ GitHub stars, 5x cost savings, <120ms on-device latency, <6% WER

**Why Duchess is a PERFECT match**:
- Our 4-tier architecture (glasses → phone → Mac → cloud) IS Cactus's philosophy
- PPE detection: simple → on-device YOLOv8. Complex scene analysis → Gemma 4 on phone. Escalation → cloud
- Wearable intelligence (smart glasses) is literally in their use case showcase
- Privacy-first: construction site video stays on-device unless escalated = Cactus's HIPAA story
- Their API: `cactus_complete(model, messages, response, ...)` → direct integration with our `GemmaInferenceService`

**Integration plan**: Replace our custom tier-routing logic with Cactus SDK. Demonstrate intelligent routing between YOLOv8-nano (Tier 1), Gemma 4 E2B (Tier 2), and cloud (Tier 4) using Cactus's complexity-based router.

### 3.2 Unsloth ($10K Prize)

> "For the best fine-tuned Gemma 4 model created using Unsloth, optimized for a specific, impactful task"

**What it is**: Unsloth is an **AI fine-tuning platform** that makes training 30x faster with 90% less memory. Created by Daniel & Michael Han.

**Core Capabilities**:
- **Custom CUDA kernels**: 30x faster than FlashAttention 2, 90% less VRAM
- **Unsloth Studio**: No-code training with real-time observability, run 100% offline on Mac/Windows
- **Data Recipes**: Auto-transform PDFs, CSV, JSON docs into training datasets via graph-node workflow
- **Model Arena**: Side-by-side model comparison (base vs fine-tuned)
- **LoRA, FP8, FFT support**: 500+ supported models including text, vision, audio, embeddings
- **Multi-GPU**: Distributed training support
- **Export**: Safetensors, GGUF for llama.cpp, vLLM, Ollama
- **Gemma 4 support**: Confirmed supported (Daniel Han is on Kaggle, followed by Gusthema)

**Key claim**: "Train your own custom model in 24 hrs, not 30 days"

**Why Duchess is ideal for Unsloth Prize**:
- **Specific, impactful task**: PPE detection on construction sites (saves lives)
- **Fine-tuning plan**:
  1. `safety` adapter: Construction safety classification, hazard identification, OSHA terminology
  2. `safety_vision` adapter: Multimodal — PPE detection from camera frames
  3. `spanish_jargon` adapter: Construction-register Spanish terminology, bilingual alerts
- **Benchmarks**: Before/after on iSafetyBench, Construction-PPE F1, bilingual BLEU scores
- **Model publication**: Upload to Hugging Face as `duchess/gemma-4-e2b-construction-safety`
- **Data Recipes**: Use Unsloth Studio to convert OSHA safety manuals (PDFs) into training data

**Critical signal**: Gusthema (Gemma PM, judge) **follows Unsloth AI on Kaggle**. This is a strong endorsement to invest heavily in the Unsloth Prize.

### 3.3 LiteRT ($10K Prize)

> "For the most compelling and effective use case built using Google AI Edge's LiteRT implementation of Gemma 4"

**What it is**: LiteRT is **Google's next-generation on-device ML framework**. Used by 100K+ applications, billions of users globally.

**Core Capabilities**:
- **Multi-framework support**: Convert PyTorch, JAX, TensorFlow models to LiteRT format
- **Hardware acceleration**: NPU, GPU, CPU delegates. Qualcomm NPU, MediaTek NPU, Apple Neural Engine
- **GenAI deployment**: LiteRT-LM for deploying language models on wearables, browsers, Pixel Watch
- **CompiledModel API**: Automated hardware selection + async execution
- **Cross-platform**: Android, Desktop, Web, IoT
- **Quantization toolkit**: Post-training quantization (INT8, FP16, dynamic range)

**Key differentiator**: It's Google's own framework. Using it signals alignment with Google's ecosystem — judges will appreciate this.

**Why Duchess fits**:
- We use LiteRT for YOLOv8-nano on glasses and Gemma 4 on phone
- Gemma 4 E2B on Pixel 9 Fold's Tensor G4 chip with NPU delegate = measurable speedup
- LiteRT-LM for on-device GenAI is exactly our use case
- Google AI Edge Gallery app showcases the same pattern we're building
- **Native integration**: LiteRT is our primary inference runtime across all on-device tiers

### 3.4 llama.cpp ($10K Prize)

> "For the best innovative implementation of Gemma 4 on resource-constrained hardware"

**What it is**: The **industry-standard C/C++ inference engine** for running LLMs locally. 101K GitHub stars, 1,571 contributors, 5,000+ releases. Created by Georgi Gerganov.

**Core Capabilities**:
- **Pure C/C++**: No dependencies. Runs anywhere.
- **Apple Silicon optimized**: ARM NEON, Accelerate, Metal frameworks
- **Quantization**: 1.5-bit to 8-bit integer, GGUF format
- **Backend support**: Metal, CUDA, Vulkan, SYCL, OpenCL, WebGPU, Hexagon (Snapdragon)
- **CPU+GPU hybrid**: Partially accelerate models larger than VRAM
- **OpenAI-compatible server**: Drop-in replacement API
- **Multimodal support**: Merged into llama-server
- **XCFramework**: Prebuilt for iOS, visionOS, tvOS, macOS

**Key stats**: LLM inference in C/C++. 101K stars. MIT license.

**Why Duchess fits**:
- **Resource-constrained hardware**: Our AR glasses have 6GB RAM, 750mAh battery, Snapdragon XR1
- **GGUF export**: Export Gemma 4 E2B to GGUF Q4_K_M (~1.4GB) → runs on phone via llama.cpp
- **Hexagon backend** (in progress): Direct Snapdragon optimization for our glasses chip
- **Demonstration**: Show Gemma 4 running on phones/glasses-class hardware via llama.cpp — the "resource-constrained" narrative is our strongest
- **OpenAI-compatible API**: Our phone app can switch between local llama.cpp server and cloud seamlessly

### 3.5 Ollama ($10K Prize)

> "For the best project that utilizes and showcases the capabilities of Gemma 4 running locally via Ollama"

**What it is**: The **simplest way to run LLMs locally**. One command: `ollama run gemma4:e2b`. Desktop app + CLI + API.

**Core Capabilities**:
- **One-line install**: `curl -fsSL https://ollama.com/install.sh | sh`
- **Model library**: 40,000+ integrations (Claude Code, Codex, LangChain, LlamaIndex, Open WebUI)
- **API server**: OpenAI-compatible REST API + SDK
- **Claude Code integration**: `ollama launch claude` directly
- **Model management**: Pull, run, customize, share models
- **Hardware**: Apple Silicon (Metal), NVIDIA CUDA, AMD ROCm

**Our current status**: ✅ Already verified. Ollama 0.20.0-rc0 installed. Gemma 4 E2B (7.2GB) and E4B (9.6GB) pulled and tested with construction safety prompts. Producing bilingual JSON output.

**Why Duchess fits**:
- Tier 3 (Mac server): Ollama runs Gemma 4 26B MoE for complex scene analysis
- Development workflow: Every developer uses Ollama locally for testing
- Demonstration: Show Ollama powering local inference during video demo
- Integration: Our phone app can call Ollama's REST API as a Tier 3 backend

---

## 4. Google Cloud Migration Strategy

### 4.1 Why Google Cloud (Not AWS)

The Gemma 4 Good Hackathon is **sponsored by Google**. Using Google Cloud:
1. Signals ecosystem alignment → judges appreciate Google Cloud usage
2. Unlocks **Vertex AI** — Google's native Gemma 4 hosting (better than SageMaker for Google models)
3. Potential hackathon credits from Google Cloud
4. Google Workspace + Google Sites integration for the demo page
5. Tighter integration with Google's CLI, SDK, and developer tooling

### 4.2 Service Migration Map

| AWS Service | Google Cloud Equivalent | Duchess Usage | Migration Effort |
|-------------|------------------------|---------------|-----------------|
| S3 | **Cloud Storage** (GCS) | Video storage, model artifacts, safety reports | Low — API is similar |
| DynamoDB | **Firestore** | Alert metadata, safety reports, device registry | Medium — document model differs |
| Lambda | **Cloud Run** or **Cloud Functions** | PPE escalation handler, API endpoints | Medium — container-based |
| SQS | **Pub/Sub** | Alert queue, batch processing triggers | Low — publish/subscribe pattern |
| Bedrock | **Vertex AI** | Gemma 4 31B Dense cloud inference, model hosting | High — different API |
| SageMaker | **Vertex AI Training** | Model fine-tuning, endpoint hosting | High — different SDK |
| IoT TwinMaker | **Cloud IoT Core** (deprecated) → **Pub/Sub + Firestore** | Digital twin (deferred) | N/A — not in scope |
| CloudWatch | **Cloud Logging + Monitoring** | Observability, alerting | Low |
| Secrets Manager | **Secret Manager** | API keys, tokens | Low |
| KMS | **Cloud KMS** | Encryption keys for video data | Low |
| SNS | **Pub/Sub** | Push notifications trigger | Low |
| CDK | **Terraform** or **gcloud CLI** | Infrastructure as Code | High — complete rewrite |

### 4.3 New Google Cloud Architecture

```
┌─────────────────────────────────────────────────────────────┐
│  Google Cloud Project: duchess-hackathon                     │
│                                                              │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐       │
│  │ Cloud Run    │  │ Vertex AI    │  │ Cloud        │       │
│  │ (API +       │  │ (Gemma 4 31B │  │ Storage      │       │
│  │  Escalation) │  │  inference)  │  │ (video, KMS) │       │
│  └──────┬───────┘  └──────┬───────┘  └──────────────┘       │
│         │                  │                                 │
│  ┌──────┴──────────────────┴──────────────────┐              │
│  │              Pub/Sub                        │              │
│  │  (alert-queue, batch-trigger, escalation)   │              │
│  └──────┬──────────────────────────────────────┘              │
│         │                                                    │
│  ┌──────┴───────┐  ┌──────────────┐  ┌──────────────┐       │
│  │ Firestore    │  │ Secret       │  │ Cloud        │       │
│  │ (alerts,     │  │ Manager      │  │ Monitoring   │       │
│  │  reports,    │  │              │  │              │       │
│  │  devices)    │  │              │  │              │       │
│  └──────────────┘  └──────────────┘  └──────────────┘       │
│                                                              │
│  ┌──────────────┐                                            │
│  │ Vertex AI    │                                            │
│  │ Training     │                                            │
│  │ (fine-tune   │                                            │
│  │  pipeline)   │                                            │
│  └──────────────┘                                            │
└─────────────────────────────────────────────────────────────┘
```

### 4.4 Google Workspace Integration

| Service | Usage |
|---------|-------|
| **Google Sites** | Public demo page with live infrastructure status |
| **Google Docs** | Kaggle writeup drafting, collaborative editing |
| **Google Sheets** | Benchmark tracking, dataset curation progress |
| **Google Drive** | Demo videos, media gallery staging |
| **Google Forms** | Beta tester feedback collection |

### 4.5 Google CLI Integration

```bash
# Project setup
gcloud projects create duchess-hackathon --name="Duchess Hackathon"
gcloud config set project duchess-hackathon

# Enable APIs
gcloud services enable \
  run.googleapis.com \
  aiplatform.googleapis.com \
  firestore.googleapis.com \
  pubsub.googleapis.com \
  storage.googleapis.com \
  secretmanager.googleapis.com \
  monitoring.googleapis.com

# Deploy Cloud Run service
gcloud run deploy duchess-api \
  --source=cloud/ \
  --region=us-central1 \
  --allow-unauthenticated=false

# Vertex AI Gemma 4 endpoint
gcloud ai endpoints create \
  --display-name=gemma4-31b-duchess \
  --region=us-central1
```

---

## 5. Rules Compliance Assessment

### 5.1 Full Rules Review

| Rule | Requirement | Duchess Status | Risk |
|------|-------------|---------------|------|
| Team size | Max 5 members | ✅ Need to register all team members | **ACTION: Register team on Kaggle** |
| Submissions | 1 per team | ✅ Will submit once | None |
| External data | Must be publicly available and free | ✅ All our datasets are open source (Roboflow CC BY 4.0, Kaggle public datasets) | None |
| Winner license | CC-BY 4.0 | ⚠️ Our code is Apache 2.0 — compatible but need to verify all deps | **ACTION: Audit all dependency licenses** |
| Code sharing | No private sharing outside team | ✅ GitHub repo is public | None |
| Open source code | OSI-approved license only | ✅ Apache 2.0, MIT | None |
| Eligibility | 18+, not from sanctioned countries, not Google employee | ✅ Verify all team members | **ACTION: Confirm eligibility** |
| Deliverables | Writeup ≤1,500 words, video ≤3 min, public repo, live demo, media, cover image | ❌ Not yet created | **See checklist** |
| Documentation | Must be reproducible from description | ⚠️ Need comprehensive README + setup docs | **ACTION: Ensure full repro steps** |
| Prize split | Even split unless unanimously agreed otherwise | ✅ Decide team split agreement up front | None |

### 5.2 Potential Rule Violations to Avoid

1. **No private code sharing**: Our GitHub repo must be public before submission. All code visible to everyone.
2. **External data must be free**: Construction-PPE datasets from Roboflow are CC BY 4.0. Kaggle datasets are public. ✅ Safe.
3. **No hand-labeling of test data**: We're fine — we're not predicting on competition test data (there is none).
4. **Commercially available software** (e.g., Gemma 4): No need to open-source Google's model weights — they're freely available. ✅ Safe.
5. **AMLT usage**: If using Unsloth or other automated tools, still must meet all rules. ✅ Fine.
6. **Incompatible license data/models**: Gemma 4 is Apache 2.0 ✅. If any training data has non-CC-BY license, we don't need to open-source that data, just document it.

### 5.3 Key Compliance Actions

- [ ] Register all team members on Kaggle (max 5)
- [ ] Verify every team member's eligibility (age 18+, not Google employee, not sanctioned country)
- [ ] Audit all dependency licenses for CC-BY 4.0 compatibility
- [ ] Make GitHub repo fully public with LICENSE file (Apache 2.0 for code, CC-BY 4.0 for submission content)
- [ ] Ensure all external datasets used are publicly available and free
- [ ] Prepare detailed reproducibility documentation (environment, dependencies, steps)
- [ ] Agree on prize split with all team members before submission

---

## 6. Free Compute & Cloud Credits

### 6.1 Kaggle Free Compute

Kaggle provides **free GPU and TPU** access for all users:

| Resource | Specifications | Weekly Quota | Use For |
|----------|---------------|-------------|---------|
| **CPU** | 4 cores, 30GB RAM, 12h sessions | Unlimited | Data preprocessing, dataset curation |
| **NVIDIA P100 GPU** | 16GB VRAM, 4 CPU cores, 29GB RAM | ~30 hours/week | Fine-tuning small models, inference demos |
| **NVIDIA T4 x2 GPU** | 2x 16GB VRAM, 4 CPU cores, 29GB RAM | ~30 hours/week | Larger fine-tuning jobs |
| **TPU v3-8** | 128GB HBM, 96 CPU cores, 330GB RAM, 9h sessions | ~20 hours/week | Distributed training (if supported) |
| **Disk** | 20GB saved output + scratchpad | Per session | Model checkpoints, training data |

**Colab Pro integration**: Kaggle users with Colab Pro get 15 extra GPU hours/week (Pro+: 30 hours/week).

### 6.2 Google Cloud Credits

| Source | Credits | How to Get | Status |
|--------|---------|------------|--------|
| **Google Cloud Free Trial** | $300 for 90 days | New GCP accounts | ✅ Available if creating new project |
| **Vertex AI free tier** | Gemma 4 predictions free for limited volume | Built into Vertex AI pricing | Check limits |
| **Google for Startups** | Up to $100K in credits | Apply to program | Longer timeline than hackathon |
| **Kaggle + GCP integration** | Direct upload from Kaggle to GCS/BigQuery | Built into Kaggle notebooks | ✅ Available |

### 6.3 Compute Strategy

| Workload | Platform | Cost |
|----------|----------|------|
| Gemma 4 E2B fine-tuning (QLoRA) | Kaggle T4 x2 GPU | **Free** |
| Dataset preprocessing | Kaggle CPU | **Free** |
| Inference demos / benchmarks | Local M4 Max (48GB) + Ollama | **Free** |
| Unsloth training | Local RTX 5090 (64GB VRAM) | **Free** (owned hardware) |
| Cloud inference (Gemma 4 31B) | Vertex AI or GCP $300 credits | **Free** (within trial) |
| Notebooks for submission | Kaggle notebooks | **Free** |

**Total estimated cost: $0** using existing hardware + Kaggle free tier + GCP trial credits.

---

## 7. Datasets for Fine-Tuning

### 7.1 Construction PPE Detection (Vision)

| Dataset | Source | Size | Classes | License | Use |
|---------|--------|------|---------|---------|-----|
| **Construction Site Safety (Roboflow)** | [Kaggle](https://www.kaggle.com/datasets/snehilsanyal/construction-site-safety-image-dataset-roboflow) | 227 MB, 5,641 files | 10 (Hardhat, Mask, NO-Hardhat, NO-Mask, NO-Safety Vest, Person, Safety Cone, Safety Vest, machinery, vehicle) | CC BY 4.0 ✅ | Primary PPE detection training |
| **Safety Helmet Detection** | [Kaggle (Larxel)](https://www.kaggle.com/datasets/andrewmvd/hard-hat-detection) | 1 GB, 27.6K downloads | Hard hat detection | Public | Helmet-specific fine-tuning |
| **HardHat-Vest Dataset v3** | [Kaggle](https://www.kaggle.com/datasets/muhammetzahitaydn/hardhat-vest-dataset-v3) | 5 GB, 44K files | Hardhat + vest | Public | Large-scale PPE training |
| **Safety Helmet and Reflective Jacket** | [Kaggle](https://www.kaggle.com/datasets/niravnaik/safety-helmet-and-reflective-jacket) | 543 MB, 21K files | Helmet + jacket | Public | Additional PPE variety |
| **PPE Dataset YOLOv8** | [Kaggle](https://www.kaggle.com/datasets/shlokraval/ppe-dataset-yolov8) | — | YOLOv8 format PPE | Public | Pre-formatted for YOLO |

### 7.2 Spanish / Bilingual (NLU)

| Dataset | Source | Size | Description | License | Use |
|---------|--------|------|-------------|---------|-----|
| **OSHA Safety Standards (Spanish)** | OSHA.gov | ~100 pages | Official OSHA safety materials in Spanish | Public domain (US Gov) | Safety terminology extraction |
| **Construction Jargon Corpus** | Custom scrape (public forums, OSHA) | ~10K sentences | EN/ES construction terminology pairs | Fair use | Bilingual adapter training |
| **CoVoST2** | Meta / HuggingFace | Audio + transcripts | Multilingual speech-to-text including ES | CC0 | Audio input validation |
| **FLEURS** | Google | Audio + transcripts | 102 languages including Spanish variants | CC BY 4.0 | Multilingual audio eval |

### 7.3 General Safety & Construction

| Dataset | Source | Description | Use |
|---------|--------|-------------|-----|
| **iSafetyBench** | Academic | Construction safety benchmark | Model evaluation |
| **MOCS (Monitoring of Construction Sites)** | Academic | Multi-class construction monitoring | Scene understanding |
| **SH17 (Safety Helmet)** | Academic | 17-class safety equipment detection | Extended PPE classes |
| **OSHA Incident Reports** | OSHA.gov | Text descriptions of construction incidents | Safety NLU training |

### 7.4 Fine-Tuning Plan

```
Phase 1: Text Adapter (safety)
  Data: OSHA reports + construction safety text + incident descriptions
  Model: Gemma 4 E2B
  Method: Unsloth QLoRA (r=16, alpha=32)
  Target: Safety classification accuracy ≥90%

Phase 2: Vision Adapter (safety_vision)
  Data: Roboflow Construction Site Safety + HardHat-Vest + Safety Helmet
  Model: Gemma 4 E2B (vision)
  Method: Unsloth QLoRA with vision layers
  Target: PPE detection F1 ≥ 0.85

Phase 3: Bilingual Adapter (spanish_jargon)
  Data: OSHA Spanish materials + construction jargon corpus
  Model: Gemma 4 E2B
  Method: Unsloth QLoRA
  Target: ES BLEU ≥ 30, construction terminology accuracy ≥ 95%
```

---

## 8. Benchmark Analysis & Model Arena

### 8.1 Where Gemma 4 Leads

Based on the Gemma 4 model card benchmarks:

| Benchmark | Category | Gemma 4 31B | Gemma 4 26B MoE | Gemma 4 E4B | Gemma 4 E2B | Key Competitor | Notes |
|-----------|----------|-------------|-----------------|-------------|-------------|----------------|-------|
| **MMLU Pro** | General knowledge | 85.2% | 82.6% | 69.4% | 60.0% | GPT-4o: ~85% | Competitive at 31B, strong for size at E2B |
| **MMMLU** | Multilingual | **88.4%** | **86.3%** | **76.6%** | **70.7%** | GPT-4o: ~85% | **INDUSTRY-LEADING** multilingual. Key for our bilingual story |
| **MMMU Pro** | Multimodal understanding | 76.9% | 73.8% | 52.6% | 49.7% | GPT-4o: ~65% | **Strong at 31B** |
| **MATH-Vision** | Visual math | **85.6%** | **82.4%** | 59.5% | 46.0% | GPT-4o: ~55% | **World-class** at 31B |
| **AIME 2026** | Math reasoning | **89.2%** | **88.3%** | 42.5% | 37.5% | Claude: ~80% | Exceptional at larger sizes |
| **LiveCodeBench v6** | Code generation | 80.0% | 77.1% | 52.0% | 44.0% | DeepSeek V3: ~75% | Competitive |
| **GPQA Diamond** | Graduate-level QA | **84.3%** | **82.3%** | 58.6% | 43.4% | GPT-4o: ~70% | Significant lead at 31B |
| **CoVoST** | Audio (speech translation) | — | — | **35.54** | **33.47** | Whisper: ~33 | **Edge model with AUDIO** — unique |
| **FLEURS** | Audio (ASR) | — | — | **0.08** | **0.09** | Whisper: ~0.1 | Near-perfect WER |

### 8.2 Where Gemma 4 is Strongest (Our Narrative)

1. **Multilingual (MMMLU 88.4%)**: Industry-leading. Our bilingual EN/ES story is backed by the best multilingual model.
2. **Visual understanding (MMMU Pro 76.9%)**: Critical for PPE detection from camera frames.
3. **Reasoning at scale (GPQA Diamond 84.3%)**: Complex safety scene analysis.
4. **Audio on edge (CoVoST/FLEURS)**: Only frontier model family with native audio on 2.3B params. Voice safety reporting.
5. **Thinking mode**: Auditable reasoning chains for safety decisions.

### 8.3 Where We Can Improve (Model Release Opportunity)

| Gap Area | Gemma 4 E2B Score | Target After Fine-Tuning | Method |
|----------|------------------|-------------------------|--------|
| Construction PPE detection | ~50% (zero-shot) | ≥85% F1 | Unsloth QLoRA safety_vision adapter |
| Construction safety classification | ~60% (zero-shot) | ≥90% accuracy | Unsloth QLoRA safety adapter |
| Spanish construction terminology | ~70% BLEU | ≥85% BLEU | Unsloth QLoRA spanish_jargon adapter |
| On-device latency (phone) | ~3-4s | <2s | LiteRT NPU delegate + quantization |

### 8.4 Arena Rankings

Based on publicly available Arena.ai data and Gemma 4 model card claims:
- **Gemma 4 31B Dense**: Claimed #3 open model on Arena
- **Gemma 4 26B A4B MoE**: Claimed #6 on Arena
- Both achieve "frontier-level" performance with open weights

### 8.5 Benchmark Categories We Should Publish

To maximize academic impact and judge impressions, publish results on:

| Benchmark Suite | Category | Priority | Why |
|----------------|----------|----------|-----|
| **Construction-PPE F1** | Domain-specific | ★★★★★ | Directly measures our core use case |
| **Bilingual BLEU (EN→ES)** | Translation quality | ★★★★★ | Measures our bilingual output quality |
| **Latency (tokens/sec on Pixel 9)** | Performance | ★★★★★ | Proves on-device viability |
| **iSafetyBench** | Safety classification | ★★★★☆ | Academic benchmark, paper-worthy |
| **Memory usage (MB)** | Efficiency | ★★★★☆ | Proves edge deployment feasibility |
| **MMMU (construction subset)** | Multimodal | ★★★☆☆ | If we can isolate construction-relevant items |

---

## 9. Model Release Strategy

### 9.1 Models to Publish

| Model | Format | Platform | Target |
|-------|--------|----------|--------|
| `duchess/gemma-4-e2b-safety` | Safetensors + GGUF | Hugging Face + Kaggle Models | Unsloth Prize |
| `duchess/gemma-4-e2b-safety-vision` | Safetensors + GGUF | Hugging Face + Kaggle Models | Unsloth + LiteRT Prize |
| `duchess/gemma-4-e2b-bilingual-safety` | Safetensors + GGUF | Hugging Face + Kaggle Models | Digital Equity Prize |
| `duchess/yolov8n-construction-ppe` | LiteRT | Hugging Face + Kaggle Models | LiteRT Prize |

### 9.2 Publication Checklist

Each published model must include:
- [ ] Model card with architecture, training details, hyperparameters
- [ ] Benchmark results table (before/after fine-tuning)
- [ ] Training code (link to GitHub)
- [ ] Inference notebook (Kaggle + Colab)
- [ ] Example outputs (safety assessment, bilingual alerts)
- [ ] License (Apache 2.0 for code, model weights under Gemma license)
- [ ] Dataset references with proper citations

### 9.3 Achieving High Benchmark Scores

**Strategy**: Focus on domain-specific benchmarks where fine-tuning has the highest ROI:

1. **Construction-PPE F1**: Fine-tune Gemma 4 E2B vision on 5,641 labeled Roboflow images + 44K HardHat-Vest images. Expect 85-92% F1 (up from ~50% zero-shot).
2. **Bilingual BLEU**: Fine-tune on OSHA Spanish materials + construction jargon corpus. Expect 80-90% BLEU (up from ~70% zero-shot).
3. **Safety Classification**: Fine-tune on OSHA incident reports + iSafetyBench data. Expect 90-95% accuracy (up from ~60% zero-shot).
4. **Latency**: Quantize to INT4 via GGUF, deploy with LiteRT NPU. Target <2s on Pixel 9 Fold.

---

## 10. Prize Pool Gamification Matrix

### 10.1 Maximum Theoretical Earnings

Per competition rules: "Projects are eligible to win both a Main Track Prize and a Special Technology Prize." Impact Track is separate.

| Track | Prize | Achievable? | Earning |
|-------|-------|-------------|---------|
| Main Track (1st) | $50,000 | Target | $50,000 |
| Safety & Trust | $10,000 | Very likely | $10,000 |
| Digital Equity & Inclusivity | $10,000 | Very likely | $10,000 |
| Global Resilience | $10,000 | Likely | $10,000 |
| Cactus | $10,000 | Very likely | $10,000 |
| Unsloth | $10,000 | Very likely | $10,000 |
| LiteRT | $10,000 | Likely | $10,000 |
| llama.cpp | $10,000 | Possible | $10,000 |
| Ollama | $10,000 | Possible | $10,000 |
| Future of Education | $10,000 | Stretch | $10,000 |
| **TOTAL (max theoretical)** | | | **$140,000** |

### 10.2 Realistic Target: $80,000-$100,000

| Tier | Prizes | Total |
|------|--------|-------|
| **Tier A (Primary)** | Main Track 1st ($50K) + Safety & Trust ($10K) + Cactus ($10K) + Unsloth ($10K) | $80,000 |
| **Tier B (Secondary)** | + Digital Equity ($10K) + LiteRT ($10K) | +$20,000 |
| **Tier C (Bonus)** | + Global Resilience ($10K) + llama.cpp ($10K) + Ollama ($10K) | +$30,000 |

### 10.3 Feature → Prize Mapping

Every feature we build should map to at least 2 prizes:

| Feature | Prizes Targeted | Effort |
|---------|----------------|--------|
| Gemma 4 E2B on-device inference | Main + Cactus + LiteRT | P0 |
| Bilingual EN/ES alerts | Main + Digital Equity + Safety & Trust | P0 |
| Unsloth fine-tuned safety model | Main + Unsloth | P0 |
| Cactus SDK multi-tier routing | Main + Cactus | P1 |
| LiteRT NPU deployment | Main + LiteRT | P1 |
| GGUF export + llama.cpp demo | Main + llama.cpp | P1 |
| Ollama Tier 3 inference | Main + Ollama | P2 |
| Offline mesh demo | Main + Global Resilience | P2 |
| Safety toolbox talk generator | Main + Future of Education | P2 |
| Voice hazard reporting (audio) | Main + Digital Equity + Safety & Trust | P1 |

---

## 11. Revised Timeline (Internal Deadline: May 7)

**Real deadline**: May 18, 2026 11:59 PM UTC  
**Internal deadline**: May 7, 2026 (11 days buffer for polish, video editing, unexpected issues)

### Week 1: Apr 2-8 — Foundation ✅ (Partially Complete)

- [x] Gemma 4 research and model card analysis
- [x] Hackathon strategy document
- [x] Codebase migration Gemma 3n → 4
- [x] Local Ollama inference verified (E2B + E4B)
- [ ] **NEW**: Create Google Cloud project `duchess-hackathon`
- [ ] **NEW**: Register team on Kaggle (up to 5 members)
- [ ] **NEW**: Install Google Cloud CLI / SDK
- [ ] Start Cactus SDK research + prototype integration

### Week 2: Apr 9-15 — Core Model Integration

- [ ] Gemma 4 E2B integration in phone app via LiteRT
- [ ] Cactus SDK integration for multi-tier routing
- [ ] Multimodal vision input (camera frames → Gemma 4)
- [ ] Native function calling for structured safety output
- [ ] Google Cloud: Deploy Cloud Run API skeleton
- [ ] Google Cloud: Set up Firestore schema

### Week 3: Apr 16-22 — Fine-Tuning Sprint

- [ ] Unsloth: safety text adapter (QLoRA)
- [ ] Unsloth: safety_vision adapter (multimodal)
- [ ] Unsloth: spanish_jargon adapter
- [ ] Export all adapters to GGUF + SafeTensors
- [ ] Zero-shot vs fine-tuned benchmarks
- [ ] Publish models to Hugging Face

### Week 4: Apr 23-29 — End-to-End Integration

- [ ] Full pipeline: glasses → phone → cloud with Cactus routing
- [ ] Bilingual voice input demo (Gemma 4 audio)
- [ ] llama.cpp on edge hardware demo
- [ ] Ollama on Mac server demo (Tier 3)
- [ ] Google Cloud: Vertex AI Gemma 4 31B endpoint
- [ ] LiteRT migration + NPU benchmarks

### Week 5: Apr 30-May 7 — INTERNAL DEADLINE

- [ ] **May 1-3**: Record video demo on construction site
- [ ] **May 3-5**: Edit video (≤3 min)
- [ ] **May 5-6**: Write Kaggle writeup (≤1,500 words)
- [ ] **May 6-7**: Create architecture diagrams, cover image, media gallery
- [ ] **May 7**: **INTERNAL DEADLINE — ALL DELIVERABLES COMPLETE**

### Week 6: May 8-14 — Buffer & Polish

- [ ] Review and polish video
- [ ] Review and polish writeup
- [ ] Clean up GitHub repo (README, docs, LICENSE)
- [ ] Test live demo URL
- [ ] Cross-check against ALL track requirements
- [ ] Peer review from all team members

### Week 7: May 15-18 — Submission

- [ ] **May 15**: Final video upload to YouTube
- [ ] **May 16**: Submit Kaggle Writeup
- [ ] **May 17**: Verify all links work (video, code, demo, models)
- [ ] **May 18**: **SUBMIT before 11:59 PM UTC**

---

## 12. Production Scaling Strategy

### 12.1 Demo Page Scaling

The demo page must handle traffic from all hackathon participants and judges simultaneously.

**Architecture**:
```
CloudFlare CDN
    ↓
Google Cloud Run (auto-scaling, min 0, max 10 instances)
    ↓
Static assets on Cloud Storage (CDN-backed)
    ↓
Firestore (auto-scaling NoSQL)
```

**Key decisions**:
- **Static first**: Generate demo page as static HTML/CSS/JS → serve from Cloud Storage + CDN
- **Google Sites**: For the "business page" — automatically scaled by Google, zero ops
- **Cloud Run**: Only for API endpoints (safety assessment API, model inference)
- **Autoscaling**: Cloud Run scales to 0 when idle, scales up to 10 instances under load
- **CDN**: CloudFlare or Cloud CDN in front of all static assets
- **Pre-recorded demos**: Video demos are on YouTube (infinite scale). Don't rely on live inference for the demo page.

### 12.2 Cost Containment

- Cloud Run: Pay per request (scale to 0 when not in use)
- Cloud Storage: ~$0.02/GB/month
- Vertex AI: Use $300 free trial credits
- YouTube: Free video hosting with unlimited viewers
- Google Sites: Free

### 12.3 Demo Page Content Strategy

| Page Section | Content | Hosting |
|-------------|---------|---------|
| Landing / Hero | Project overview, video embed | Google Sites |
| Live Demo | Pre-recorded inference GIFs, interactive safety assessment form | Cloud Run + Cloud Storage |
| Architecture | Interactive diagram, tier breakdown | Static HTML/JS |
| Models | Links to Hugging Face model cards, benchmark charts | Google Sites |
| Team | Team bios, photos | Google Sites |
| GitHub | Link to public repo | GitHub |

---

## Appendix A: Key URLs

| Resource | URL |
|----------|-----|
| Competition Page | https://www.kaggle.com/competitions/gemma-4-good-hackathon |
| Competition Rules | https://www.kaggle.com/competitions/gemma-4-good-hackathon/rules |
| Gemma 4 Model Card | https://ai.google.dev/gemma/docs/model-card-gemma4 |
| Unsloth | https://unsloth.ai/ |
| Cactus | https://cactuscompute.com/ |
| LiteRT | https://ai.google.dev/edge/litert |
| llama.cpp | https://github.com/ggml-org/llama.cpp |
| Ollama | https://ollama.com/ |
| Omar Sanseviero | https://osanseviero.github.io/hackerllama/ |
| Gusthema (Kaggle) | https://www.kaggle.com/gusthema |
| Roboflow PPE Dataset | https://www.kaggle.com/datasets/snehilsanyal/construction-site-safety-image-dataset-roboflow |
| Kaggle Free GPU Docs | https://www.kaggle.com/docs/notebooks |
| Google Cloud Free Trial | https://cloud.google.com/free |

## Appendix B: Judge Appeal Checklist

Before submission, verify each judge's likely priorities are addressed:

- [ ] **Gusthema**: Gemma 4 features used creatively? Notebooks reproducible? Unsloth integration showcased?
- [ ] **Omar Sanseviero**: Spanish/bilingual story prominent? Open model published? Developer experience clean?
- [ ] **Glenn Cameron + Kristen Quan**: Competition deliverables complete? All links working? Video compelling?
- [ ] **All judges**: Impact & Vision (40pts) story is clear? Video is professional (30pts)? Technical depth demonstrated (30pts)?

---

_Duke — April 2, 2026_
_"We don't just build technology. We build it so every worker goes home safe — en inglés y en español."_
_¡A ganar! 🏗️_
