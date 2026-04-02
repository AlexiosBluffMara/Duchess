"""
Gemma 3n E2B fine-tuning with Unsloth Dynamic QLoRA.

Hardware: NVIDIA RTX 5090 (64GB VRAM, 8TB NVMe)
Model: google/gemma-3n-e2b-it (1.91B params)
Method: QLoRA (r=16, alpha=32, targets: q/k/v/o projections)
Dataset: Construction safety instruction pairs (EN + ES)

Usage:
    python scripts/train_gemma3n.py
    python scripts/train_gemma3n.py --adapter safety
    python scripts/train_gemma3n.py --adapter spanish_jargon
"""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path

import torch
import wandb
from datasets import load_dataset
from peft import LoraConfig
from transformers import TrainingArguments
from trl import SFTTrainer
from unsloth import FastLanguageModel

# ── Config ──────────────────────────────────────────────────────────────────

MODEL_NAME = "google/gemma-3n-e2b-it"
MAX_SEQ_LENGTH = 2048
DTYPE = None  # Auto-detect (float16 on RTX 5090)
LOAD_IN_4BIT = True  # QLoRA

LORA_R = 16
LORA_ALPHA = 32
LORA_DROPOUT = 0.05
LORA_TARGET_MODULES = ["q_proj", "k_proj", "v_proj", "o_proj"]

OUTPUT_DIR = Path("outputs")
ADAPTERS_DIR = Path("adapters")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Fine-tune Gemma 3n for Duchess")
    parser.add_argument(
        "--adapter",
        type=str,
        default="safety",
        choices=["safety", "spanish_jargon"],
        help="Which domain adapter to train",
    )
    parser.add_argument("--epochs", type=int, default=int(os.getenv("DUCHESS_TRAIN_EPOCHS", "3")))
    parser.add_argument(
        "--batch-size", type=int, default=int(os.getenv("DUCHESS_TRAIN_BATCH_SIZE", "4"))
    )
    parser.add_argument("--lr", type=float, default=float(os.getenv("DUCHESS_TRAIN_LR", "2e-4")))
    parser.add_argument("--max-steps", type=int, default=-1, help="Override epochs with max steps")
    parser.add_argument("--no-wandb", action="store_true", help="Disable W&B logging")
    return parser.parse_args()


def load_adapter_config(adapter_name: str) -> dict:
    config_path = ADAPTERS_DIR / adapter_name / "config.json"
    if config_path.exists():
        with open(config_path) as f:
            return json.load(f)
    return {}


def format_instruction(example: dict) -> str:
    """Format a single example into Gemma instruction format."""
    instruction = example.get("instruction", "")
    input_text = example.get("input", "")
    output_text = example.get("output", "")

    if input_text:
        return (
            f"<start_of_turn>user\n{instruction}\n\nInput: {input_text}<end_of_turn>\n"
            f"<start_of_turn>model\n{output_text}<end_of_turn>"
        )
    return (
        f"<start_of_turn>user\n{instruction}<end_of_turn>\n"
        f"<start_of_turn>model\n{output_text}<end_of_turn>"
    )


