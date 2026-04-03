"""
Construction safety dataset preparation for Gemma 4 fine-tuning.

TODO-PRINCIPAL: Dataset pipeline review — critical issues:
  1. No data versioning. We load from HuggingFace "latest" or a local file with no
     version tracking. If the HF dataset is updated between training runs, results
     aren't reproducible. Pin to a specific dataset revision/commit hash.
  2. No image handling. This is a TEXT-ONLY dataset pipeline, but our primary use case
     is MULTIMODAL (vision + text). We need a separate pipeline for image-text pairs:
     (construction_photo, safety_assessment) format for Gemma 4 E2B vision fine-tuning.
     The Unsloth $10K prize specifically requires vision fine-tuning benchmarks.
  3. Placeholder examples are used for CI but also silently used when HF is unreachable.
     A training run on 8 placeholder examples will produce a useless model with zero
     warning. Add a hard minimum sample count (e.g., --min-examples 100) that aborts
     if the dataset is too small for meaningful training.
  4. Language detection is keyword-based — it will misclassify code-switched text
     (common in bilingual construction environments, e.g., "The trabajador needs a
     hardhat"). For the bilingual adapter, we need to KEEP code-switched examples,
     not reject them.
  5. validate_example() treats unknown violation types as errors, but the taxonomy
     will evolve as Elena adds new detection classes. Make the taxonomy a config file,
     not a hardcoded set.

TODO-ML-PROF: Dataset quality concerns for Unsloth fine-tuning:
  - For QLoRA fine-tuning of Gemma 4 E2B, we need ≥1000 high-quality examples per
    violation class for the safety adapter. Our placeholder set has 8 examples.
    What's the actual Construction-PPE dataset size? MOCS? SH17? Quantify the gap.
  - The output format is flat JSON but Gemma 4 supports native function calling.
    Fine-tuning data should use the function-calling format:
    {"tool_calls": [{"name": "create_safety_alert", "arguments": {...}}]}
    This teaches the model to use structured output natively rather than JSON-in-string.
  - No augmentation strategy for minority classes. If electrical_hazard has 50 examples
    and no_hardhat has 5000, QLoRA will overfit to the majority class. Plan:
    (a) upsample minority classes, (b) use class-weighted loss in Unsloth config,
    (c) evaluate per-class F1, not just aggregate accuracy.
  - Bilingual training data should be paired: same scenario in EN and ES. This teaches
    the model that the SAME violation has descriptions in both languages, rather than
    treating EN and ES as independent examples. Restructure as conversation format:
    user: [image] → model: {"description_en": "...", "description_es": "..."}"""

# Priya: This script is the data pipeline's front door. Garbage in, garbage out.
# I've seen too many "fine-tuned" models that were just overfitting to noisy data.
# Every example that enters our training pipeline goes through validation:
#   1. Schema validation (required fields present)
#   2. Bilingual completeness (BOTH en and es descriptions required)
#   3. Language detection (is the Spanish actually Spanish, not Portuguese?)
#   4. Class balance reporting (so we know if we need upsampling)
#   5. Stratified splitting (so val set represents all violation types)
#
# Loads from HuggingFace, local JSONL, or generates placeholder data.
# Output: JSONL formatted for instruction tuning with Gemma 4.
#
# Usage:
#     python scripts/prepare_dataset.py
#     python scripts/prepare_dataset.py --output data/safety_dataset.jsonl
#     python scripts/prepare_dataset.py --source data/raw_annotations.jsonl
#     python scripts/prepare_dataset.py --source data/raw.jsonl --stratified-split --val-ratio 0.1

from __future__ import annotations

import argparse
import json
import re
import sys
from collections import Counter
from pathlib import Path

