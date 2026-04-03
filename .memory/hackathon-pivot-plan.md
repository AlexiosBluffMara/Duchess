# Hackathon Pivot Plan — Gemma 4 Good (April 3, 2026)

_This document is the single source of truth for all development changes needed to
pivot Duchess for the Gemma 4 Good Hackathon. GitHub Copilot: read this before
touching ANY code. Claude Code: write this before touching any code._

---

## Architecture Pivot: 4-Tier → 3-Tier

### BEFORE (original)
```
Tier 1: AR Glasses (Ray-Ban Meta)          → YOLOv8-nano      (<50ms)
Tier 2: Pixel 9 Fold                       → Gemma 4 E2B      (<2s)
Tier 3: M4 Max MacBook / RTX 5090 (local)  → Gemma 4 26B MoE  (<5s)     ← REMOVED
Tier 4: AWS Cloud (SageMaker / Bedrock)    → Gemma 4 31B      (100-500ms) ← NOW GOOGLE CLOUD
```

### AFTER (hackathon)
```
Tier 1: AR Glasses (Vuzix M400 + Oakley/Ray-Ban)  → YOLOv8-nano      (<50ms)
Tier 2: Pixel 9 Fold (ON-DEVICE INFERENCE STATION) → Gemma 4 E2B     (<2s, $0/inference)
Tier 3: Google Cloud (Vertex AI)                   → Gemma 4 31B     (100-500ms)
```

### Why
- Tier 3 (local server) does not scale to a full jobsite deployment
- Google Cloud (Vertex AI) is the natural escalation path for the Gemma ecosystem
- Hackathon judges want to see Google services used cohesively
- 3-tier is simpler to demo, simpler to explain, and more commercially credible

---

## Hardware: Two Paths of Glasses

| Path | Glasses | Status | SDK | Notes |
|------|---------|--------|-----|-------|
| **A** | Vuzix M400 | TO BE ACQUIRED (faculty meeting tomorrow) | Vuzix SDK, Camera2, LiteRT | On-device YOLOv8-nano PPE detection |
| **B** | Oakley/Ray-Ban (Meta Wayfarer for prototyping) | IN HAND | Meta DAT SDK v0.5.0 | Camera stream via BLE to phone |

**For hackathon prototyping**: Path B (Ray-Ban Wayfarers) is primary.
**For production vision**: Both paths are supported in the architecture.

---

## Google Cloud Services Plan

### Tier 3 — Cloud Inference & Processing

| Service | Purpose | Replaces |
|---------|---------|----------|
| **Vertex AI** | Host Gemma 4 31B for escalated inference, batch safety analysis | AWS SageMaker / Bedrock |
| **Cloud Run** | Serverless API for escalation pipeline (receives alerts from phone) | AWS Lambda |
| **Cloud Storage (GCS)** | Video storage with encryption + lifecycle (replaces S3) | AWS S3 |
| **Firestore** | Alert database with real-time sync to phone apps | AWS DynamoDB |
| **Pub/Sub** | Escalation queue (phone → cloud, cloud → supervisor) | AWS SQS |
| **Firebase Auth** | User authentication + device registration | Custom auth |
| **Firebase Cloud Messaging** | Push notifications for safety alerts | SNS |
| **Secret Manager** | API keys, credentials, model configs | AWS SSM |
| **Artifact Registry** | Docker images for Cloud Run, APK builds | ECR |
| **Cloud Build** | CI/CD for cloud services | GitHub Actions (supplement) |

### Vertex AI Gemma 4 Deployment

```
1. Model Garden → Deploy Gemma 4 31B to a Vertex AI Endpoint
2. Machine type: g2-standard-48 (NVIDIA L4 x4) for production
   OR n1-standard-8 + T4 for hackathon demo budget
3. Use Vertex AI Prediction API for real-time inference
4. Use Vertex AI Batch Prediction for nightly shift analysis
5. Model monitoring via Vertex AI Model Monitoring
```

### Cost Estimate (Hackathon Demo Budget)
- Vertex AI Endpoint (T4, on-demand): ~$0.35/hr = ~$252/month
- Cloud Run: Free tier covers demo traffic
- Firestore: Free tier (50K reads/day)
- GCS: ~$0.02/GB/month
- **Total hackathon demo: ~$50-100 for the entire competition period**

---

## Gemma 4 On-Device Capabilities (Deep Dive)

### What Gemma 4 E2B Can Do Autonomously on Android

1. **Multimodal Vision Analysis**
   - Process camera frames at 640x640 via MediaPipe LlmInference
   - Detect PPE violations directly from video (hard hat, vest, glasses, gloves)
   - Variable resolution: adaptive visual token budget based on scene complexity
   - No separate CV pipeline needed — the LLM IS the vision pipeline

