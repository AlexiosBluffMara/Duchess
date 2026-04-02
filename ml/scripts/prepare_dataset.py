"""
Construction safety dataset preparation for Gemma 3n fine-tuning.

Loads from HuggingFace (or generates placeholder), formats for instruction tuning:
  {"instruction": ..., "input": ..., "output": ...}
All examples include English + Spanish bilingual pairs.

Usage:
    python scripts/prepare_dataset.py
    python scripts/prepare_dataset.py --output data/safety_dataset.jsonl
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

from datasets import Dataset, load_dataset


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Prepare construction safety dataset")
    parser.add_argument(
        "--source",
        type=str,
        default="duchess/construction-safety-instructions",
        help="HuggingFace dataset name or local path",
    )
    parser.add_argument(
        "--output",
        type=str,
        default="data/safety_dataset.jsonl",
        help="Output JSONL path",
    )
    parser.add_argument(
        "--max-samples",
        type=int,
        default=None,
        help="Limit number of samples (for testing)",
    )
    return parser.parse_args()


# ── Bilingual placeholder examples ──────────────────────────────────────────

PLACEHOLDER_EXAMPLES = [
    # PPE violations
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
    # Safe scene (no violation)
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


def load_or_generate_dataset(source: str, max_samples: int | None) -> Dataset:
    """Try loading from HuggingFace, fall back to placeholder data."""
    try:
        print(f"Loading dataset from HuggingFace: {source}")
        dataset = load_dataset(source, split="train")
        if max_samples:
            dataset = dataset.select(range(min(max_samples, len(dataset))))
        return dataset
    except Exception:
        print("HuggingFace dataset not available — generating placeholder data")
        data = PLACEHOLDER_EXAMPLES
        if max_samples:
            data = data[:max_samples]
        return Dataset.from_list(data)


def validate_example(example: dict) -> bool:
    """Ensure example has required fields and bilingual output."""
    required = {"instruction", "input", "output"}
    if not required.issubset(example.keys()):
        return False

    try:
        output = json.loads(example["output"])
        return "description_en" in output and "description_es" in output
    except (json.JSONDecodeError, TypeError):
        return False


def main():
    args = parse_args()
    output_path = Path(args.output)
    output_path.parent.mkdir(parents=True, exist_ok=True)

    dataset = load_or_generate_dataset(args.source, args.max_samples)

    valid_count = 0
    skipped_count = 0

    with open(output_path, "w") as f:
        for example in dataset:
            if validate_example(example):
                f.write(json.dumps(example, ensure_ascii=False) + "\n")
                valid_count += 1
            else:
                skipped_count += 1

    print(f"Dataset written to {output_path}")
    print(f"  Valid examples: {valid_count}")
    print(f"  Skipped: {skipped_count}")


if __name__ == "__main__":
    main()
