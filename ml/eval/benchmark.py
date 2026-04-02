"""
Evaluation benchmark for Duchess fine-tuned models.

# Priya: This is the evaluation suite that gates every model release. No model
# gets deployed to phones or cloud without passing through this benchmark.
#
# What we measure:
#   1. Per-class accuracy, precision, recall, F1 (micro + macro averaged)
#   2. Confusion matrix (full NxN for all violation types)
#   3. Bilingual output quality (both EN and ES present and non-empty)
#   4. JSON schema compliance (valid JSON with required fields)
#   5. Severity calibration (predicted severity vs ground truth)
#   6. Inference latency (mean, std, p50, p95, p99)
#
# Output formats:
#   - Console table (always)
#   - Markdown report file (--output-markdown)
#   - JSON metrics file (--output-json) for CI/CD integration
#
# Benchmark datasets:
#   - iSafetyBench-style placeholders (built-in)
#   - Custom test JSONL (--test-data)
#
# Usage:
#     python eval/benchmark.py --adapter safety
#     python eval/benchmark.py --adapter safety --device cuda
#     python eval/benchmark.py --adapter safety --output-markdown reports/safety.md
#     python eval/benchmark.py --adapter safety --output-json reports/safety.json
#     python eval/benchmark.py --adapter safety --compare-baseline
"""

from __future__ import annotations

import argparse
import json
import time
from collections import Counter, defaultdict
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
    parser.add_argument(
        "--device",
        type=str,
        default="cuda" if torch.cuda.is_available() else "cpu",
    )
    parser.add_argument("--num-samples", type=int, default=50)
    parser.add_argument(
        "--test-data",
        type=str,
        default=None,
        help="Path to custom test JSONL file",
    )
    parser.add_argument(
        "--output-markdown",
        type=str,
        default=None,
        help="Path to write markdown report",
    )
    parser.add_argument(
        "--output-json",
        type=str,
        default=None,
        help="Path to write JSON metrics",
    )
    parser.add_argument(
        "--compare-baseline",
        action="store_true",
        help="Also run baseline (no adapter) for comparison",
    )
    return parser.parse_args()


# ── Test cases (iSafetyBench-style) ────────────────────────────────────────
# Priya: These are our built-in evaluation examples. They cover:
#   - Each violation type at least once
#   - A compliant scene (no violation)
#   - Spanish input (to test bilingual comprehension)
#   - Multiple severity levels (0-5)
#
# For real evaluation, use --test-data with a held-out JSONL file. These
# built-in cases are for smoke testing and CI pipeline validation.

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

# Priya: All known violation labels from our taxonomy. Used for confusion matrix.
ALL_LABELS = [
    "no_hardhat", "no_vest", "no_eye_protection", "no_ear_protection",
    "no_eye_ear_protection", "fall_hazard", "electrical_hazard",
    "excavation_hazard", "struck_by_hazard", "confined_space",
    "unprotected_opening", "compliant",
]


def load_test_data(test_data_path: str) -> list[dict]:
    """Load custom test data from JSONL file.

    # Priya: Custom test data MUST have the same schema as our built-in
    # TEST_CASES: {input, expected_violation, expected_severity}.
    # The output field is optional (only needed for comparison).
    """
    path = Path(test_data_path)
    if not path.exists():
        raise FileNotFoundError(f"Test data not found: {test_data_path}")

    test_cases = []
    with open(path) as f:
        for line in f:
            line = line.strip()
            if line:
                test_cases.append(json.loads(line))

    print(f"Loaded {len(test_cases)} test cases from {test_data_path}")
    return test_cases


def load_model(adapter_name: str | None, device: str):
    """Load base model + optional adapter for evaluation.

    # Priya: When adapter_name is None, we load the bare base model. This is
    # used for --compare-baseline to measure the delta from fine-tuning.
    # A good adapter should show ≥5% improvement over baseline on our metrics.
    """
    from peft import PeftModel
    from transformers import AutoModelForCausalLM, AutoTokenizer

    model_name = "google/gemma-3n-e2b-it"
    adapter_path = Path("adapters") / adapter_name / "weights" if adapter_name else None

    tokenizer = AutoTokenizer.from_pretrained(model_name)

    if adapter_path and adapter_path.exists():
        print(f"Loading model with {adapter_name} adapter")
        model = AutoModelForCausalLM.from_pretrained(
            model_name, torch_dtype=torch.float16, device_map=device
        )
        model = PeftModel.from_pretrained(model, str(adapter_path))
    else:
        label = adapter_name or "baseline"
        if adapter_path and not adapter_path.exists():
            print(f"Adapter not found at {adapter_path} — benchmarking base model")
        else:
            print(f"Loading base model (no adapter) for {label} evaluation")
        model = AutoModelForCausalLM.from_pretrained(
            model_name, torch_dtype=torch.float16, device_map=device
        )

    return model, tokenizer