2. **Native Function Calling / Tool Use**
   - Gemma 4 supports structured tool use (function calling)
   - On-device: model calls predefined functions with typed parameters
   - Example: `create_safety_alert(type="NO_HARD_HAT", severity=4, zone="B")`
   - Eliminates JSON parsing fragility — model produces structured output natively
   - Can trigger Android intents, write to Room DB, send BLE messages

3. **Audio Input Processing**
   - E2B processes raw audio natively — no separate STT pipeline
   - Workers report hazards by voice in any language
   - "¡Falta casco en la zona norte!" → structured SafetyAlert
   - Works offline — no cloud STT dependency

4. **Thinking Mode (Extended Reasoning)**
   - Auditable chain-of-thought for compliance/legal
   - "Worker at height without harness. OSHA 1926.502 requires fall protection >6ft. Severity: HIGH."
   - Can be logged for OSHA audits without storing video

5. **System Prompts / Persona**
   - Domain-specialized construction safety instructions
   - Bilingual output formatting (EN + ES in single pass)
   - OSHA regulation knowledge embedded in prompt

6. **128K Context Window**
   - Multi-frame temporal reasoning across video sequences
   - Aggregate multiple camera frames before making a decision
   - Track worker behavior patterns over time (without PII)

7. **On-Device Actions Gemma 4 Can Trigger**
   - Write SafetyAlert to local Firestore cache
   - Send BLE alert to glasses HUD
   - Queue escalation to Vertex AI via Cloud Run
   - Start/stop camera recording
   - Adjust inference frequency based on scene danger level
   - Generate bilingual toolbox talk scripts
   - Log anonymized metrics for supervisor dashboard

---

## Quantization & ML Research Agenda

### For ML Professors — Unsloth, TurboQuant, 1-Bit Ternary, Prism

#### 1. Unsloth Dynamic QLoRA (PRIMARY — $10K Prize)
- Fine-tune Gemma 4 E2B on construction safety vision dataset
- Target adapters: safety_vision, spanish_jargon, osha_regulations
- Publish weights + benchmarks to HuggingFace
- Key metrics: accuracy vs base model, inference latency, memory usage
- Unsloth 2x faster than HF PEFT, 60% less memory

#### 2. TurboQuant / Advanced Quantization
- GPTQ + AWQ quantization of Gemma 4 for on-device deployment
- Compare INT8 vs INT4 vs FP16 on Tensor G4 NPU
- LiteRT CompiledModel API for hardware-specific optimization
- Benchmark suite: latency, accuracy, memory, thermal throttling

#### 3. 1-Bit Ternary Optimization (BitNet b1.58)
- Research angle: Can Gemma 4 E2B survive 1.58-bit quantization?
- BitNet approach: weights ∈ {-1, 0, +1}
- Potential 10x memory reduction + 8x speed on CPU
- If viable: run Gemma 4 on Vuzix M400 (6GB RAM) directly
- Reference: "The Era of 1-bit LLMs" (Ma et al., 2024)

#### 4. Prism 1 / PrismQuant
- Mixed-precision quantization: different bit-widths per layer
- Attention layers at FP16, FFN at INT4, embeddings at INT8
- Optimized for MoE architectures (Gemma 4 IS MoE)
- Key insight: expert routing can tolerate lower precision than attention

#### 5. Research Deliverables
- Ablation study across quantization methods on Gemma 4 E2B
- Before/after accuracy on construction safety benchmark
- Latency comparison on Tensor G4 vs Snapdragon XR1
- Published model weights on HuggingFace with benchmark cards
- Paper-ready results for potential CVPR/CHI/UbiComp submission

---

## Dev Changes Required (Ordered)

### Phase 0: Planning Artifacts (THIS SESSION — NO CODE CHANGES)
- [x] Write this plan document
- [ ] Update docs/index.html — 3-tier architecture, Google Cloud, two glass paths
- [ ] Update README.md — same changes
- [ ] Update HACKATHON_STRATEGY.md — remove Tier 3, add Vertex AI details
- [ ] Remove Tribe v2 references from docs/technical/meta-google-stack.md
- [ ] Add critical review comments (TODO-PRINCIPAL, TODO-ML-PROF) across codebase
- [ ] Update .memory/decisions.md with new ADRs
- [ ] Commit: `docs(shared): hackathon pivot — 3-tier arch, Vertex AI, dual-glass support`

