# Gemma 4: Technical Review & Feasibility Analysis for Duchess

> **Prepared by**: Duke Martinez, Project Coordinator  
> **With contributions from**: Alex Torres (Android), Priya Sharma (ML), Kai Nakamura (Edge AI), Elena Vasquez (CV), Noah Kim (Networking), Luis Garcia (Localization)  
> **Date**: April 2, 2026  
> **Context**: Evaluation of Google DeepMind's Gemma 4 open model family for the Duchess Construction Site Intelligence Platform

---

## 1. Model Family Overview

Gemma 4 is Google DeepMind's latest open model family, built from Gemini 3 research. Released April 2, 2026 under the **Apache 2.0 license** (a major upgrade from Gemma 3n's more restrictive license), it is commercially permissive with no usage restrictions.

### Model Sizes

| Model | Total Params | Effective/Active | Architecture | Context | Modalities | Target Hardware |
|-------|-------------|-----------------|--------------|---------|------------|-----------------|
| **E2B** | 5.1B (w/ embeddings) | **2.3B effective** | Dense + PLE | 128K | Text, Vision, Audio | Phones, IoT, Raspberry Pi |
| **E4B** | 8B (w/ embeddings) | **4.5B effective** | Dense + PLE | 128K | Text, Vision, Audio | Phones, laptops |
| **26B A4B** | 25.2B | **3.8B active** | MoE (8/128 experts + 1 shared) | 256K | Text, Vision | Consumer GPUs, workstations |
| **31B** | 30.7B | **30.7B** | Dense | 256K | Text, Vision | Workstations, servers |

### Key Architectural Innovations

1. **Per-Layer Embeddings (PLE)**: E2B and E4B use PLE — each decoder layer gets its own small embedding table for every token. These are large in parameter count but only used for quick lookups, making the *effective* compute cost much lower than the total parameter count.

2. **Hybrid Attention**: All models interleave local sliding window attention (512-1024 tokens) with full global attention. The final layer is always global. This delivers lightweight-model speed with deep-context awareness.

3. **Proportional RoPE (p-RoPE)**: Global layers use unified Keys and Values with p-RoPE for memory-efficient long-context processing.

4. **MoE Efficiency (26B)**: Activates only 3.8B of 25.2B parameters per forward pass (8 of 128 experts + 1 shared expert), running nearly as fast as a 4B model while maintaining 26B-level quality.

---

## 2. Benchmark Performance

### Text & Reasoning

| Benchmark | 31B | 26B A4B | E4B | E2B | Significance for Duchess |
|-----------|-----|---------|-----|-----|-------------------------|
| MMLU Pro | 85.2% | 82.6% | 69.4% | 60.0% | General knowledge for safety reasoning |
| AIME 2026 (no tools) | 89.2% | 88.3% | 42.5% | 37.5% | Complex multi-step reasoning |
| LiveCodeBench v6 | 80.0% | 77.1% | 52.0% | 44.0% | Code generation for agentic workflows |
| GPQA Diamond | 84.3% | 82.3% | 58.6% | 43.4% | Graduate-level domain reasoning |
| MMMLU (multilingual) | 88.4% | 86.3% | 76.6% | 70.7% | **Critical** — bilingual EN/ES capability |

### Vision

| Benchmark | 31B | 26B A4B | E4B | E2B | Significance |
|-----------|-----|---------|-----|-----|-------------|
| MMMU Pro | 76.9% | 73.8% | 52.6% | 49.7% | Multimodal understanding |
| MATH-Vision | 85.6% | 82.4% | 59.5% | 46.0% | Visual math/engineering reading |
| MedXPertQA MM | 61.3% | 58.1% | 28.7% | — | Medical visual understanding (transferable to safety) |

### Audio (E2B & E4B only)

| Benchmark | E4B | E2B | Significance |
|-----------|-----|-----|-------------|
| CoVoST (speech translation) | 35.54 | 33.47 | Bilingual voice commands |
| FLEURS (speech recognition, lower=better) | 0.08 | 0.09 | Worker voice reporting |

### Long Context

| Benchmark | 31B | 26B A4B | E4B | E2B | Significance |
|-----------|-----|---------|-----|-----|-------------|
| MRCR v2 8-needle 128K | 66.4% | 44.1% | 25.4% | 13.5% | Multi-frame temporal analysis |

### Arena Rankings

- **31B Dense**: #3 open model on Arena.ai overall
- **26B MoE**: #6 on Arena.ai — remarkable given only 3.8B active params
- **E2B/E4B**: First models to bring frontier capabilities to mobile hardware

---

## 3. Capability Assessment for Duchess

