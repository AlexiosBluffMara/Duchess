"""
Pytest fixtures for the Duchess ML test suite.

# Priya: Shared fixtures for all ML tests. These provide:
#   - Sample dataset (JSONL format with bilingual examples)
#   - Adapter config dicts (safety + spanish_jargon)
#   - Temporary directories for training outputs
#   - Mock model and tokenizer objects
#
# IMPORTANT: These fixtures use ONLY synthetic data. We never load real
# models or real datasets in unit tests — those go in integration tests
# on GPU machines. Unit tests must run in <30s on CPU-only CI.
"""

from __future__ import annotations

import json
import os
import tempfile
from pathlib import Path

import pytest


# ── Sample dataset examples ─────────────────────────────────────────────────
# Priya: Minimal but complete examples covering all validation paths.
# Each example has instruction, input, output (JSON with bilingual fields).

SAMPLE_EXAMPLES = [
    {
        "instruction": "Identify the PPE violation in this scene description.",
        "input": "Worker on scaffolding at 15ft height, wearing vest but no hardhat.",
        "output": json.dumps({
            "violation": "no_hardhat",
            "severity": 3,
            "description_en": "Worker at height without hardhat — OSHA 1926.100",
            "description_es": "Trabajador en altura sin casco — OSHA 1926.100",
        }),
    },
    {
        "instruction": "Identifique la violación de EPP en esta descripción.",
        "input": "Trabajador en andamio a 5m de altura, con chaleco pero sin casco.",
        "output": json.dumps({
            "violation": "no_hardhat",
            "severity": 3,
            "description_en": "Worker at height without hardhat",
            "description_es": "Trabajador en altura sin casco",
        }),
    },
    {
        "instruction": "Assess the safety risk level.",
        "input": "Excavation site, no barricades, workers within 6ft of edge.",
        "output": json.dumps({
            "violation": "fall_hazard",
            "severity": 4,
            "description_en": "Unprotected excavation edge",
            "description_es": "Borde de excavación sin protección",
        }),
    },
    {
        "instruction": "Evalúe el riesgo de seguridad en esta escena.",
        "input": "Sitio de excavación, sin barricadas, trabajadores cerca del borde.",
        "output": json.dumps({
            "violation": "fall_hazard",
            "severity": 4,
            "description_en": "Unprotected excavation edge",
            "description_es": "Borde de excavación sin protección",
        }),
    },
    {
        "instruction": "Assess the safety compliance of this scene.",
        "input": "Workers wearing hardhats, vests, and safety glasses. Guardrails in place.",
        "output": json.dumps({
            "violation": None,
            "severity": 0,
            "description_en": "Scene is compliant — no violations detected",
            "description_es": "Escena cumple con las normas — no se detectaron violaciones",
        }),
    },
    {
        "instruction": "Identify the electrical hazard.",
        "input": "Exposed wiring near water pooling on ground floor.",
        "output": json.dumps({
            "violation": "electrical_hazard",
            "severity": 5,
            "description_en": "Exposed wiring near water — electrocution risk",
            "description_es": "Cableado expuesto cerca de agua — riesgo de electrocución",
        }),
    },
    {
        "instruction": "Assess worker safety equipment.",
        "input": "Worker operating circular saw without safety glasses.",
        "output": json.dumps({
            "violation": "no_eye_protection",
            "severity": 3,
            "description_en": "Power tool operation without eye protection",
            "description_es": "Operación de herramienta eléctrica sin protección ocular",
        }),
    },
    {
        "instruction": "Evaluate crane operation safety.",
        "input": "Worker operating crane without signal person, blind lift.",
        "output": json.dumps({
            "violation": "struck_by_hazard",
            "severity": 4,
            "description_en": "Crane lift without signal person",
            "description_es": "Operación de grúa sin señalero",
        }),
    },
]


@pytest.fixture
def sample_examples():
    """Provide sample training examples as a list of dicts.

    # Priya: These are valid, bilingual, schema-compliant examples that
    # should pass all validation checks. Use them as the "golden path"
    # in tests.
    """
    return SAMPLE_EXAMPLES.copy()


@pytest.fixture
def sample_jsonl_file(tmp_path, sample_examples):
    """Write sample examples to a temporary JSONL file.

    # Priya: Creates a real JSONL file on disk for testing the data loading
    # pipeline end-to-end. The file is in tmp_path so pytest cleans it up.
    """
    jsonl_path = tmp_path / "test_dataset.jsonl"
    with open(jsonl_path, "w") as f:
        for example in sample_examples:
            f.write(json.dumps(example, ensure_ascii=False) + "\n")
    return jsonl_path


@pytest.fixture
def invalid_examples():
    """Examples that should FAIL validation.

    # Priya: Each example has a specific defect. Tests should verify that
    # validate_example catches each one.
    """
    return [
        # Missing 'output' field entirely
        {
            "instruction": "Identify the violation.",
            "input": "Worker without hardhat.",
        },
        # Output is not valid JSON
        {
            "instruction": "Identify the violation.",
            "input": "Worker without hardhat.",
            "output": "This is not JSON",
        },
        # Missing description_es (not bilingual)
        {
            "instruction": "Assess the scene.",
            "input": "Worker without vest.",
            "output": json.dumps({
                "violation": "no_vest",
                "severity": 3,
                "description_en": "No vest",
            }),
        },
        # Missing description_en (not bilingual)
        {
            "instruction": "Assess the scene.",
            "input": "Worker without vest.",
            "output": json.dumps({
                "violation": "no_vest",
                "severity": 3,
                "description_es": "Sin chaleco",
            }),
        },
        # Invalid severity (out of range)
        {
            "instruction": "Assess the scene.",
            "input": "Worker without vest.",
            "output": json.dumps({
                "violation": "no_vest",
                "severity": 99,
                "description_en": "No vest",
                "description_es": "Sin chaleco",
            }),
        },
        # Instruction too short
        {
            "instruction": "Hi",
            "input": "Worker without hardhat on scaffolding.",
            "output": json.dumps({
                "violation": "no_hardhat",
                "severity": 3,
                "description_en": "No hardhat",
                "description_es": "Sin casco",
            }),
        },
    ]