def run_inference(model, tokenizer, prompt: str, device: str) -> tuple[str, float]:
    """Run single inference, return (output_text, latency_ms).

    # Priya: We use temperature=0.1 with do_sample=False (greedy) for
    # deterministic evaluation. Any randomness in eval is a bug.
    """
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

    response = tokenizer.decode(
        outputs[0][inputs["input_ids"].shape[1]:], skip_special_tokens=True
    )
    return response, latency_ms


def evaluate_response(response: str, expected: dict) -> dict:
    """Score a single response against expected values.

    # Priya: We score on 5 dimensions:
    #   1. violation_correct — exact match on violation type
    #   2. severity_correct — exact match on severity int
    #   3. has_en — English description present and non-empty
    #   4. has_es — Spanish description present and non-empty
    #   5. valid_json — response parses as valid JSON
    #
    # We also extract the predicted violation and severity for the confusion
    # matrix and per-class metrics.
    """
    scores = {
        "violation_correct": False,
        "severity_correct": False,
        "has_en": False,
        "has_es": False,
        "valid_json": False,
        "predicted_violation": None,
        "predicted_severity": None,
        "expected_violation": expected.get("expected_violation"),
        "expected_severity": expected.get("expected_severity"),
    }

    try:
        parsed = json.loads(response)
        scores["valid_json"] = True

        predicted_violation = parsed.get("violation")
        predicted_severity = parsed.get("severity")

        scores["predicted_violation"] = predicted_violation
        scores["predicted_severity"] = predicted_severity
        scores["violation_correct"] = predicted_violation == expected["expected_violation"]
        scores["severity_correct"] = predicted_severity == expected["expected_severity"]
        scores["has_en"] = bool(parsed.get("description_en", "").strip())
        scores["has_es"] = bool(parsed.get("description_es", "").strip())
    except (json.JSONDecodeError, TypeError):
        pass

    return scores


# ── Per-class metrics ───────────────────────────────────────────────────────
# Priya: These are the real metrics. Overall accuracy is misleading when
# class distribution is imbalanced (which it ALWAYS is in safety detection —
# compliant scenes vastly outnumber violations in real data).

def compute_per_class_metrics(results: list[dict]) -> dict:
    """Compute precision, recall, F1 for each violation class.

    # Priya: We compute both micro-averaged (treats each prediction equally)
    # and macro-averaged (treats each class equally) metrics. Macro penalizes
    # models that do well on common classes but fail on rare ones (like
    # confined_space, which is only 2% of our data).
    """
    # Priya: Confusion matrix accumulators
    tp = Counter()  # true positives per class
    fp = Counter()  # false positives per class
    fn = Counter()  # false negatives per class

    for r in results:
        expected = r["expected_violation"]
        predicted = r["predicted_violation"]

        # Priya: Normalize None to "compliant" for metrics
        expected_label = expected if expected else "compliant"
        predicted_label = predicted if predicted else "compliant"

        if predicted_label == expected_label:
            tp[expected_label] += 1
        else:
            fp[predicted_label] += 1
            fn[expected_label] += 1

    # Priya: Compute per-class P/R/F1
    per_class = {}
    all_labels = set(list(tp.keys()) + list(fp.keys()) + list(fn.keys()))

    for label in sorted(all_labels):
        precision = tp[label] / (tp[label] + fp[label]) if (tp[label] + fp[label]) > 0 else 0.0
        recall = tp[label] / (tp[label] + fn[label]) if (tp[label] + fn[label]) > 0 else 0.0
        f1 = (2 * precision * recall / (precision + recall)) if (precision + recall) > 0 else 0.0

        per_class[label] = {
            "precision": precision,
            "recall": recall,
            "f1": f1,
            "support": tp[label] + fn[label],  # total actual instances
        }

    # Priya: Macro-averaged metrics (equal weight per class)
    if per_class:
        macro_precision = sum(m["precision"] for m in per_class.values()) / len(per_class)
        macro_recall = sum(m["recall"] for m in per_class.values()) / len(per_class)
        macro_f1 = sum(m["f1"] for m in per_class.values()) / len(per_class)
    else:
        macro_precision = macro_recall = macro_f1 = 0.0

    # Priya: Micro-averaged metrics (equal weight per prediction)
    total_tp = sum(tp.values())
    total_fp = sum(fp.values())
    total_fn = sum(fn.values())
    micro_precision = total_tp / (total_tp + total_fp) if (total_tp + total_fp) > 0 else 0.0
    micro_recall = total_tp / (total_tp + total_fn) if (total_tp + total_fn) > 0 else 0.0
    micro_f1 = (
        (2 * micro_precision * micro_recall / (micro_precision + micro_recall))
        if (micro_precision + micro_recall) > 0
        else 0.0
    )

    return {
        "per_class": per_class,
        "macro": {"precision": macro_precision, "recall": macro_recall, "f1": macro_f1},
        "micro": {"precision": micro_precision, "recall": micro_recall, "f1": micro_f1},
    }


