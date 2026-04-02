"""
Evaluation benchmark for Duchess fine-tuned models.

Evaluates against iSafetyBench-style placeholders:
  - PPE violation classification accuracy
  - Bilingual output quality (EN + ES both present)
  - Inference latency on target hardware
  - Severity calibration

Usage:
    python eval/benchmark.py --adapter safety
    python eval/benchmark.py --adapter safety --device cuda
"""

from __future__ import annotations

import argparse
import json
import time
from pathlib import Path

import torch


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Benchmark Duchess models")
    parser.add_argument(
        "--adapter",
        type=str,
        default="safety",
        choices=["safety", "spanish_jargon"],
    )
    parser.add_argument("--device", type=str, default="cuda" if torch.cuda.is_available() else "cpu")
    parser.add_argument("--num-samples", type=int, default=50)
    return parser.parse_args()


# ── Placeholder test cases (iSafetyBench-style) ────────────────────────────

TEST_CASES = [
    {
        "input": "Worker on scaffolding without hardhat, wearing vest.",
        "expected_violation": "no_hardhat",
        "expected_severity": 3,
    },
    {
        "input": "Worker near excavation edge, no guardrails visible.",
        "expected_violation": "fall_hazard",
        "expected_severity": 4,
    },
    {
        "input": "All workers wearing hardhats, vests, safety glasses. Guardrails in place.",
        "expected_violation": None,
        "expected_severity": 0,
    },
    {
        "input": "Exposed electrical wiring near puddle of water on ground floor.",
        "expected_violation": "electrical_hazard",
        "expected_severity": 5,
    },
    {
        "input": "Trabajador sin chaleco reflectante cerca de maquinaria pesada.",
        "expected_violation": "no_vest",
        "expected_severity": 3,
    },
    {
        "input": "Worker operating crane without signal person, blind lift in progress.",
        "expected_violation": "struck_by_hazard",
        "expected_severity": 4,
    },
]


def load_model(adapter_name: str, device: str):
    """Load base model + adapter for evaluation."""
    from peft import PeftModel
    from transformers import AutoModelForCausalLM, AutoTokenizer

    model_name = "google/gemma-3n-e2b-it"
    adapter_path = Path("adapters") / adapter_name / "weights"

    tokenizer = AutoTokenizer.from_pretrained(model_name)

    if adapter_path.exists():
        print(f"Loading model with {adapter_name} adapter")
        model = AutoModelForCausalLM.from_pretrained(
            model_name, torch_dtype=torch.float16, device_map=device
        )
        model = PeftModel.from_pretrained(model, str(adapter_path))
    else:
        print(f"Adapter not found at {adapter_path} — benchmarking base model")
        model = AutoModelForCausalLM.from_pretrained(
            model_name, torch_dtype=torch.float16, device_map=device
        )

    return model, tokenizer


def run_inference(model, tokenizer, prompt: str, device: str) -> tuple[str, float]:
    """Run single inference, return (output, latency_ms)."""
    full_prompt = (
        f"<start_of_turn>user\n"
        f"Identify the safety violation. Respond with JSON containing: "
        f"violation, severity, description_en, description_es.\n\n"
        f"Scene: {prompt}<end_of_turn>\n"
        f"<start_of_turn>model\n"
    )

    inputs = tokenizer(full_prompt, return_tensors="pt").to(device)

    start = time.perf_counter()
    with torch.no_grad():
        outputs = model.generate(
            **inputs,
            max_new_tokens=256,
            temperature=0.1,
            do_sample=False,
        )
    latency_ms = (time.perf_counter() - start) * 1000

    response = tokenizer.decode(outputs[0][inputs["input_ids"].shape[1]:], skip_special_tokens=True)
    return response, latency_ms


def evaluate_response(response: str, expected: dict) -> dict:
    """Score a single response against expected values."""
    scores = {
        "violation_correct": False,
        "severity_correct": False,
        "has_en": False,
        "has_es": False,
        "valid_json": False,
    }

    try:
        parsed = json.loads(response)
        scores["valid_json"] = True
        scores["violation_correct"] = parsed.get("violation") == expected["expected_violation"]
        scores["severity_correct"] = parsed.get("severity") == expected["expected_severity"]
        scores["has_en"] = "description_en" in parsed and len(parsed.get("description_en", "")) > 0
        scores["has_es"] = "description_es" in parsed and len(parsed.get("description_es", "")) > 0
    except (json.JSONDecodeError, TypeError):
        pass

    return scores


def main():
    args = parse_args()

    print(f"=== Duchess Benchmark: {args.adapter} adapter ===")
    print(f"Device: {args.device}")
    print(f"Test cases: {len(TEST_CASES)}")
    print()

    try:
        model, tokenizer = load_model(args.adapter, args.device)
    except Exception as e:
        print(f"Could not load model: {e}")
        print("Running with stub scores (model not available)")
        print_stub_results()
        return

    results = []
    latencies = []

    for i, test_case in enumerate(TEST_CASES):
        print(f"  [{i+1}/{len(TEST_CASES)}] {test_case['input'][:60]}...")
        response, latency_ms = run_inference(model, tokenizer, test_case["input"], args.device)
        latencies.append(latency_ms)

        scores = evaluate_response(response, test_case)
        results.append(scores)

    # ── Results table ───────────────────────────────────────────────────────
    print_results(results, latencies, args)


def print_results(results: list[dict], latencies: list[float], args):
    """Print formatted benchmark results table."""
    n = len(results)
    metrics = {
        "Violation Accuracy": sum(r["violation_correct"] for r in results) / n * 100,
        "Severity Accuracy": sum(r["severity_correct"] for r in results) / n * 100,
        "Valid JSON": sum(r["valid_json"] for r in results) / n * 100,
        "Bilingual EN": sum(r["has_en"] for r in results) / n * 100,
        "Bilingual ES": sum(r["has_es"] for r in results) / n * 100,
    }

    print()
    print(f"{'Metric':<25} {'Score':>10}")
    print("-" * 37)
    for metric, score in metrics.items():
        print(f"{metric:<25} {score:>9.1f}%")
    print("-" * 37)
    print(f"{'Avg Latency':<25} {sum(latencies)/len(latencies):>8.1f}ms")
    print(f"{'P95 Latency':<25} {sorted(latencies)[int(len(latencies)*0.95)]:>8.1f}ms")
    print(f"{'Device':<25} {args.device:>10}")
    print(f"{'Adapter':<25} {args.adapter:>10}")


def print_stub_results():
    """Print placeholder results when model is not available."""
    print()
    print(f"{'Metric':<25} {'Score':>10}")
    print("-" * 37)
    print(f"{'Violation Accuracy':<25} {'N/A':>10}")
    print(f"{'Severity Accuracy':<25} {'N/A':>10}")
    print(f"{'Valid JSON':<25} {'N/A':>10}")
    print(f"{'Bilingual EN':<25} {'N/A':>10}")
    print(f"{'Bilingual ES':<25} {'N/A':>10}")
    print("-" * 37)
    print(f"{'Note':<25} {'Model not loaded':>10}")
    print("\nTrain a model first: python scripts/train_gemma3n.py")


if __name__ == "__main__":
    main()