### Phase 1: Cloud Migration (AWS → Google Cloud)
- [ ] Create `cloud-gcp/` directory (parallel to existing `cloud/` AWS CDK)
- [ ] `cloud-gcp/main.tf` — Terraform for Vertex AI endpoint + Cloud Run + GCS + Firestore
- [ ] `cloud-gcp/vertex_ai/deploy_gemma4.py` — Script to deploy Gemma 4 31B to Vertex AI
- [ ] `cloud-gcp/cloud_run/escalation/` — Cloud Run service for PPE escalation
- [ ] `cloud-gcp/firestore/schema.md` — Alert collection schema
- [ ] Update `app-phone/` to use Firebase + Firestore instead of AWS DynamoDB
- [ ] Deprecate (but don't delete) existing `cloud/` AWS CDK stack

### Phase 2: Gemma 4 On-Device Enhancement
- [ ] Implement native function calling in GemmaInferenceEngine.kt
- [ ] Add audio input pipeline (Gemma 4 E2B audio modality)
- [ ] Implement thinking mode for auditable reasoning
- [ ] Add on-device tool definitions for safety assessment actions
- [ ] StreamScreen.kt — expose inference toggle + zone picker UI
- [ ] GemmaInferenceService lifecycle management (start/stop with foreground)

### Phase 3: Dual Glass Hardware Support
- [ ] Ensure `app-glasses/` builds for both Vuzix M400 and generic AOSP
- [ ] Add build flavors: `vuzixM400` and `genericAosp` (for Oakley-compatible)
- [ ] Abstract camera interface: Camera2 (M400) vs DAT SDK (Meta Ray-Ban)
- [ ] BLE protocol: unified alert format for both glass types

### Phase 4: Unsloth Fine-Tuning Pipeline
- [ ] Update `ml/scripts/train_gemma4.py` for Gemma 4 E2B vision fine-tuning
- [ ] Curate construction safety vision dataset (PPE images + labels)
- [ ] Train safety_vision adapter with Unsloth Dynamic QLoRA
- [ ] Train spanish_jargon adapter
- [ ] Export to GGUF + LiteRT for on-device deployment
- [ ] Benchmark: base vs fine-tuned accuracy on safety tasks
- [ ] Publish weights to HuggingFace

### Phase 5: Quantization Research
- [ ] INT8/INT4 quantization comparison on Tensor G4
- [ ] 1-bit ternary (BitNet b1.58) experiment on Gemma 4 E2B
- [ ] Mixed-precision (Prism-style) experiment
- [ ] LiteRT CompiledModel benchmarks
- [ ] Write benchmark report with charts

### Phase 6: Demo & Submission
- [ ] Video demo script + filming (construction site access needed)
- [ ] Kaggle writeup (≤1,500 words)
- [ ] Live demo URL (Cloud Run hosted dashboard)
- [ ] Cover image + media gallery
- [ ] Final code cleanup + documentation

---

## Prize Optimization Strategy

### PRIMARY TARGET (go deep, not wide)
**Main Track ($50K)** — Full end-to-end demo is the differentiator.
- Live glasses → phone → cloud pipeline
- Bilingual alerts in real-time
- Video demo with construction site footage

### SECONDARY TARGETS (natural fit, minimal extra work)
1. **Safety & Trust ($10K)** — Our core use case IS safety
2. **Digital Equity ($10K)** — Bilingual is our DNA
3. **Unsloth ($10K)** — Fine-tuning pipeline is Phase 4

### OPPORTUNISTIC (if time permits)
4. **LiteRT ($10K)** — Already using it; need benchmarks
5. **Cactus ($10K)** — Multi-tier routing is our architecture
6. **Global Resilience ($10K)** — Offline-capable by design

### DO NOT TARGET (weak fit, dilutes narrative)
- Health & Sciences — not our story
- Future of Education — stretch
- llama.cpp — conflicts with Google-centric narrative for this hackathon
- Ollama — same conflict

---

## Key Decisions Made Today

1. **DROP Tier 3 (local server)** — doesn't scale, adds complexity for no hackathon value
2. **AWS → Google Cloud** — Vertex AI for Gemma 4, full Google ecosystem alignment
3. **Two glass paths** — Vuzix M400 (to acquire) + Oakley/Ray-Ban (prototyping with Meta Wayfarers)
4. **Tighter focus** — Main Track + Safety + Equity + Unsloth > spreading across all 10 prizes
5. **Gemma 4 everywhere** — On-device E2B for phone, 31B on Vertex AI for cloud. Same model family top to bottom.
6. **Google services maximized** — Vertex AI, Firebase, Cloud Run, Firestore, GCS, FCM, Secret Manager

---

_Written by Claude Code session, April 3, 2026_
_For GitHub Copilot: Read this document completely before making any code changes._
_For human team: Review and confirm before proceeding with Phase 1+._
