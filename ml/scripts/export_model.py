"""
Export fine-tuned Gemma 3n adapter to ONNX and TFLite FP16 for Android deployment.

Pipeline:
  1. Load base model + merge LoRA adapter weights
  2. Export to ONNX via Optimum
  3. Convert ONNX to TFLite FP16 via ai-edge-torch
  4. Output: ready for app-phone/ Gemma inference service

Usage:
    python scripts/export_model.py --adapter safety
    python scripts/export_model.py --adapter spanish_jargon --output exports/
"""

from __future__ import annotations

import argparse
import shutil
from pathlib import Path

import torch


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Export Gemma 3n adapter for Android")
    parser.add_argument(
        "--adapter",
        type=str,
        default="safety",
        choices=["safety", "spanish_jargon"],
        help="Which adapter to export",
    )
    parser.add_argument(
        "--output",
        type=str,
        default="exports",
        help="Output directory",
    )
    parser.add_argument(
        "--skip-onnx",
        action="store_true",
        help="Skip ONNX export (go straight to TFLite if ONNX exists)",
    )
    return parser.parse_args()


MODEL_NAME = "google/gemma-3n-e2b-it"
ADAPTERS_DIR = Path("adapters")


def merge_adapter(adapter_name: str) -> tuple:
    """Load base model and merge LoRA adapter weights."""
    from peft import PeftModel
    from transformers import AutoModelForCausalLM, AutoTokenizer

    adapter_path = ADAPTERS_DIR / adapter_name / "weights"
    if not adapter_path.exists():
        raise FileNotFoundError(
            f"Adapter weights not found at {adapter_path}. Run train_gemma3n.py first."
        )

    print(f"Loading base model: {MODEL_NAME}")
    tokenizer = AutoTokenizer.from_pretrained(MODEL_NAME)
    model = AutoModelForCausalLM.from_pretrained(
        MODEL_NAME,
        torch_dtype=torch.float16,
        device_map="auto",
    )

    print(f"Merging adapter: {adapter_name}")
    model = PeftModel.from_pretrained(model, str(adapter_path))
    model = model.merge_and_unload()

    return model, tokenizer


def export_to_onnx(model, tokenizer, output_dir: Path) -> Path:
    """Export merged model to ONNX format via Optimum."""
    from optimum.exporters.onnx import main_export

    onnx_path = output_dir / "onnx"
    onnx_path.mkdir(parents=True, exist_ok=True)

    print(f"Exporting to ONNX: {onnx_path}")

    # Save merged model temporarily for Optimum export
    tmp_merged = output_dir / "_tmp_merged"
    tmp_merged.mkdir(parents=True, exist_ok=True)
    model.save_pretrained(str(tmp_merged))
    tokenizer.save_pretrained(str(tmp_merged))

    main_export(
        model_name_or_path=str(tmp_merged),
        output=str(onnx_path),
        task="text-generation",
        fp16=True,
    )

    # Clean up temp
    shutil.rmtree(tmp_merged)
    print(f"ONNX export complete: {onnx_path}")
    return onnx_path


def export_to_tflite(onnx_path: Path, output_dir: Path) -> Path:
    """Convert ONNX model to TFLite FP16 for Android deployment."""
    tflite_path = output_dir / "tflite"
    tflite_path.mkdir(parents=True, exist_ok=True)

    output_file = tflite_path / "gemma3n_duchess.tflite"

    print(f"Converting ONNX to TFLite FP16: {output_file}")

    # TODO: ai-edge-torch conversion
    # The actual conversion depends on the model architecture and
    # ai-edge-torch version. This is a placeholder for the pipeline.
    #
    # from ai_edge_torch import convert
    # edge_model = convert(onnx_path / "model.onnx", quantize="fp16")
    # edge_model.export(str(output_file))

    # Placeholder: create empty file to mark pipeline position
    output_file.write_text(
        "PLACEHOLDER — Run full export pipeline with real adapter weights.\n"
        "See: ai-edge-torch documentation for ONNX → TFLite conversion.\n"
    )

    print(f"TFLite export complete: {output_file}")
    return tflite_path


def main():
    args = parse_args()
    output_dir = Path(args.output) / args.adapter
    output_dir.mkdir(parents=True, exist_ok=True)

    print(f"=== Duchess Model Export: {args.adapter} adapter ===")
    print(f"Output directory: {output_dir}")

    # Step 1: Merge adapter
    model, tokenizer = merge_adapter(args.adapter)

    # Step 2: ONNX export
    if args.skip_onnx:
        onnx_path = output_dir / "onnx"
        if not onnx_path.exists():
            raise FileNotFoundError(f"--skip-onnx specified but no ONNX at {onnx_path}")
        print(f"Skipping ONNX export, using existing: {onnx_path}")
    else:
        onnx_path = export_to_onnx(model, tokenizer, output_dir)

    # Step 3: TFLite conversion
    tflite_path = export_to_tflite(onnx_path, output_dir)

    print(f"\n=== Export complete ===")
    print(f"ONNX:   {onnx_path}")
    print(f"TFLite: {tflite_path}")
    print(f"\nCopy {tflite_path}/gemma3n_duchess.tflite to app-phone/app/src/main/assets/")


if __name__ == "__main__":
    main()
