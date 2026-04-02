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

# PRIYA: =================================================================
# Mock heavy ML dependencies that aren't installed on dev laptops / CI.
# These mocks are installed in sys.modules BEFORE any test file imports
# source modules (eval.benchmark, scripts.export_model, etc.) that have
# module-level `import torch` / `from datasets import ...` statements.
# Without these mocks, every test fails with ModuleNotFoundError.
#
# Each mock is guarded by _is_package_available() so that on a GPU dev
# machine with the real packages installed, the real code runs instead.
# =================================================================

import importlib.util
import sys
from unittest.mock import MagicMock


def _is_package_available(name: str) -> bool:
    """Check if a Python package is importable without importing it."""
    # PRIYA: Use find_spec to avoid importing heavy packages as a side effect.
    if name in sys.modules:
        return True
    return importlib.util.find_spec(name) is not None


# ── Mock helpers ────────────────────────────────────────────────────────────

class _MockDataset:
    """Lightweight stand-in for datasets.Dataset.

    # PRIYA: Functions like load_local_dataset() and split_dataset() call
    # Dataset.from_list(), len(dataset), and dataset.train_test_split().
    # This mock provides just enough interface to satisfy those calls
    # without pulling in the 300 MB `datasets` package.
    """

    def __init__(self, data=None):
        self._data = list(data or [])

    def __len__(self):
        return len(self._data)

    @classmethod
    def from_list(cls, data):
        return cls(data)

    def train_test_split(self, test_size=0.1, seed=42):
        n_test = max(1, int(len(self._data) * test_size))
        return {
            "train": _MockDataset(self._data[n_test:]),
            "test": _MockDataset(self._data[:n_test]),
        }

    def select(self, indices):
        return _MockDataset([self._data[i] for i in indices])


class _MockTrainingArguments:
    """Lightweight stand-in for transformers.TrainingArguments.

    # PRIYA: build_training_args() constructs a TrainingArguments and the
    # test asserts training_args.report_to == ["none"]. The real class
    # converts a bare string to a single-element list, so we replicate that.
    """

    def __init__(self, **kwargs):
        for k, v in kwargs.items():
            setattr(self, k, v)
        # PRIYA: Real TrainingArguments wraps a bare report_to string in a list
        if hasattr(self, "report_to") and isinstance(self.report_to, str):
            self.report_to = [self.report_to]


# ── Install mocks for missing packages ──────────────────────────────────────

if not _is_package_available("torch"):
    # PRIYA: torch is imported at module level in benchmark.py, export_model.py,
    # and train_gemma4.py. resolve_mixed_precision() calls
    # torch.cuda.is_available() — must return False so it picks the fp16 path.
    _mock_torch = MagicMock()
    _mock_torch.cuda.is_available.return_value = False
    _mock_torch.cuda.is_bf16_supported.return_value = False
    _mock_torch.float16 = "float16"
    sys.modules["torch"] = _mock_torch
    sys.modules["torch.cuda"] = _mock_torch.cuda
    sys.modules["torch.nn"] = _mock_torch.nn
    sys.modules["torch.nn.functional"] = _mock_torch.nn.functional

if not _is_package_available("datasets"):
    # PRIYA: datasets is imported at module level in prepare_dataset.py and
    # train_gemma4.py via `from datasets import Dataset, load_dataset`.
    # _MockDataset supports from_list(), len(), and train_test_split().
    _mock_datasets = MagicMock()
    _mock_datasets.Dataset = _MockDataset
    _mock_datasets.DatasetDict = MagicMock()
    _mock_datasets.load_dataset = MagicMock()
    sys.modules["datasets"] = _mock_datasets

if not _is_package_available("transformers"):
    # PRIYA: transformers provides TrainingArguments, imported at module level
    # in train_gemma4.py. _MockTrainingArguments stores kwargs as attributes
    # and wraps report_to string → list (matching real HF behavior).
    _mock_transformers = MagicMock()
    _mock_transformers.TrainingArguments = _MockTrainingArguments
    sys.modules["transformers"] = _mock_transformers

if not _is_package_available("wandb"):
    # PRIYA: wandb is imported at module level in train_gemma4.py for
    # experiment tracking. Not exercised in unit tests.
    sys.modules["wandb"] = MagicMock()

if not _is_package_available("peft"):
    # PRIYA: peft provides LoraConfig (module-level import in train_gemma4.py)
    # and PeftModel (lazy import inside benchmark.py / export_model.py).
    sys.modules["peft"] = MagicMock()

if not _is_package_available("trl"):
    # PRIYA: trl provides SFTTrainer, imported at module level in train_gemma4.py.
    sys.modules["trl"] = MagicMock()

if not _is_package_available("unsloth"):
    # PRIYA: unsloth provides FastLanguageModel for Dynamic QLoRA — imported at
    # module level in train_gemma4.py. Only framework supporting Gemma 4 quant.
    sys.modules["unsloth"] = MagicMock()

if not _is_package_available("ultralytics"):
    # PRIYA: ultralytics (YOLOv8) isn't imported by the four source files under
    # test, but mocked defensively in case future imports add it.
    sys.modules["ultralytics"] = MagicMock()

# PRIYA: numpy is NOT mocked here on purpose. It's only lazily imported inside
# export_model.run_benchmark() (never called in unit tests), and mocking it
# breaks pytest.approx() which checks sys.modules.get("numpy") internally.

if not _is_package_available("optimum"):
    # PRIYA: optimum is lazily imported inside export_model.export_to_onnx().
    _mock_optimum = MagicMock()
    sys.modules["optimum"] = _mock_optimum
    sys.modules["optimum.exporters"] = _mock_optimum.exporters
    sys.modules["optimum.exporters.onnx"] = _mock_optimum.exporters.onnx

if not _is_package_available("onnx"):
    # PRIYA: onnx is lazily imported inside export_model.validate_onnx().
    sys.modules["onnx"] = MagicMock()

if not _is_package_available("ai_edge_torch"):
    # PRIYA: ai-edge-torch converts ONNX → TFLite. Lazily imported inside
    # export_model.export_to_tflite().
    sys.modules["ai_edge_torch"] = MagicMock()

# ── End of mock setup ───────────────────────────────────────────────────────

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
        "base_model": "google/gemma-4-e2b-it",
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
        "base_model": "google/gemma-4-e2b-it",
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