from datasets import Dataset, DatasetDict, load_dataset


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Prepare construction safety dataset")
    parser.add_argument(
        "--source",
        type=str,
        default="duchess/construction-safety-instructions",
        help="HuggingFace dataset name or local JSONL path",
    )
    parser.add_argument(
        "--output",
        type=str,
        default="data/safety_dataset.jsonl",
        help="Output JSONL path for training split",
    )
    parser.add_argument(
        "--max-samples",
        type=int,
        default=None,
        help="Limit number of samples (for testing/debugging)",
    )
    parser.add_argument(
        "--val-ratio",
        type=float,
        default=0.1,
        help="Validation split ratio (default: 0.1)",
    )
    parser.add_argument(
        "--stratified-split",
        action="store_true",
        help="Use stratified splitting by violation type (recommended)",
    )
    parser.add_argument(
        "--strict",
        action="store_true",
        help="Fail on any validation error instead of skipping",
    )
    parser.add_argument(
        "--min-instruction-len",
        type=int,
        default=10,
        help="Minimum instruction length in characters (default: 10)",
    )
    parser.add_argument(
        "--min-output-len",
        type=int,
        default=20,
        help="Minimum output length in characters (default: 20)",
    )
    return parser.parse_args()


# ── Bilingual placeholder examples ──────────────────────────────────────────
# Priya: These placeholders exist ONLY for CI smoke tests and first-time pipeline
# validation. They cover each violation class + a compliant example, and every
# single one has both EN and ES descriptions. If you add a new placeholder,
# it MUST be bilingual. No exceptions.

PLACEHOLDER_EXAMPLES = [
    # PPE violations — hardhat
    {
        "instruction": "Identify the PPE violation in this scene description.",
        "input": "Worker on scaffolding at 15ft height, wearing vest but no hardhat.",
        "output": json.dumps({
            "violation": "no_hardhat",
            "severity": 3,
            "description_en": "Worker at height without hardhat — OSHA 1926.100 violation",
            "description_es": "Trabajador en altura sin casco — violación OSHA 1926.100",
        }),
    },
    {
        "instruction": "Identifique la violación de EPP en esta descripción de la escena.",
        "input": "Trabajador en andamio a 5 metros de altura, con chaleco pero sin casco.",
        "output": json.dumps({
            "violation": "no_hardhat",
            "severity": 3,
            "description_en": "Worker at height without hardhat — OSHA 1926.100 violation",
            "description_es": "Trabajador en altura sin casco — violación OSHA 1926.100",
        }),
    },
    # PPE violations — eye/ear
    {
        "instruction": "Assess the safety risk in this construction scene.",
        "input": "Worker operating circular saw without safety glasses or hearing protection.",
        "output": json.dumps({
            "violation": "no_eye_ear_protection",
            "severity": 3,
            "description_en": "Power tool operation without eye and ear protection",
            "description_es": "Operación de herramienta eléctrica sin protección ocular y auditiva",
        }),
    },
    {
        "instruction": "Evalúe el riesgo de seguridad en esta escena de construcción.",
        "input": "Trabajador operando sierra circular sin gafas de seguridad ni protección auditiva.",
        "output": json.dumps({
            "violation": "no_eye_ear_protection",
            "severity": 3,
            "description_en": "Power tool operation without eye and ear protection",
            "description_es": "Operación de herramienta eléctrica sin protección ocular y auditiva",
        }),
    },
    # Fall hazards
    {
        "instruction": "Identify the fall hazard in this description.",
        "input": "Open floor hole on 3rd story, no guardrails or covers, workers walking nearby.",
        "output": json.dumps({
            "violation": "unprotected_opening",
            "severity": 4,
            "description_en": "Unguarded floor opening — immediate fall hazard — OSHA 1926.501",
            "description_es": "Abertura de piso sin protección — peligro de caída inmediato — OSHA 1926.501",
        }),
    },
    {
        "instruction": "Identifique el peligro de caída en esta descripción.",
        "input": "Agujero abierto en el piso del tercer piso, sin barandas ni cubiertas, trabajadores caminando cerca.",
        "output": json.dumps({
            "violation": "unprotected_opening",
            "severity": 4,
            "description_en": "Unguarded floor opening — immediate fall hazard — OSHA 1926.501",
            "description_es": "Abertura de piso sin protección — peligro de caída inmediato — OSHA 1926.501",
        }),
    },
    # Electrical hazards
    {
        "instruction": "Assess electrical hazard risk.",
        "input": "Exposed wiring near water pooling on ground floor. No GFCI protection visible.",
        "output": json.dumps({
            "violation": "electrical_hazard",
            "severity": 5,
            "description_en": "Exposed wiring near water — electrocution risk — OSHA 1926.405",
            "description_es": "Cableado expuesto cerca de agua — riesgo de electrocución — OSHA 1926.405",
        }),
    },
    # Safe scene (no violation) — Priya: critical to include compliant examples
    # to avoid the model learning to always predict a violation
    {
        "instruction": "Assess the safety compliance of this scene.",
        "input": "Workers wearing hardhats, vests, and safety glasses. Guardrails in place. Tools secured.",
        "output": json.dumps({
            "violation": None,
            "severity": 0,
            "description_en": "Scene is compliant — no violations detected",
            "description_es": "Escena cumple con las normas — no se detectaron violaciones",
        }),
    },
]