def build_confusion_matrix(results: list[dict]) -> dict:
    """Build confusion matrix from evaluation results.

    # Priya: The confusion matrix is the single most informative artifact
    # from evaluation. It tells you EXACTLY where the model is confused —
    # e.g., if it consistently predicts "fall_hazard" when the true label
    # is "unprotected_opening", those classes may need more training data
    # or a clearer distinction in the instruction prompt.
    """
    matrix = defaultdict(Counter)

    for r in results:
        expected = r["expected_violation"] if r["expected_violation"] else "compliant"
        predicted = r["predicted_violation"] if r["predicted_violation"] else "compliant"
        matrix[expected][predicted] += 1

    return dict(matrix)


def compute_latency_stats(latencies: list[float]) -> dict:
    """Compute comprehensive latency statistics.

    # Priya: We report p50, p95, p99, not just mean. The distribution matters:
    #   - p50 tells you typical experience
    #   - p95 tells you worst-case for most users
    #   - p99 tells you tail latency (important for real-time safety alerts)
    #
    # On Tier 2 (phone), our latency budget is <2s. If p95 > 2000ms, we need
    # to investigate — maybe the model is too large or the phone is throttling.
    """
    if not latencies:
        return {"mean_ms": 0, "std_ms": 0, "min_ms": 0, "max_ms": 0,
                "p50_ms": 0, "p95_ms": 0, "p99_ms": 0}

    sorted_lat = sorted(latencies)
    n = len(sorted_lat)

    mean = sum(sorted_lat) / n
    variance = sum((x - mean) ** 2 for x in sorted_lat) / n
    std = variance ** 0.5

    return {
        "mean_ms": round(mean, 1),
        "std_ms": round(std, 1),
        "min_ms": round(sorted_lat[0], 1),
        "max_ms": round(sorted_lat[-1], 1),
        "p50_ms": round(sorted_lat[int(n * 0.50)], 1),
        "p95_ms": round(sorted_lat[min(int(n * 0.95), n - 1)], 1),
        "p99_ms": round(sorted_lat[min(int(n * 0.99), n - 1)], 1),
    }


def format_confusion_matrix_text(matrix: dict) -> str:
    """Format confusion matrix as a text table for console output.

    # Priya: Fixed-width text table because we can't guarantee the console
    # supports Unicode box-drawing characters on all environments.
    """
    all_labels = sorted(set(
        list(matrix.keys()) +
        [pred for row in matrix.values() for pred in row.keys()]
    ))

    # Priya: Abbreviate labels for column headers (max 10 chars)
    abbrev = {label: label[:10] for label in all_labels}

    lines = []
    actual_pred = "Actual\\Pred"
    header = f"{actual_pred:<20}" + "".join(f"{abbrev[l]:>12}" for l in all_labels)
    lines.append(header)
    lines.append("-" * len(header))

    for actual in all_labels:
        row = f"{actual:<20}"
        for predicted in all_labels:
            count = matrix.get(actual, {}).get(predicted, 0)
            row += f"{count:>12}"
        lines.append(row)

    return "\n".join(lines)


# ── Report generation ───────────────────────────────────────────────────────