### 3.1 Multimodal Vision (E2B/E4B) — **Game-Changer**

**Before (Gemma 3n)**: Text-only model. Our `GemmaInferenceService.buildSafetyPrompt()` could not pass camera frames to the model. Tier 1 YOLO detections were described in text, and Gemma acted as a text classifier: "Based on this text description, is this a real PPE violation?"

**After (Gemma 4 E2B)**: Native vision input. We pass the actual `VideoFrame.bitmap` directly to the model alongside a text prompt. The model *sees* the construction site, the worker, the missing hard hat. This eliminates the weakest link in our pipeline — translating visual information to text descriptions and back.

**Variable Resolution**: Configurable visual token budgets (70-1120 tokens) let us trade detail for speed:
- **140 tokens**: Quick confirmation when YOLO is >90% confident
- **420 tokens**: Standard PPE analysis  
- **840 tokens**: Detailed analysis when YOLO confidence is low or scene is complex
- Variable aspect ratio support handles our portrait-orientation camera frames (504×896) natively

### 3.2 Native Audio (E2B/E4B) — **New Modality**

Workers can verbally report hazards in their native language. The model processes audio directly — no separate speech-to-text pipeline. Maximum 30 seconds per clip.

**Bilingual impact**: A Spanish-speaking worker says "esquina noroeste, andamio sin protección" and the model produces a structured safety alert with both English and Spanish descriptions. No translation step. Gemma 4 supports 140+ languages out of the box.

### 3.3 Native Function Calling — **Reliability Upgrade**

**Before**: We prompted the model to output JSON and defensively parsed it. ~5% of responses had malformed JSON, causing silent false negatives (real violations missed due to parse failures).

**After**: We define typed function schemas:
```
classify_ppe_violation(violation_detected: bool, violation_type: str, severity: int, 
                       description_en: str, description_es: str, confidence: float)
```
The model is structurally constrained to produce typed arguments. No more JSON parsing. No more silent failures. Expected schema compliance: **≥98%**.

**Agentic extension**: Multiple tool functions enable the model to *decide* next actions:
- `escalate_to_cloud(reason, priority)` — trigger immediate escalation
- `dismiss_false_positive(reason)` — reduce alert fatigue
- `request_additional_frames(count)` — ask for more visual context

### 3.4 Thinking Mode — **Explainable Safety Decisions**

Gemma 4's built-in reasoning mode (`<|think|>` token) produces step-by-step reasoning before the final answer. For safety-critical decisions, this creates an **auditable reasoning chain**:

```
<|channel>thought
I see a worker near scaffolding at approximately 3 stories height. 
They are wearing a hard hat (orange) and high-vis vest.
However, I do not see a fall protection harness, which is required above 6 feet per OSHA 1926.502.
The guardrail system on the scaffolding appears incomplete on the north side.
Severity: HIGH — fall hazard without harness at height.
<channel|>
{function_call: classify_ppe_violation(...)}
```

This is critical for the **Safety & Trust** hackathon prize — transparent, explainable AI decisions.

### 3.5 System Prompts — **Domain Specialization**

Gemma 4 introduces native `system` role support. We can embed construction safety context:

```
system: You are a construction site safety inspector for the Duchess platform. 
You analyze camera frames from workers' AR glasses for PPE violations and safety hazards.
Always respond in both English and Spanish. Cite OSHA regulations when applicable.
Focus on the Fatal Four: falls, struck-by, electrocution, caught-in/between.
```

This replaces our current approach of packing system instructions into the user prompt.

### 3.6 128K Context Window — **Temporal Reasoning**

128K tokens enables multi-frame temporal analysis. Instead of analyzing each frame independently, we can buffer context across a video sequence:

"Worker removed hard hat 30 seconds ago near heavy equipment. Has not replaced it. Three previous frames show ongoing overhead work. Escalating to HIGH severity due to sustained exposure."

This is currently done by our primitive `TemporalVoter` sliding window on the glasses — Gemma 4 can do it with actual language understanding.

---

## 4. Tier Deployment Strategy

### Tier 1: Ray-Ban Meta Glasses (Edge)
- **Model**: YOLOv8-nano + MobileNet (unchanged)
- **Purpose**: Real-time PPE detection at <50ms
- **Gemma 4 impact**: None directly — glasses are too resource-constrained for LLM inference
- **Indirect benefit**: Gemma 4's vision on the phone means Tier 1 can send raw frames instead of text descriptions

