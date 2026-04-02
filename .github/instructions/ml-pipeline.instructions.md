---
description: "Use when working on the ML training pipeline, model fine-tuning, dataset preparation, or model export. Covers Unsloth Dynamic QLoRA, domain adapters, evaluation protocols, and export formats."
applyTo: "ml/**"
---

# ML Pipeline Instructions

## Training Stack

- **Framework**: Unsloth (only framework supporting Gemma 4 quantization)
- **Method**: Dynamic QLoRA (0% accuracy loss, 2-4x memory reduction over LoRA)
- **GPU**: RTX 5090 (32GB VRAM)
- **Language**: Python 3.11, PyTorch, managed via Poetry
- **Tracking**: Weights & Biases for all experiments

## Mandatory Practices

1. **Always evaluate base model zero-shot FIRST** before fine-tuning. Document the baseline.
2. **Always use held-out test set** that was never seen during training or validation.
3. **Always version everything**: dataset version, hyperparameters, random seed, git commit hash.
4. **Always run ablation studies** for hyperparameter choices on novel tasks.
5. **Always benchmark on standard datasets**: iSafetyBench, Construction-PPE, MOCS, SH17.
6. **Always export to the correct format** for the target tier:
   - Tier 1: LiteRT (FP16 or INT8)
   - Tier 2: GGUF (Q4_K_M)
   - Tier 3: MLX (FP16)
   - Tier 4: SafeTensors (FP16/BF16)

## Domain Adapters

Four LoRA adapters trained independently, composable at inference:

| Adapter | Focus | Key Training Data |
|---------|-------|------------------|
| Safety | PPE/hazard vocabulary, OSHA terms | iSafetyBench, Construction-PPE, SH17 |
| Spanish Jargon | Construction-register Spanish, code-switching | Curated bilingual corpus |
| Engineering | Structural/MEP/building codes | Technical documentation corpus |
| Electrical | NEC codes, panel schedules, electrical safety | Electrical code + field data |

## Evaluation Requirements

Every model change PR must include:
- mAP@50:95 (detection models) or accuracy/F1 (classification models)
- Per-class metrics for life-critical classes (hard hat, vest, harness)
- Comparison against base model AND previous best
- Inference latency on target hardware
- Peak memory usage on target hardware
- No regression allowed on life-critical classes (recall for no_hard_hat, no_safety_vest)