def main():
    args = parse_args()
    adapter_config = load_adapter_config(args.adapter)

    # ── W&B init ────────────────────────────────────────────────────────────
    if not args.no_wandb:
        wandb.init(
            project=os.getenv("WANDB_PROJECT", "duchess-ml"),
            name=f"gemma3n-{args.adapter}-qlora",
            config={
                "model": MODEL_NAME,
                "adapter": args.adapter,
                "lora_r": LORA_R,
                "lora_alpha": LORA_ALPHA,
                "epochs": args.epochs,
                "batch_size": args.batch_size,
                "lr": args.lr,
            },
        )

    # ── Load model with Unsloth ─────────────────────────────────────────────
    print(f"Loading {MODEL_NAME} with Unsloth (4-bit QLoRA)...")
    model, tokenizer = FastLanguageModel.from_pretrained(
        model_name=MODEL_NAME,
        max_seq_length=MAX_SEQ_LENGTH,
        dtype=DTYPE,
        load_in_4bit=LOAD_IN_4BIT,
    )

    # ── Apply LoRA adapters ─────────────────────────────────────────────────
    model = FastLanguageModel.get_peft_model(
        model,
        r=LORA_R,
        lora_alpha=LORA_ALPHA,
        lora_dropout=LORA_DROPOUT,
        target_modules=LORA_TARGET_MODULES,
        bias="none",
        use_gradient_checkpointing="unsloth",
        random_state=42,
    )

    # ── Load dataset ────────────────────────────────────────────────────────
    dataset_name = adapter_config.get("dataset", "duchess/construction-safety-instructions")
    print(f"Loading dataset: {dataset_name}")

    try:
        dataset = load_dataset(dataset_name, split="train")
    except Exception:
        # Placeholder: generate synthetic training data if HF dataset not available
        print("Dataset not found on HuggingFace — using placeholder data")
        from datasets import Dataset

        placeholder_data = [
            {
                "instruction": "Identify the PPE violation in this scene description.",
                "input": "Worker on scaffolding at 15ft height, wearing vest but no hardhat.",
                "output": '{"violation": "no_hardhat", "severity": 3, '
                '"description_en": "Worker at height without hardhat", '
                '"description_es": "Trabajador en altura sin casco"}',
            },
            {
                "instruction": "Identifique la violación de EPP en esta descripción.",
                "input": "Trabajador en andamio a 5m de altura, con chaleco pero sin casco.",
                "output": '{"violation": "no_hardhat", "severity": 3, '
                '"description_en": "Worker at height without hardhat", '
                '"description_es": "Trabajador en altura sin casco"}',
            },
            {
                "instruction": "Assess the safety risk level.",
                "input": "Excavation site, no barricades, workers within 6ft of edge.",
                "output": '{"violation": "fall_hazard", "severity": 4, '
                '"description_en": "Unprotected excavation edge", '
                '"description_es": "Borde de excavación sin protección"}',
            },
        ]
        dataset = Dataset.from_list(placeholder_data)

    dataset = dataset.map(lambda x: {"text": format_instruction(x)})

    # ── Training ────────────────────────────────────────────────────────────
    output_path = OUTPUT_DIR / f"gemma3n-{args.adapter}"
    output_path.mkdir(parents=True, exist_ok=True)

    training_args = TrainingArguments(
        output_dir=str(output_path),
        num_train_epochs=args.epochs,
        max_steps=args.max_steps,
        per_device_train_batch_size=args.batch_size,
        gradient_accumulation_steps=4,
        learning_rate=args.lr,
        weight_decay=0.01,
        warmup_ratio=0.03,
        lr_scheduler_type="cosine",
        logging_steps=10,
        save_steps=100,
        save_total_limit=3,
        fp16=not torch.cuda.is_bf16_supported(),
        bf16=torch.cuda.is_bf16_supported(),
        optim="adamw_8bit",
        seed=42,
        report_to="wandb" if not args.no_wandb else "none",
    )

    trainer = SFTTrainer(
        model=model,
        tokenizer=tokenizer,
        train_dataset=dataset,
        dataset_text_field="text",
        max_seq_length=MAX_SEQ_LENGTH,
        args=training_args,
    )

    print(f"Starting training: {args.adapter} adapter, {args.epochs} epochs")
    trainer.train()

    # ── Save adapter ────────────────────────────────────────────────────────
    adapter_output = ADAPTERS_DIR / args.adapter / "weights"
    adapter_output.mkdir(parents=True, exist_ok=True)
    model.save_pretrained(str(adapter_output))
    tokenizer.save_pretrained(str(adapter_output))
    print(f"Adapter saved to {adapter_output}")

    if not args.no_wandb:
        wandb.finish()


if __name__ == "__main__":
    main()
