# Duchess — Construction Site Intelligence Platform

## Project Overview

Duchess is a four-tier AI-powered construction safety and intelligence platform built on Vuzix M400 AR glasses, companion smartphones, optional local Mac servers, and cloud inference (AWS). The system detects PPE violations, construction hazards, and safety risks in real-time using a hierarchy of ML models from edge to cloud.

## Architecture

```
Tier 1: Vuzix M400 (YOLOv8-nano, MobileNet PPE) → <50ms on-glass edge
Tier 2: Companion Phone (Gemma 4 E2B) → NLU, bilingual alerts, triage
Tier 3: Local Mac Server (Qwen2.5-VL-72B) → optional, complex scene analysis
Tier 4: Cloud/AWS (Bedrock, SageMaker) → nightly batch + escalated inference
```

### Critical Data Flow: PPE Detection Pipeline

1. Tier 1 (glasses) runs YOLOv8-nano continuously for PPE detection
2. If PPE violation suspected → frame sent to Tier 2 (phone) for Gemma 4 confirmation
3. If Gemma 4 confirms → video clip immediately sent to cloud for full assessment (skip nightly batch)
4. If cloud model confirms → sent to human reviewer for verification
5. If human confirms → alert dispatched via mesh network to the nearest worker(s) geospatially
6. All other video data runs nightly batch inference on cloud for retrospective analysis

### Mesh Network

- Tailscale WireGuard mesh connects all devices on-site
- Not all workers wear glasses; ALL workers have the companion phone app
- Geospatial device tracking enables targeted alert delivery
- Direct peer <10ms, relay through peers, DERP fallback 50-150ms

## Tech Stack

- **Languages**: Kotlin (Android), Python (ML/backend), Swift (optional iOS), TypeScript (cloud functions)
- **Android**: AOSP Android 13 (Vuzix), standard Android (companion phone), no Google Play Services on glasses
- **ML Frameworks**: LiteRT, ONNX Runtime, MLX, Unsloth, PyTorch
- **Models**: YOLOv8-nano, MobileNet, Gemma 4 E2B, Qwen2.5-VL-7B/72B
- **Cloud**: AWS Bedrock, SageMaker, IoT TwinMaker, Lambda
- **Networking**: Tailscale, WireGuard, BLE 5.0
- **Quantization**: Dynamic QLoRA (Unsloth), BitNet b1.58 (1-bit), LiteRT FP16/INT8

## Code Style

- Kotlin: Follow Android Kotlin style guide, coroutines for async
- Python: PEP 8, type hints, Black formatter, isort
- All code must handle offline/degraded network gracefully
- Battery-conscious coding on edge devices — minimize wake locks, batch network calls
- All user-facing strings must support English and Spanish (construction register)

## Build and Test

- Android: Gradle with Kotlin DSL, min SDK 33 (Vuzix), min SDK 30 (companion)
- ML: Poetry for Python dependency management
- Tests: JUnit 5 + Espresso (Android), pytest (Python), integration tests for tier transitions
- CI/CD: GitHub Actions for all pipelines

## Security and Privacy

- Video never leaves jobsite unless escalated through the PPE pipeline or nightly batch
- WireGuard encryption on all mesh traffic; Tailscale cannot decrypt
- HIPAA-compliant for biometric data
- Tag-based ACLs, SSO, MFA on all nodes
- No PII in logs; anonymize worker identifiers in cloud-bound data

## Conventions

- Agent names are personas — Duke coordinates all work, delegates to specialists
- Every PR must include test coverage for the changed tier(s)
- ML model changes require benchmark results against iSafetyBench and Construction-PPE datasets
- Bilingual support is not optional — all alerts and UI must work in English and Spanish
- Document all model accuracy/latency tradeoffs when changing quantization