### Tier 2: Pixel 9 Fold (Phone) — **PRIMARY GEMMA 4 TARGET**
- **Model**: **Gemma 4 E2B** (2.3B effective, ~1.4GB loaded)
- **Upgrade path**: Gemma 4 E4B (4.5B effective, ~3GB loaded) for high-end phones
- **Hardware advantages**:
  - Tensor G4 chip with Edge TPU optimized for Gemma
  - 12GB RAM (comfortable headroom after model load)
  - UWB for precise indoor positioning
  - Foldable: 7.6" inner display for rich dashboards, 6.3" outer for field view
- **Capabilities used**: Vision, audio, function calling, thinking, system prompts, bilingual

### Tier 3: M4 Max MacBook (Local Server)
- **Model**: **Gemma 4 26B MoE** via Ollama/MLX
- **Purpose**: Complex scene analysis, multi-worker video fusion
- **Advantage**: 3.8B active params run fast on M4 Max; quality approaches 26B
- **Optional**: System works without Tier 3

### Tier 4: AWS Cloud
- **Model**: **Gemma 4 31B Dense** via SageMaker/Bedrock
- **Purpose**: Nightly batch analysis, escalated PPE assessment, multi-site orchestration
- **Advantage**: Maximum quality (85.2% MMLU Pro), 256K context for long video analysis

---

## 5. Cost Analysis

### On-Device Inference (Tier 2) — **$0/inference**

The entire Gemma 4 E2B model runs on the worker's phone. No API calls. No cloud cost. No per-token billing. This is the fundamental economic advantage of our architecture.

**One-time costs**:
- Model download to device: ~1.4GB (one-time, over WiFi)
- Fine-tuning: ~$50-100 in GPU compute (RTX 5090 for 14-18 hours)

**Per-inference costs**: $0.00

### Cloud Inference (Tier 4) — Cost Per Escalation

For the ~5% of frames that escalate to cloud:

| Provider | Model | Cost per 1K tokens | Est. cost per escalation |
|----------|-------|--------------------|----- --------------------|
| AWS Bedrock | Gemma 4 31B | ~$0.003 input, ~$0.015 output | ~$0.02 |
| Self-hosted SageMaker | Gemma 4 31B | ~$4.50/hr (g5.2xlarge) | ~$0.005 (amortized) |

**At scale** (1,000 workers, 8-hour shifts, 5% escalation rate):
- ~40,000 escalations/day × $0.02 = **$800/day** (Bedrock)
- ~40,000 escalations/day × $0.005 = **$200/day** (self-hosted)

### Fine-Tuning Costs

| Model | GPU | Time | Cost (cloud rental) |
|-------|-----|------|---------------------|
| E2B (QLoRA, 3 epochs, 10K examples) | RTX 5090 | ~14-18 hrs | ~$50-70 |
| E4B (QLoRA, 3 epochs, 10K examples) | RTX 5090 | ~24-32 hrs | ~$80-110 |
| 26B MoE (QLoRA, 3 epochs, 10K examples) | RTX 5090 | ~60-80 hrs | ~$200-280 |

---

## 6. Competitive Landscape

### vs. GPT-4o (OpenAI)
- GPT-4o is cloud-only, $5/1M input tokens
- Cannot run on-device
- No Apache 2.0 license, no fine-tuning
- **Duchess advantage**: On-device, private, zero marginal cost

### vs. Llama 3.3 (Meta)
- Llama 3.3 70B is larger, less efficient on mobile
- No native vision on small models
- No native audio
- **Duchess advantage**: Gemma 4 E2B has vision+audio on mobile-class hardware

### vs. Phi-4 (Microsoft)  
- Phi-4 is strong at reasoning but text-only
- No multimodal variants for mobile
- **Duchess advantage**: Multimodal vision+audio for construction site analysis

### vs. Qwen2.5-VL (Alibaba)
- Qwen2.5-VL-7B is our Tier 3 model — Gemma 4 26B MoE may replace it
- Gemma 4 26B MoE: 3.8B active params, 82.6% MMLU Pro
- Qwen2.5-VL-7B: 7B params, ~65% MMLU Pro
- **Decision**: Gemma 4 26B MoE replaces Qwen2.5-VL as Tier 3 model

---

## 7. Monetization Pathways

### 7.1 SaaS Platform ($15-50/worker/month)
- Phone app subscription with Gemma 4 on-device inference
- Cloud dashboard for site managers
- Competitive with manual safety inspection costs ($2,000-5,000/site/month)

### 7.2 Enterprise License ($50K-200K/year)
- On-premises deployment (no cloud, all tiers on-site)
- Custom fine-tuned models for specific trades (electrical, steel, concrete)
- Compliance reporting and audit trails

