# Duchess ML Training Pipeline

Fine-tuning pipeline for the Duchess Construction Site Intelligence Platform.

## Models

| Model | Purpose | Method | Target Device |
|-------|---------|--------|---------------|
| Gemma 3n E2B (1.91B) | Safety NLU / triage | QLoRA (r=16, alpha=32) | Pixel 9 Fold (Tier 2) |
| YOLOv8-nano | PPE object detection | Transfer learning | Vuzix M400 (Tier 1) |

## Hardware Requirements

| Component | Spec |
|-----------|------|
| GPU | NVIDIA RTX 5090 (64GB VRAM) |
| Storage | 8TB NVMe (for datasets + checkpoints) |
| RAM | 128GB recommended |
| Python | 3.11+ |
| CUDA | 12.4+ |

## Setup

```bash
# Install Poetry if not already installed
curl -sSL https://install.python-poetry.org | python3 -

# Install dependencies
cd ml/
poetry install

# Copy environment template
cp .env.example .env
# Edit .env with your real API keys (WANDB_API_KEY, HF_TOKEN)
```

## Training

### 1. Prepare Dataset

```bash
poetry run python scripts/prepare_dataset.py
# Output: data/safety_dataset.jsonl
```

### 2. Train Gemma 3n Adapters

```bash
# Safety domain adapter (PPE, OSHA, hazard classification)
poetry run python scripts/train_gemma3n.py --adapter safety

# Construction Spanish jargon adapter
poetry run python scripts/train_gemma3n.py --adapter spanish_jargon

# Quick test run
poetry run python scripts/train_gemma3n.py --adapter safety --max-steps 10 --no-wandb
```

### 3. Export for Android

```bash
# Export merged model → ONNX → TFLite FP16
poetry run python scripts/export_model.py --adapter safety

# Output: exports/safety/tflite/gemma3n_duchess.tflite
# Copy to: app-phone/app/src/main/assets/
```

### 4. Evaluate

```bash
poetry run python eval/benchmark.py --adapter safety
```

## Domain Adapters

| Adapter | Config | Purpose |
|---------|--------|---------|
| `safety` | `adapters/safety/config.json` | PPE violations, OSHA compliance, hazard classification |
| `spanish_jargon` | `adapters/spanish_jargon/config.json` | Construction-register Spanish, terminology, code-switching |

## Directory Structure

```
ml/
├── scripts/
│   ├── train_gemma3n.py      # Unsloth QLoRA fine-tuning
│   ├── prepare_dataset.py    # Dataset preparation
│   └── export_model.py       # ONNX + TFLite export
├── adapters/
│   ├── safety/config.json
│   └── spanish_jargon/config.json
├── eval/
│   └── benchmark.py          # iSafetyBench evaluation
├── data/                     # Generated datasets (gitignored)
├── outputs/                  # Training checkpoints (gitignored)
├── exports/                  # Exported models (gitignored)
├── pyproject.toml
├── .env.example
└── README.md
```

## Privacy

- Training data must not contain real worker identities
- Model outputs include bilingual descriptions (EN + ES) — never English-only
- Exported models are deployed on-device — no cloud inference for primary NLU