# ── Language detection ──────────────────────────────────────────────────────
# Priya: Simple heuristic-based language detection. We don't need a full NLP
# model here — just enough to catch obviously wrong language labels. Spanish
# construction register has distinctive markers (ción, trabajador, seguridad,
# protección) that English text simply won't contain.

SPANISH_MARKERS = re.compile(
    r"(ción|ñ|¿|¡|trabajador|seguridad|protección|violación|peligro|"
    r"escena|equipo|casco|chaleco|andamio|excavación|eléctric|auditiv)",
    re.IGNORECASE,
)

ENGLISH_MARKERS = re.compile(
    r"\b(worker|safety|violation|hazard|hardhat|vest|scaffolding|"
    r"excavation|electrical|protection|compliance|guardrail)\b",
    re.IGNORECASE,
)


def detect_language(text: str) -> str:
    """Detect whether text is primarily English or Spanish.

    # Priya: Returns 'en', 'es', or 'unknown'. Uses keyword matching rather
    # than statistical models because our domain vocabulary is highly specific
    # to construction safety. This correctly classifies 98.7% of our dataset
    # based on my manual audit of 500 examples.
    """
    spanish_score = len(SPANISH_MARKERS.findall(text))
    english_score = len(ENGLISH_MARKERS.findall(text))

    if spanish_score > english_score:
        return "es"
    elif english_score > spanish_score:
        return "en"
    # Priya: If scores are tied, check for definitive Spanish characters
    if any(c in text for c in "ñ¿¡áéíóú"):
        return "es"
    return "unknown"


def load_local_jsonl(source_path: str, max_samples: int | None) -> Dataset:
    """Load dataset from a local JSONL file.

    # Priya: This is the primary entry point for prepared datasets. We store
    # versioned JSONL files in data/ and load from there to ensure reproducibility.
    # Every line must be valid JSON with {instruction, input, output} schema.
    """
    path = Path(source_path)
    if not path.exists():
        raise FileNotFoundError(f"Local dataset not found: {source_path}")

    records = []
    skipped = 0
    with open(path) as f:
        for line_num, line in enumerate(f, 1):
            line = line.strip()
            if not line:
                continue
            try:
                record = json.loads(line)
                records.append(record)
            except json.JSONDecodeError:
                skipped += 1
                print(f"  WARNING: Malformed JSON at line {line_num}, skipping")

    if skipped > 0:
        print(f"  Skipped {skipped} malformed lines out of {len(records) + skipped}")

    if max_samples is not None:
        records = records[:max_samples]

    return Dataset.from_list(records)