### 7.3 Model Weights License
- Publish Unsloth-fine-tuned Gemma 4 E2B for construction safety
- Sell domain-specific adapters (safety, engineering, electrical, multilingual)
- Apache 2.0 base model allows commercial redistribution

### 7.4 Insurance Integration
- Real-time safety scoring reduces insurance premiums
- Data-driven risk assessment for underwriters
- Partnership with construction insurance providers

### 7.5 Government Contracts
- OSHA compliance tool
- Department of Defense construction site monitoring
- State/local building safety enforcement

### Market Size
- US Construction: **$1.7 trillion** (2024)
- Construction Safety Technology: **$8.2 billion** (2025, growing 12% CAGR)
- PPE Compliance Market: **$3.1 billion** (2025)

---

## 8. Feasibility Assessment

### What's Ready Today
- ✅ Phone app compiles and runs (128MB debug APK, 0 errors)
- ✅ 4-tab navigation with dashboard, alerts, stream, settings
- ✅ Bilingual EN/ES string resources
- ✅ Demo mode with simulated safety data
- ✅ BLE GATT server/client for glasses ↔ phone
- ✅ Tailscale mesh networking
- ✅ Cloud CDK infrastructure (S3, DynamoDB, Lambda, SQS)
- ✅ ML training pipeline (Unsloth QLoRA, export to TFLite)

### What Needs Building (46 days to deadline)
- 🔄 Gemma 4 E2B integration (replacing Gemma 3n) — **3-5 days**
- 🔄 Multimodal vision input in GemmaInferenceService — **3-5 days**
- 🔄 Cactus SDK integration for multi-tier routing — **3-5 days**
- 🔄 Unsloth fine-tuning with Gemma 4 + vision adapter — **5-7 days**
- 🔄 LiteRT migration — **1-2 days**
- 🔄 Video demo production — **5-7 days**
- 🔄 Kaggle writeup — **2-3 days**

### Risk Assessment

| Risk | Probability | Mitigation |
|------|------------|------------|
| Unsloth doesn't support Gemma 4 by Week 2 | Medium | Use HuggingFace PEFT as fallback |
| Gemma 4 E2B too large for phone | Low | Q4_K_M quantization via GGUF |
| Video demo quality insufficient | Medium | Start filming early (Week 4), iterate |
| Cactus SDK incompatible with our architecture | Low | Our routing logic already works without Cactus |
| May 18 deadline too tight | Low | 46 days is ample with existing codebase |

### Verdict: **HIGHLY FEASIBLE**

We have a working, compiling, demo-ready codebase. The Gemma 4 migration is evolutionary, not revolutionary. The hackathon deadline is achievable with focused execution.

---

## 9. Recommended Model Configuration

### Smallest Models First (User Requirement)

For on-device (Tier 2), prioritize the smallest effective models:

| Configuration | Model | RAM Usage | Tokens/sec (est.) | Quality Trade-off |
|---------------|-------|-----------|-------------------|--------------------|
| **Primary** | Gemma 4 E2B (Q4_K_M) | ~900MB | 35-50 tok/s | Best efficiency, 60% MMLU Pro |
| **Enhanced** | Gemma 4 E4B (Q4_K_M) | ~2.5GB | 20-30 tok/s | Better quality, 69.4% MMLU Pro |
| **Controller** | Gemma 4 26B MoE (Q4_K_M) | ~14GB | 15-25 tok/s | Near-frontier quality, Mac server only |

**Strategy**: E2B handles 95% of on-device decisions. The 26B MoE on the Mac server acts as a "controller" for complex scenes that E2B flags as uncertain. This two-tier local inference maintains our privacy guarantee (nothing leaves the jobsite) while providing frontier-quality analysis when needed.

### Bigger Models as Controller

The 26B MoE model is ideal as a local controller:
- Only 3.8B active params → runs fast despite 26B total
- 82.6% MMLU Pro → graduate-level reasoning
- 256K context → can analyze entire shift's worth of escalated frames
- Runs on M4 Max MacBook via Ollama/MLX with 48GB unified memory

---

## 10. Next Steps

1. **Install Ollama and pull Gemma 4 E2B** — verify inference on MacBook
2. **Benchmark Gemma 4 E2B zero-shot** on iSafetyBench and Construction-PPE
3. **Begin Unsloth fine-tuning** once Unsloth adds Gemma 4 support
4. **Integrate vision input** in GemmaInferenceService
5. **Implement Cactus routing** for multi-tier model dispatch
6. **Start video demo scripting** — this is the highest-weighted evaluation criterion

---

_"The best safety system is the one that's always watching. Gemma 4 makes that possible on every worker's phone."_  
_— Duke Martinez, Duchess Project Coordinator_
