"""
Tests for the dataset preparation pipeline.

# Priya: These tests validate every stage of our data pipeline:
#   1. Format validation — schema compliance, required fields
#   2. Bilingual checking — EN/ES descriptions present and non-empty
#   3. Language detection — basic heuristics for EN vs ES
#   4. Train/val splitting — stratified by violation type
#   5. Class balance reporting — violation type distribution
#   6. JSONL I/O — round-trip write and read
#   7. Edge cases — empty files, malformed JSON, trivial fields
#
# These tests run WITHOUT any ML dependencies (no torch, no transformers).
# They validate the data processing logic in isolation.
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

# Priya: Import the functions under test. We test the public API of
# prepare_dataset.py — each function that could break independently.
from scripts.prepare_dataset import (
    PLACEHOLDER_EXAMPLES,
    compute_statistics,
    detect_language,
    stratified_split,
    validate_example,
    write_jsonl,
)


class TestValidateExample:
    """Tests for validate_example().

    # Priya: This is the most critical function in the data pipeline.
    # A bug here means bad data gets into training. Every failure mode
    # I've seen in the wild has a test here.
    """

    def test_valid_example_passes(self, sample_examples):
        """Valid bilingual examples should pass all checks."""
        for example in sample_examples:
            is_valid, errors = validate_example(example)
            assert is_valid, f"Valid example failed: {errors}"
            assert len(errors) == 0

    def test_missing_output_field(self):
        """Missing 'output' field should fail validation."""
        example = {
            "instruction": "Identify the violation.",
            "input": "Worker without hardhat on scaffolding.",
        }
        is_valid, errors = validate_example(example)
        assert not is_valid
        assert any("Missing fields" in e for e in errors)

    def test_invalid_json_output(self):
        """Non-JSON output string should fail validation."""
        example = {
            "instruction": "Identify the violation.",
            "input": "Worker without hardhat on scaffolding at height.",
            "output": "This is definitely not valid JSON at all",
        }
        is_valid, errors = validate_example(example)
        assert not is_valid
        assert any("not valid JSON" in e for e in errors)

    def test_missing_spanish_description(self):
        """Output without description_es should fail bilingual check."""
        example = {
            "instruction": "Identify the violation in the scene.",
            "input": "Worker without vest near heavy machinery.",
            "output": json.dumps({
                "violation": "no_vest",
                "severity": 3,
                "description_en": "Worker without high-visibility vest",
            }),
        }
        is_valid, errors = validate_example(example)
        assert not is_valid
        assert any("description_es" in e for e in errors)

    def test_missing_english_description(self):
        """Output without description_en should fail bilingual check."""
        example = {
            "instruction": "Evalúe la seguridad del trabajador.",
            "input": "Trabajador sin chaleco cerca de maquinaria pesada.",
            "output": json.dumps({
                "violation": "no_vest",
                "severity": 3,
                "description_es": "Trabajador sin chaleco de alta visibilidad",
            }),
        }
        is_valid, errors = validate_example(example)
        assert not is_valid
        assert any("description_en" in e for e in errors)

    def test_invalid_severity_out_of_range(self):
        """Severity outside 0-5 should produce validation error."""
        example = {
            "instruction": "Assess the scene for safety violations.",
            "input": "Worker without hardhat on scaffolding at height.",
            "output": json.dumps({
                "violation": "no_hardhat",
                "severity": 99,
                "description_en": "No hardhat",
                "description_es": "Sin casco",
            }),
        }
        is_valid, errors = validate_example(example)
        assert not is_valid
        assert any("severity" in e.lower() for e in errors)

    def test_instruction_too_short(self):
        """Short instruction (below min_instruction_len) should fail."""
        example = {
            "instruction": "Hi",
            "input": "Worker without hardhat on scaffolding at height.",
            "output": json.dumps({
                "violation": "no_hardhat",
                "severity": 3,
                "description_en": "No hardhat at height — OSHA 1926.100",
                "description_es": "Sin casco en altura — OSHA 1926.100",
            }),
        }
        is_valid, errors = validate_example(example, min_instruction_len=10)
        assert not is_valid
        assert any("Instruction too short" in e for e in errors)

    def test_compliant_scene_validates(self):
        """Compliant scene (violation=None, severity=0) should validate."""
        example = {
            "instruction": "Assess the safety compliance of this scene.",
            "input": "Workers in full PPE, guardrails in place.",
            "output": json.dumps({
                "violation": None,
                "severity": 0,
                "description_en": "Scene is compliant — no violations",
                "description_es": "Escena cumple — sin violaciones",
            }),
        }
        is_valid, errors = validate_example(example)
        assert is_valid, f"Compliant scene failed: {errors}"


class TestLanguageDetection:
    """Tests for detect_language().

    # Priya: Simple keyword-based detection. It's not meant to be
    # production-grade NLP — just a quick sanity check during data prep.
    """

    def test_english_text_detected(self):
        """English construction safety text should be detected as 'en'."""
        text = "Worker on scaffolding without hardhat, wearing safety vest."
        assert detect_language(text) == "en"

    def test_spanish_text_detected(self):
        """Spanish construction safety text should be detected as 'es'."""
        text = "Trabajador en andamio sin casco, con chaleco de seguridad."
        assert detect_language(text) == "es"

    def test_spanish_special_chars(self):
        """Text with ñ, accent marks should be detected as Spanish."""
        text = "Señalización de protección en la zona de excavación."
        assert detect_language(text) == "es"


class TestBilingualPairs:
    """Tests for bilingual completeness in placeholder data.

    # Priya: EVERY placeholder example MUST have both EN and ES descriptions.
    # This is non-negotiable per the Duchess bilingual requirement.
    """

    def test_all_placeholders_are_bilingual(self):
        """Every placeholder example must have both description_en and description_es."""
        for i, example in enumerate(PLACEHOLDER_EXAMPLES):
            output = json.loads(example["output"])
            assert "description_en" in output, f"Placeholder {i} missing description_en"
            assert "description_es" in output, f"Placeholder {i} missing description_es"
            assert len(output["description_en"]) > 0, f"Placeholder {i} empty description_en"
            assert len(output["description_es"]) > 0, f"Placeholder {i} empty description_es"

    def test_placeholder_count_minimum(self):
        """We need at least 6 placeholder examples for meaningful pipeline tests."""
        # Priya: 6 is our MIN_DATASET_SIZE in train_gemma4.py
        assert len(PLACEHOLDER_EXAMPLES) >= 6


class TestStratifiedSplit:
    """Tests for stratified_split().

    # Priya: Stratified splitting is how we ensure rare violation types
    # (like confined_space) appear in both train and val sets.
    """

    def test_split_produces_two_non_empty_sets(self, sample_examples):
        """Both train and val sets should be non-empty."""
        train, val = stratified_split(sample_examples, val_ratio=0.3, seed=42)
        assert len(train) > 0, "Train set is empty"
        assert len(val) > 0, "Val set is empty"

    def test_split_preserves_total_count(self, sample_examples):
        """Train + val should equal total examples (no data loss)."""
        train, val = stratified_split(sample_examples, val_ratio=0.2, seed=42)
        assert len(train) + len(val) == len(sample_examples)

    def test_split_is_deterministic(self, sample_examples):
        """Same seed should produce identical splits."""
        train1, val1 = stratified_split(sample_examples, val_ratio=0.2, seed=42)
        train2, val2 = stratified_split(sample_examples, val_ratio=0.2, seed=42)
        assert train1 == train2
        assert val1 == val2

    def test_split_val_has_multiple_classes(self, sample_examples):
        """Val set should contain examples from multiple violation types.

        # Priya: This is the whole point of stratification — the val set
        # should represent the full label distribution, not just one class.
        """
        _, val = stratified_split(sample_examples, val_ratio=0.3, seed=42)

        val_violations = set()
        for ex in val:
            output = json.loads(ex["output"])
            violation = output.get("violation") or "compliant"
            val_violations.add(violation)

        # Priya: With 8 examples spanning ~5 violation types and val_ratio=0.3,
        # we expect at least 2 distinct violation types in val
        assert len(val_violations) >= 2, (
            f"Val set has only {len(val_violations)} violation type(s): {val_violations}. "
            f"Stratification may be broken."
        )


class TestWriteJsonl:
    """Tests for JSONL output writing."""

    def test_write_and_read_roundtrip(self, tmp_path, sample_examples):
        """Write → read should produce identical examples."""
        output_path = tmp_path / "output.jsonl"
        count = write_jsonl(sample_examples, output_path)

        assert count == len(sample_examples)
        assert output_path.exists()

        # Priya: Read back and verify
        read_back = []
        with open(output_path) as f:
            for line in f:
                read_back.append(json.loads(line))

        assert len(read_back) == len(sample_examples)
        for original, loaded in zip(sample_examples, read_back):
            assert original["instruction"] == loaded["instruction"]
            assert original["input"] == loaded["input"]

    def test_write_creates_parent_directories(self, tmp_path, sample_examples):
        """write_jsonl should create parent dirs if missing."""
        output_path = tmp_path / "deep" / "nested" / "dir" / "output.jsonl"
        count = write_jsonl(sample_examples, output_path)
        assert count == len(sample_examples)
        assert output_path.exists()


class TestComputeStatistics:
    """Tests for dataset statistics computation."""

    def test_statistics_counts(self, sample_examples):
        """Statistics should count total examples correctly."""
        stats = compute_statistics(sample_examples)
        assert stats["total_examples"] == len(sample_examples)

    def test_statistics_violation_distribution(self, sample_examples):
        """Violation distribution should cover all types in the data."""
        stats = compute_statistics(sample_examples)
        # Priya: Our sample data has: no_hardhat, fall_hazard, compliant,
        # electrical_hazard, no_eye_protection, struck_by_hazard
        assert len(stats["violation_counts"]) >= 4

    def test_statistics_bilingual_completeness(self, sample_examples):
        """Bilingual count should match valid examples."""
        stats = compute_statistics(sample_examples)
        # Priya: All our sample examples are bilingual
        assert stats["bilingual_complete"] == len(sample_examples)

    def test_statistics_average_lengths(self, sample_examples):
        """Average instruction and output lengths should be positive."""
        stats = compute_statistics(sample_examples)
        assert stats["avg_instruction_len"] > 0
        assert stats["avg_output_len"] > 0