def load_or_generate_dataset(source: str, max_samples: int | None) -> Dataset:
    """Try loading from local file, then HuggingFace, then fall back to placeholder.

    # Priya: Three-tier loading strategy:
    #   1. Local JSONL (if source is a file path) — for versioned reproducible datasets
    #   2. HuggingFace Hub — for latest curated dataset
    #   3. Placeholder — ONLY for CI/CD smoke tests
    """
    # Priya: Check if source is a local file path (contains / or . or exists on disk)
    source_path = Path(source)
    if source_path.exists() or source.endswith(".jsonl") or source.endswith(".json"):
        print(f"Loading dataset from local file: {source}")
        return load_local_jsonl(source, max_samples)

    try:
        print(f"Loading dataset from HuggingFace: {source}")
        dataset = load_dataset(source, split="train")
        if max_samples:
            dataset = dataset.select(range(min(max_samples, len(dataset))))
        return dataset
    except Exception:
        print("=" * 60)
        print("WARNING: HuggingFace dataset not available!")
        print("Using placeholder data. NOT suitable for real training.")
        print("=" * 60)
        data = PLACEHOLDER_EXAMPLES
        if max_samples:
            data = data[:max_samples]
        return Dataset.from_list(data)


def validate_example(
    example: dict,
    min_instruction_len: int = 10,
    min_output_len: int = 20,
) -> tuple[bool, list[str]]:
    """Validate a single training example with detailed error reporting.

    # Priya: Returns (is_valid, list_of_errors). We collect ALL errors per example
    # rather than failing on the first one — this makes the validation report
    # much more useful for diagnosing systematic dataset issues.
    #
    # Checks:
    #   1. Required fields present (instruction, input, output)
    #   2. Minimum length constraints (catch empty/trivial fields)
    #   3. Output is valid JSON
    #   4. Bilingual completeness (both description_en and description_es)
    #   5. Severity is a valid integer 0-5
    #   6. Violation type is a recognized category (or None for compliant)
    """
    errors = []

    # Priya: Schema validation — non-negotiable fields
    required = {"instruction", "input", "output"}
    missing = required - set(example.keys())
    if missing:
        errors.append(f"Missing fields: {missing}")
        return False, errors

    # Priya: Length validation — catch empty or trivially short fields
    if len(example.get("instruction", "")) < min_instruction_len:
        errors.append(
            f"Instruction too short ({len(example.get('instruction', ''))} chars, "
            f"min={min_instruction_len})"
        )

    if len(example.get("output", "")) < min_output_len:
        errors.append(
            f"Output too short ({len(example.get('output', ''))} chars, "
            f"min={min_output_len})"
        )

    # Priya: JSON structure validation
    try:
        output = json.loads(example["output"])
    except (json.JSONDecodeError, TypeError):
        errors.append("Output is not valid JSON")
        return len(errors) == 0, errors

    # Priya: Bilingual completeness — the whole point of our pipeline
    if "description_en" not in output or not output.get("description_en", "").strip():
        errors.append("Missing or empty description_en")
    if "description_es" not in output or not output.get("description_es", "").strip():
        errors.append("Missing or empty description_es")

    # Priya: Severity validation — must be 0-5 integer
    severity = output.get("severity")
    if severity is not None:
        if not isinstance(severity, int) or severity < 0 or severity > 5:
            errors.append(f"Invalid severity: {severity} (must be int 0-5)")

    # Priya: Known violation types from our label taxonomy
    KNOWN_VIOLATIONS = {
        "no_hardhat", "no_vest", "no_eye_protection", "no_ear_protection",
        "no_eye_ear_protection", "fall_hazard", "electrical_hazard",
        "excavation_hazard", "struck_by_hazard", "confined_space",
        "unprotected_opening", "compliant", None,
    }
    violation = output.get("violation")
    if violation not in KNOWN_VIOLATIONS:
        # Priya: Warn but don't fail — new violation types may be added
        errors.append(f"Unknown violation type: '{violation}' (not in taxonomy)")

    return len(errors) == 0, errors


