"""
Tests for the model export pipeline.

# Priya: These tests validate the export pipeline's helper functions:
#   - Model size validation (catches FP32-instead-of-FP16 bugs)
#   - ONNX configuration and validation
#   - TFLite stub generation
#   - Benchmark result formatting
#   - Export manifest generation
#
# We do NOT test actual model loading/merging/export here — those
# require a GPU and the real model weights. This suite runs on CPU-only CI.
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from scripts.export_model import (
    ExportResult,
    MODEL_SIZE_LIMITS,
    _write_tflite_stub,
    get_dir_size_bytes,
    validate_model_size,
    write_export_manifest,
)


class TestModelSizeValidation:
    """Tests for validate_model_size().

    # Priya: Size validation is the first line of defense against bad exports.
    # If the ONNX is >8GB, it's probably FP32 instead of FP16. If TFLite
    # is >6GB, it won't fit on the phone. Better to catch it here.
    """

    def test_empty_model_fails(self, tmp_path):
        """Empty export directory should fail validation."""
        empty_dir = tmp_path / "empty_onnx"
        empty_dir.mkdir()

        result = ExportResult(adapter="safety")
        is_valid = validate_model_size(empty_dir, "onnx", result)
        assert not is_valid
        assert any("empty" in e.lower() or "0 bytes" in e for e in result.errors)

    def test_small_model_passes(self, tmp_path):
        """Reasonably-sized model file should pass validation.

        # Priya: We create a 10MB test file — well within our limits.
        """
        model_dir = tmp_path / "onnx"
        model_dir.mkdir()
        # Create a 10MB test file
        model_file = model_dir / "model.onnx"
        model_file.write_bytes(b"\x00" * (10 * 1024 * 1024))

        result = ExportResult(adapter="safety")
        is_valid = validate_model_size(model_dir, "onnx", result)
        assert is_valid
        assert result.onnx_size_bytes == 10 * 1024 * 1024

    def test_size_limits_are_sane(self):
        """Model size limits should be reasonable for mobile deployment.

        # Priya: Sanity check that nobody accidentally set the limits to
        # something absurd. ONNX max should be <16GB, TFLite max <8GB.
        """
        assert MODEL_SIZE_LIMITS["onnx_max_bytes"] <= 16 * 1024 ** 3
        assert MODEL_SIZE_LIMITS["tflite_max_bytes"] <= 8 * 1024 ** 3
        # Priya: Warn thresholds should be below max thresholds
        assert MODEL_SIZE_LIMITS["onnx_warn_bytes"] < MODEL_SIZE_LIMITS["onnx_max_bytes"]
        assert MODEL_SIZE_LIMITS["tflite_warn_bytes"] < MODEL_SIZE_LIMITS["tflite_max_bytes"]

    def test_nonexistent_path_is_empty(self):
        """Non-existent path should report 0 bytes."""
        size = get_dir_size_bytes(Path("/definitely/nonexistent/path"))
        assert size == 0


class TestOnnxConfig:
    """Tests for ONNX export configuration."""

    def test_export_result_dataclass_defaults(self):
        """ExportResult should have sensible defaults.

        # Priya: The dataclass should start clean — no paths, no sizes,
        # empty error/warning lists. This is our accumulator for the pipeline.
        """
        result = ExportResult(adapter="safety")
        assert result.adapter == "safety"
        assert result.onnx_path is None
        assert result.tflite_path is None
        assert result.onnx_size_bytes == 0
        assert result.tflite_size_bytes == 0
        assert result.benchmark_results == {}
        assert result.errors == []
        assert result.warnings == []

    def test_export_result_accumulates_errors(self):
        """ExportResult should accumulate multiple errors."""
        result = ExportResult(adapter="safety")
        result.errors.append("Error 1: ONNX too large")
        result.errors.append("Error 2: TFLite conversion failed")
        assert len(result.errors) == 2


class TestTfliteOptions:
    """Tests for TFLite conversion options and stub generation."""

    def test_tflite_stub_creation(self, tmp_path):
        """TFLite stub should be a readable text file (not binary).

        # Priya: The stub exists so the pipeline doesn't crash when
        # ai-edge-torch isn't installed. It should clearly indicate
        # that it's a placeholder, not a real model.
        """
        stub_file = tmp_path / "gemma3n_duchess.tflite"
        _write_tflite_stub(stub_file)

        assert stub_file.exists()
        content = stub_file.read_text()
        assert "PLACEHOLDER" in content
        assert "ai-edge-torch" in content

    def test_tflite_stub_is_small(self, tmp_path):
        """TFLite stub should be tiny (a few hundred bytes, not GB).

        # Priya: If the stub is large, something is very wrong.
        # Real TFLite models are ~3-4GB. Stubs should be <1KB.
        """
        stub_file = tmp_path / "test_stub.tflite"
        _write_tflite_stub(stub_file)
        assert stub_file.stat().st_size < 1024  # less than 1KB


class TestBenchmarkFormat:
    """Tests for benchmark result and manifest formatting."""

    def test_export_manifest_json_structure(self, tmp_path):
        """Export manifest should be valid JSON with required fields.

        # Priya: The manifest is consumed by our CI/CD pipeline and
        # deployment scripts. Its schema is a contract.
        """
        result = ExportResult(adapter="safety")
        result.onnx_path = tmp_path / "onnx"
        result.tflite_path = tmp_path / "tflite"
        result.onnx_size_bytes = 3_500_000_000
        result.tflite_size_bytes = 3_200_000_000
        result.benchmark_results = {
            "latency_mean_ms": 150.5,
            "tokens_per_sec": 35.2,
        }
        result.warnings.append("ONNX export is large")

        write_export_manifest(result, tmp_path)

        manifest_path = tmp_path / "export_manifest.json"
        assert manifest_path.exists()

        manifest = json.loads(manifest_path.read_text())
        assert manifest["adapter"] == "safety"
        assert "onnx_size_mb" in manifest
        assert "tflite_size_mb" in manifest
        assert "benchmark" in manifest
        assert "errors" in manifest
        assert "warnings" in manifest
        assert len(manifest["warnings"]) == 1

    def test_manifest_sizes_in_mb(self, tmp_path):
        """Manifest should report sizes in MB (not bytes).

        # Priya: Human-readable sizes for the deployment team.
        # Nobody wants to mentally divide by 1048576.
        """
        result = ExportResult(adapter="safety")
        result.onnx_size_bytes = 3_670_016_000  # ~3500 MB
        result.tflite_size_bytes = 0

        write_export_manifest(result, tmp_path)

        manifest = json.loads((tmp_path / "export_manifest.json").read_text())
        assert manifest["onnx_size_mb"] == pytest.approx(3500.0, abs=1.0)

    def test_get_dir_size_single_file(self, tmp_path):
        """get_dir_size_bytes should work on a single file."""
        test_file = tmp_path / "test.bin"
        test_file.write_bytes(b"\x00" * 100)
        assert get_dir_size_bytes(test_file) == 100

    def test_get_dir_size_nested(self, tmp_path):
        """get_dir_size_bytes should sum all files recursively."""
        (tmp_path / "sub").mkdir()
        (tmp_path / "file1.bin").write_bytes(b"\x00" * 50)
        (tmp_path / "sub" / "file2.bin").write_bytes(b"\x00" * 75)
        assert get_dir_size_bytes(tmp_path) == 125