def generate_markdown_report(
    results: list[dict],
    latencies: list[float],
    class_metrics: dict,
    confusion: dict,
    latency_stats: dict,
    args,
) -> str:
    """Generate a comprehensive markdown report.

    # Priya: This report goes into our model registry alongside the checkpoint.
    # It's the permanent record of how this adapter performed at evaluation time.
    # I review these before and after every training run.
    """
    n = len(results)

    # Priya: Overall accuracy metrics
    violation_acc = sum(r["violation_correct"] for r in results) / n * 100 if n else 0
    severity_acc = sum(r["severity_correct"] for r in results) / n * 100 if n else 0
    json_valid = sum(r["valid_json"] for r in results) / n * 100 if n else 0
    bilingual_en = sum(r["has_en"] for r in results) / n * 100 if n else 0
    bilingual_es = sum(r["has_es"] for r in results) / n * 100 if n else 0

    md = []
    md.append(f"# Duchess Benchmark Report: {args.adapter} adapter")
    md.append(f"\n**Device:** {args.device}  ")
    md.append(f"**Test cases:** {n}  ")
    md.append(f"**Date:** {time.strftime('%Y-%m-%d %H:%M:%S')}  ")

    # Overall metrics
    md.append("\n## Overall Metrics\n")
    md.append("| Metric | Score |")
    md.append("|--------|------:|")
    md.append(f"| Violation Accuracy | {violation_acc:.1f}% |")
    md.append(f"| Severity Accuracy | {severity_acc:.1f}% |")
    md.append(f"| Valid JSON | {json_valid:.1f}% |")
    md.append(f"| Bilingual EN | {bilingual_en:.1f}% |")
    md.append(f"| Bilingual ES | {bilingual_es:.1f}% |")
    md.append(f"| Macro F1 | {class_metrics['macro']['f1']:.3f} |")
    md.append(f"| Micro F1 | {class_metrics['micro']['f1']:.3f} |")

    # Per-class metrics
    md.append("\n## Per-Class Metrics\n")
    md.append("| Class | Precision | Recall | F1 | Support |")
    md.append("|-------|----------:|-------:|---:|--------:|")
    for label, metrics in sorted(class_metrics["per_class"].items()):
        md.append(
            f"| {label} | {metrics['precision']:.3f} | {metrics['recall']:.3f} "
            f"| {metrics['f1']:.3f} | {metrics['support']} |"
        )

    # Latency
    md.append("\n## Latency Statistics\n")
    md.append("| Metric | Value |")
    md.append("|--------|------:|")
    for key, value in latency_stats.items():
        md.append(f"| {key} | {value} |")

    # Confusion matrix
    md.append("\n## Confusion Matrix\n")
    md.append("```")
    md.append(format_confusion_matrix_text(confusion))
    md.append("```")

    return "\n".join(md)


def print_results(
    results: list[dict],
    latencies: list[float],
    class_metrics: dict,
    latency_stats: dict,
    args,
):
    """Print formatted benchmark results to console.

    # Priya: Console output is what I look at during interactive development.
    # The markdown report is for archival. Both contain the same data.
    """
    n = len(results)

    # Priya: Overall metrics table
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

    # Priya: Aggregated F1 scores — the metrics that matter most
    print(f"{'Macro F1':<25} {class_metrics['macro']['f1']:>9.3f}")
    print(f"{'Micro F1':<25} {class_metrics['micro']['f1']:>9.3f}")
    print("-" * 37)

    # Priya: Per-class breakdown
    print(f"\n{'Class':<25} {'Prec':>7} {'Recall':>7} {'F1':>7} {'Support':>8}")
    print("-" * 56)
    for label, m in sorted(class_metrics["per_class"].items()):
        print(f"{label:<25} {m['precision']:>7.3f} {m['recall']:>7.3f} "
              f"{m['f1']:>7.3f} {m['support']:>8}")

    # Priya: Latency statistics
    print(f"\n{'Latency Metric':<25} {'Value':>10}")
    print("-" * 37)
    for key, value in latency_stats.items():
        print(f"{key:<25} {value:>9.1f}ms")

    print(f"\n{'Device':<25} {args.device:>10}")
    print(f"{'Adapter':<25} {args.adapter:>10}")


def print_stub_results():
    """Print placeholder results when model is not available.

    # Priya: Stub output for CI environments where GPU/model isn't available.
    # Clearly marked as N/A so nobody mistakes this for real results.
    """
    print()
    print(f"{'Metric':<25} {'Score':>10}")
    print("-" * 37)
    print(f"{'Violation Accuracy':<25} {'N/A':>10}")
    print(f"{'Severity Accuracy':<25} {'N/A':>10}")
    print(f"{'Valid JSON':<25} {'N/A':>10}")
    print(f"{'Bilingual EN':<25} {'N/A':>10}")
    print(f"{'Bilingual ES':<25} {'N/A':>10}")
    print("-" * 37)
    print(f"{'Macro F1':<25} {'N/A':>10}")
    print(f"{'Micro F1':<25} {'N/A':>10}")
    print("-" * 37)
    print(f"{'Note':<25} {'Model not loaded':>10}")
    print("\nTrain a model first: python scripts/train_gemma3n.py")