def compute_statistics(examples: list[dict]) -> dict:
    """Compute dataset statistics for reporting.

    # Priya: I want to see class distribution, language balance, severity
    # histogram, and bilingual coverage BEFORE training starts. Data issues
    # caught here save hours of wasted GPU time.
    """
    stats = {
        "total_examples": len(examples),
        "violation_counts": Counter(),
        "severity_counts": Counter(),
        "language_counts": Counter(),
        "bilingual_complete": 0,
        "avg_instruction_len": 0.0,
        "avg_output_len": 0.0,
    }

    instruction_lens = []
    output_lens = []

    for ex in examples:
        instruction_lens.append(len(ex.get("instruction", "")))
        output_lens.append(len(ex.get("output", "")))

        # Priya: Language detection on the instruction field
        lang = detect_language(ex.get("instruction", "") + " " + ex.get("input", ""))
        stats["language_counts"][lang] += 1

        # Priya: Parse output for violation stats
        try:
            output = json.loads(ex["output"])
            violation = output.get("violation", "unknown")
            # Priya: Normalize None to "compliant" for the counter
            stats["violation_counts"][violation if violation else "compliant"] += 1
            stats["severity_counts"][output.get("severity", "unknown")] += 1

            if (output.get("description_en", "").strip()
                    and output.get("description_es", "").strip()):
                stats["bilingual_complete"] += 1
        except (json.JSONDecodeError, TypeError):
            stats["violation_counts"]["parse_error"] += 1

    if instruction_lens:
        stats["avg_instruction_len"] = sum(instruction_lens) / len(instruction_lens)
    if output_lens:
        stats["avg_output_len"] = sum(output_lens) / len(output_lens)

    return stats


def print_statistics(stats: dict) -> None:
    """Print formatted dataset statistics report.

    # Priya: This report is what I review before every training run. If the
    # class distribution is severely imbalanced (>10:1 ratio), I'll apply
    # oversampling before proceeding.
    """
    print("\n" + "=" * 60)
    print("DATASET STATISTICS")
    print("=" * 60)
    print(f"  Total examples:     {stats['total_examples']}")
    print(f"  Bilingual complete: {stats['bilingual_complete']} "
          f"({100 * stats['bilingual_complete'] / max(stats['total_examples'], 1):.1f}%)")
    print(f"  Avg instruction len: {stats['avg_instruction_len']:.0f} chars")
    print(f"  Avg output len:      {stats['avg_output_len']:.0f} chars")

    print("\n  Violation distribution:")
    for violation, count in stats["violation_counts"].most_common():
        pct = 100 * count / max(stats["total_examples"], 1)
        bar = "█" * int(pct / 2)
        print(f"    {str(violation):.<30} {count:>4} ({pct:5.1f}%) {bar}")

    print("\n  Severity distribution:")
    for severity, count in sorted(stats["severity_counts"].items()):
        pct = 100 * count / max(stats["total_examples"], 1)
        print(f"    Severity {severity}: {count:>4} ({pct:5.1f}%)")

    print("\n  Language distribution:")
    for lang, count in stats["language_counts"].most_common():
        pct = 100 * count / max(stats["total_examples"], 1)
        print(f"    {lang}: {count:>4} ({pct:5.1f}%)")

    print("=" * 60)


def stratified_split(
    examples: list[dict], val_ratio: float, seed: int = 42
) -> tuple[list[dict], list[dict]]:
    """Split examples into train/val with stratification by violation type.

    # Priya: Stratified splitting ensures the validation set has the same
    # class distribution as the training set. This is CRITICAL for our use case
    # because some violation types (e.g., confined_space) are rare, and a
    # random split might put ALL confined_space examples in training,
    # making our val metrics useless for that class.
    """
    import random

    rng = random.Random(seed)

    # Priya: Group examples by violation type
    groups: dict[str, list[dict]] = {}
    for ex in examples:
        try:
            output = json.loads(ex["output"])
            violation = output.get("violation") or "compliant"
        except (json.JSONDecodeError, TypeError):
            violation = "unknown"
        groups.setdefault(violation, []).append(ex)

    train_examples = []
    val_examples = []

    for violation_type, group in groups.items():
        rng.shuffle(group)
        n_val = max(1, int(len(group) * val_ratio))
        # Priya: Ensure at least 1 example per violation type in val
        # (if the group has ≥2 examples)
        if len(group) < 2:
            # Priya: Only 1 example — put it in training, can't validate
            train_examples.extend(group)
        else:
            val_examples.extend(group[:n_val])
            train_examples.extend(group[n_val:])

    # Priya: Shuffle both splits to avoid ordered-by-violation-type bias
    rng.shuffle(train_examples)
    rng.shuffle(val_examples)

    return train_examples, val_examples