@pytest.fixture
def safety_adapter_config():
    """Safety adapter configuration dict.

    # Priya: Matches the schema in adapters/safety/config.json. Used to test
    # config loading and hyperparameter propagation.
    """
    return {
        "adapter_name": "safety",
        "base_model": "google/gemma-3n-e2b-it",
        "dataset": "duchess/construction-safety-instructions",
        "lora_config": {
            "r": 16,
            "lora_alpha": 32,
            "lora_dropout": 0.05,
            "target_modules": ["q_proj", "k_proj", "v_proj", "o_proj"],
            "bias": "none",
            "task_type": "CAUSAL_LM",
        },
        "training_config": {
            "epochs": 3,
            "batch_size": 4,
            "learning_rate": 2e-4,
            "warmup_ratio": 0.03,
            "weight_decay": 0.01,
            "max_seq_length": 2048,
        },
        "labels": [
            "no_hardhat", "no_vest", "no_eye_protection", "no_ear_protection",
            "fall_hazard", "electrical_hazard", "excavation_hazard",
            "struck_by_hazard", "confined_space", "compliant",
        ],
        "languages": ["en", "es"],
    }


@pytest.fixture
def spanish_jargon_adapter_config():
    """Spanish jargon adapter configuration dict.

    # Priya: Higher epoch count (5 vs 3) and lower LR (1e-4 vs 2e-4) because
    # the jargon dataset is smaller and we need to preserve base Spanish ability.
    """
    return {
        "adapter_name": "spanish_jargon",
        "base_model": "google/gemma-3n-e2b-it",
        "dataset": "duchess/construction-spanish-jargon",
        "lora_config": {
            "r": 16,
            "lora_alpha": 32,
            "lora_dropout": 0.05,
            "target_modules": ["q_proj", "k_proj", "v_proj", "o_proj"],
            "bias": "none",
            "task_type": "CAUSAL_LM",
        },
        "training_config": {
            "epochs": 5,
            "batch_size": 4,
            "learning_rate": 1e-4,
            "warmup_ratio": 0.05,
            "weight_decay": 0.01,
            "max_seq_length": 2048,
        },
        "languages": ["en", "es"],
    }


@pytest.fixture
def tmp_output_dir(tmp_path):
    """Temporary output directory for training/export artifacts.

    # Priya: Each test gets its own isolated output dir so tests don't
    # interfere with each other. Pytest cleans these up automatically.
    """
    output_dir = tmp_path / "outputs"
    output_dir.mkdir()
    return output_dir


@pytest.fixture
def tmp_adapters_dir(tmp_path):
    """Temporary adapters directory structure.

    # Priya: Mirrors the real adapters/ directory layout with configs
    # but no weights (weights are created by training).
    """
    adapters_dir = tmp_path / "adapters"

    safety_dir = adapters_dir / "safety"
    safety_dir.mkdir(parents=True)
    (safety_dir / "config.json").write_text(json.dumps({
        "adapter_name": "safety",
        "dataset": "duchess/construction-safety-instructions",
    }))

    spanish_dir = adapters_dir / "spanish_jargon"
    spanish_dir.mkdir(parents=True)
    (spanish_dir / "config.json").write_text(json.dumps({
        "adapter_name": "spanish_jargon",
        "dataset": "duchess/construction-spanish-jargon",
    }))

    return adapters_dir


@pytest.fixture
def mock_benchmark_results():
    """Mock benchmark evaluation results for testing report generation.

    # Priya: Pre-computed results that exercise all code paths in the
    # metrics computation — correct predictions, incorrect predictions,
    # missing JSON, etc.
    """
    return [
        {
            "violation_correct": True,
            "severity_correct": True,
            "has_en": True,
            "has_es": True,
            "valid_json": True,
            "predicted_violation": "no_hardhat",
            "predicted_severity": 3,
            "expected_violation": "no_hardhat",
            "expected_severity": 3,
        },
        {
            "violation_correct": True,
            "severity_correct": True,
            "has_en": True,
            "has_es": True,
            "valid_json": True,
            "predicted_violation": "fall_hazard",
            "predicted_severity": 4,
            "expected_violation": "fall_hazard",
            "expected_severity": 4,
        },
        {
            "violation_correct": False,
            "severity_correct": False,
            "has_en": True,
            "has_es": False,
            "valid_json": True,
            "predicted_violation": "no_hardhat",
            "predicted_severity": 3,
            "expected_violation": "electrical_hazard",
            "expected_severity": 5,
        },
        {
            "violation_correct": True,
            "severity_correct": True,
            "has_en": True,
            "has_es": True,
            "valid_json": True,
            "predicted_violation": None,
            "predicted_severity": 0,
            "expected_violation": None,
            "expected_severity": 0,
        },
        {
            "violation_correct": False,
            "severity_correct": False,
            "has_en": False,
            "has_es": False,
            "valid_json": False,
            "predicted_violation": None,
            "predicted_severity": None,
            "expected_violation": "no_vest",
            "expected_severity": 3,
        },
    ]
