# Duchess — Construction Site Intelligence Platform

Duchess is an AI-powered construction safety platform built on Vuzix M400 AR glasses, companion smartphones, and cloud inference. It detects PPE violations and construction hazards in real-time using a four-tier inference hierarchy from edge to cloud.

**Authors**: Bhattacharya, Baksi, Lahiri — Illinois State University / Alexios Bluff Mara LLC

## Agent Team

All work is coordinated by **Duke**, the project manager, who delegates to specialist agents:

| Agent | Role | Expertise |
|-------|------|-----------|
| **Duke** | Coordinator/Manager | Construction management, computer graphics, Spanish, architecture |
| **Carlos** | Safety Officer | OSHA compliance, PPE requirements, hazard identification |
| **Raj** | Field Superintendent | Jobsite operations, field deployment, worker workflows |
| **Mei** | Structural Engineer | Engineering analysis, building codes, digital twins |
| **Alex** | Android Developer | Vuzix M400, companion phone, Kotlin, BLE, Camera2 |
| **Jordan** | Cloud Engineer | AWS Bedrock, SageMaker, Lambda, nightly batch pipeline |
| **Taylor** | DevOps Engineer | CI/CD, GitHub Actions, Docker, deployment automation |
| **Sam** | QA Engineer | Testing strategy, ML validation, safety-critical QA |
| **Priya** | ML Engineer | Unsloth QLoRA, fine-tuning, domain adapters, BitNet |
| **Kai** | Edge AI Specialist | TFLite, ONNX, on-device optimization, profiling |
| **Elena** | CV Engineer | YOLOv8, PPE detection, object detection, augmentation |
| **Noah** | Network Engineer | Tailscale mesh, WireGuard, P2P streaming, geospatial |
| **Wei** | Research Scientist | Grant writing (NSF/OSHA), papers (CVPR/ACL/CHI) |
| **Maya** | UX/AR Designer | AR HUD design, accessibility, bilingual UI |
| **Luis** | Bilingual Specialist | Construction-register Spanish, localization, code-switching |

## Skills

Shared capabilities available to all agents:

| Skill | Description |
|-------|-------------|
| `android-development` | Vuzix M400 + companion phone app development |
| `ml-model-training` | Fine-tuning with Unsloth Dynamic QLoRA |
| `model-quantization` | TFLite, GGUF, MLX conversion and optimization |
| `computer-vision` | YOLOv8 training, PPE detection, augmentation |
| `edge-inference` | On-device ML optimization for all tiers |
| `ppe-detection-pipeline` | End-to-end PPE violation detection flow |
| `cloud-infrastructure` | AWS services, nightly batch, escalation API |
| `tailscale-networking` | Mesh network, geospatial tracking, streaming |
| `construction-safety` | OSHA regulations, PPE standards, Fatal Four |
| `ar-interface-design` | AR HUD layouts, voice UI, alert design |
| `video-processing` | Camera capture, encoding, streaming, storage |
| `bilingual-localization` | English/Spanish construction terminology |
| `grant-writing` | NSF, OSHA, SBIR grant proposals |
| `devops-cicd` | CI/CD pipelines, deployment automation |
| `testing-qa` | Test strategies for safety-critical multi-tier systems |

## Project Structure

```
Duchess/
├── AGENTS.md                          # Workspace-wide instructions
├── README.md
├── .github/
│   ├── agents/                        # Agent personas
│   │   ├── duke.agent.md              # Coordinator (delegates all work)
│   │   ├── carlos.agent.md            # Safety Officer
│   │   ├── raj.agent.md               # Field Superintendent
│   │   ├── mei.agent.md               # Structural Engineer
│   │   ├── alex.agent.md              # Android Developer
│   │   ├── jordan.agent.md            # Cloud Engineer
│   │   ├── taylor.agent.md            # DevOps Engineer
│   │   ├── sam.agent.md               # QA Engineer
│   │   ├── priya.agent.md             # ML Engineer
│   │   ├── kai.agent.md               # Edge AI Specialist
│   │   ├── elena.agent.md             # CV Engineer
│   │   ├── noah.agent.md              # Network Engineer
│   │   ├── wei.agent.md               # Research Scientist
│   │   ├── maya.agent.md              # UX/AR Designer
│   │   └── luis.agent.md              # Bilingual Specialist
│   ├── skills/                        # Shared skills
│   │   ├── android-development/
│   │   ├── ml-model-training/
│   │   ├── model-quantization/
│   │   ├── computer-vision/
│   │   ├── edge-inference/
│   │   ├── ppe-detection-pipeline/
│   │   ├── cloud-infrastructure/
│   │   ├── tailscale-networking/
│   │   ├── construction-safety/
│   │   ├── ar-interface-design/
│   │   ├── video-processing/
│   │   ├── bilingual-localization/
│   │   ├── grant-writing/
│   │   ├── devops-cicd/
│   │   └── testing-qa/
│   └── instructions/                  # Context-aware instructions
│       ├── project-architecture.instructions.md
│       ├── ppe-detection-workflow.instructions.md
│       ├── android-vuzix.instructions.md
│       ├── android-companion.instructions.md
│       ├── ml-pipeline.instructions.md
│       ├── mesh-network.instructions.md
│       ├── cloud-services.instructions.md
│       ├── safety-alert-system.instructions.md
│       └── data-privacy.instructions.md
├── app-glasses/                       # (Vuzix M400 Android app — TBD)
├── app-phone/                         # (Companion phone app — TBD)
├── ml/                                # (ML training pipeline — TBD)
├── cloud/                             # (AWS CDK infrastructure — TBD)
└── docs/                              # (Technical documentation — TBD)
```