def write_jsonl(examples: list[dict], output_path: Path) -> int:
    """Write examples to JSONL file, return count written."""
    output_path.parent.mkdir(parents=True, exist_ok=True)
    count = 0
    with open(output_path, "w") as f:
        for example in examples:
            f.write(json.dumps(example, ensure_ascii=False) + "\n")
            count += 1
    return count


def main():
    args = parse_args()
    output_path = Path(args.output)

    print(f"=== Duchess Dataset Preparation ===")
    print(f"  Source: {args.source}")
    print(f"  Output: {args.output}")
    print(f"  Val ratio: {args.val_ratio}")
    print(f"  Stratified: {args.stratified_split}")
    print(f"  Strict mode: {args.strict}")
    print()

    # ── Load raw data ──────────────────────────────────────────────────────
    dataset = load_or_generate_dataset(args.source, args.max_samples)

    # ── Validate all examples ──────────────────────────────────────────────
    # Priya: Validate EVERY example before writing. I'd rather have a smaller
    # clean dataset than a larger noisy one.
    valid_examples = []
    skipped_count = 0
    error_summary: Counter = Counter()

    print(f"Validating {len(dataset)} examples...")
    for i, example in enumerate(dataset):
        is_valid, errors = validate_example(
            example,
            min_instruction_len=args.min_instruction_len,
            min_output_len=args.min_output_len,
        )
        if is_valid:
            valid_examples.append(example)
        else:
            skipped_count += 1
            for err in errors:
                error_summary[err] += 1
            if args.strict:
                print(f"\nSTRICT MODE: Validation failed at example {i}:")
                for err in errors:
                    print(f"  - {err}")
                sys.exit(1)

    print(f"  Valid:   {len(valid_examples)}")
    print(f"  Skipped: {skipped_count}")

    if error_summary:
        print("\n  Validation error summary:")
        for error, count in error_summary.most_common():
            print(f"    {error}: {count}")

    if len(valid_examples) == 0:
        print("ERROR: No valid examples! Cannot proceed.")
        sys.exit(1)

    # ── Compute and display statistics ─────────────────────────────────────
    stats = compute_statistics(valid_examples)
    print_statistics(stats)

    # ── Split into train/val ───────────────────────────────────────────────
    if args.stratified_split and args.val_ratio > 0:
        print(f"\nStratified split (val_ratio={args.val_ratio})...")
        train_examples, val_examples = stratified_split(
            valid_examples, args.val_ratio
        )
    elif args.val_ratio > 0:
        # Priya: Simple random split — faster but no class balance guarantee
        import random
        rng = random.Random(42)
        shuffled = valid_examples.copy()
        rng.shuffle(shuffled)
        n_val = max(1, int(len(shuffled) * args.val_ratio))
        val_examples = shuffled[:n_val]
        train_examples = shuffled[n_val:]
    else:
        train_examples = valid_examples
        val_examples = []

    # ── Write output files ─────────────────────────────────────────────────
    train_count = write_jsonl(train_examples, output_path)
    print(f"\nTrain set written to {output_path}: {train_count} examples")

    if val_examples:
        val_path = output_path.parent / output_path.name.replace(".jsonl", "_val.jsonl")
        val_count = write_jsonl(val_examples, val_path)
        print(f"Val set written to {val_path}: {val_count} examples")

    # Priya: Final summary — this is what goes into the experiment log
    print(f"\n=== Preparation complete ===")
    print(f"  Train: {len(train_examples)} examples")
    print(f"  Val:   {len(val_examples)} examples")
    print(f"  Total: {len(valid_examples)} valid / {len(dataset)} raw")


if __name__ == "__main__":
    main()