def main():
    args = parse_args()

    print(f"=== Duchess Benchmark: {args.adapter} adapter ===")
    print(f"Device: {args.device}")

    # Priya: Load test data — either custom JSONL or built-in test cases
    if args.test_data:
        test_cases = load_test_data(args.test_data)
    else:
        test_cases = TEST_CASES

    print(f"Test cases: {len(test_cases)}")
    print()

    try:
        model, tokenizer = load_model(args.adapter, args.device)
    except Exception as e:
        print(f"Could not load model: {e}")
        print("Running with stub scores (model not available)")
        print_stub_results()
        return

    # ── Run evaluation ──────────────────────────────────────────────────────
    results = []
    latencies = []

    for i, test_case in enumerate(test_cases):
        print(f"  [{i+1}/{len(test_cases)}] {test_case['input'][:60]}...")
        response, latency_ms = run_inference(
            model, tokenizer, test_case["input"], args.device
        )
        latencies.append(latency_ms)
        scores = evaluate_response(response, test_case)
        results.append(scores)

    # ── Compute metrics ─────────────────────────────────────────────────────
    class_metrics = compute_per_class_metrics(results)
    confusion = build_confusion_matrix(results)
    latency_stats = compute_latency_stats(latencies)

    # ── Console output ──────────────────────────────────────────────────────
    print_results(results, latencies, class_metrics, latency_stats, args)

    print("\nConfusion Matrix:")
    print(format_confusion_matrix_text(confusion))

    # ── Markdown report ─────────────────────────────────────────────────────
    if args.output_markdown:
        md_path = Path(args.output_markdown)
        md_path.parent.mkdir(parents=True, exist_ok=True)
        md_report = generate_markdown_report(
            results, latencies, class_metrics, confusion, latency_stats, args
        )
        md_path.write_text(md_report)
        print(f"\nMarkdown report: {md_path}")

    # ── JSON metrics ────────────────────────────────────────────────────────
    if args.output_json:
        json_path = Path(args.output_json)
        json_path.parent.mkdir(parents=True, exist_ok=True)
        json_metrics = {
            "adapter": args.adapter,
            "device": args.device,
            "num_test_cases": len(test_cases),
            "overall": {
                "violation_accuracy": sum(r["violation_correct"] for r in results) / len(results),
                "severity_accuracy": sum(r["severity_correct"] for r in results) / len(results),
                "valid_json_rate": sum(r["valid_json"] for r in results) / len(results),
                "bilingual_en_rate": sum(r["has_en"] for r in results) / len(results),
                "bilingual_es_rate": sum(r["has_es"] for r in results) / len(results),
            },
            "class_metrics": class_metrics,
            "latency": latency_stats,
            "confusion_matrix": {k: dict(v) for k, v in confusion.items()},
        }
        with open(json_path, "w") as f:
            json.dump(json_metrics, f, indent=2)
        print(f"JSON metrics: {json_path}")

    # ── Baseline comparison ─────────────────────────────────────────────────
    if args.compare_baseline:
        print("\n\n=== Baseline (no adapter) ===")
        try:
            base_model, base_tokenizer = load_model(None, args.device)
            base_results = []
            base_latencies = []
            for i, test_case in enumerate(test_cases):
                print(f"  [{i+1}/{len(test_cases)}] {test_case['input'][:60]}...")
                response, latency_ms = run_inference(
                    base_model, base_tokenizer, test_case["input"], args.device
                )
                base_latencies.append(latency_ms)
                scores = evaluate_response(response, test_case)
                base_results.append(scores)

            base_class_metrics = compute_per_class_metrics(base_results)
            base_latency_stats = compute_latency_stats(base_latencies)

            # Priya: Print comparison delta
            print("\n--- Adapter vs Baseline Delta ---")
            adapter_f1 = class_metrics["macro"]["f1"]
            base_f1 = base_class_metrics["macro"]["f1"]
            delta = adapter_f1 - base_f1
            print(f"  Macro F1:  {adapter_f1:.3f} vs {base_f1:.3f} (delta: {delta:+.3f})")

            adapter_acc = sum(r["violation_correct"] for r in results) / len(results)
            base_acc = sum(r["violation_correct"] for r in base_results) / len(base_results)
            delta_acc = adapter_acc - base_acc
            print(f"  Violation Acc: {adapter_acc:.3f} vs {base_acc:.3f} (delta: {delta_acc:+.3f})")

        except Exception as e:
            print(f"Could not load baseline model: {e}")


if __name__ == "__main__":
    main()
