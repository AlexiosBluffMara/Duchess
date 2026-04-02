"""
Tests for the evaluation benchmark module.

# Priya: These tests validate our evaluation metrics, confusion matrix
# computation, latency statistics, and report generation. The benchmark
# is what gates every model release — a bug here means we might ship
# a model that looks good on paper but fails in the field.
#
# We test with pre-computed mock results (no actual model inference).
# Integration tests with real models run separately on GPU machines.
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from eval.benchmark import (
    ALL_LABELS,
    TEST_CASES,
    build_confusion_matrix,
    compute_latency_stats,
    compute_per_class_metrics,
    evaluate_response,
    format_confusion_matrix_text,
    generate_markdown_report,
)


class TestMetricsComputation:
    """Tests for per-class precision/recall/F1 computation.

    # Priya: These are the core metrics that determine whether an adapter
    # is ready for deployment. We compute micro and macro averages because
    # overall accuracy is meaningless with imbalanced data.
    """

    def test_perfect_predictions(self):
        """All-correct predictions should give 1.0 across all metrics."""
        results = [
            {
                "predicted_violation": "no_hardhat",
                "expected_violation": "no_hardhat",
            },
            {
                "predicted_violation": "fall_hazard",
                "expected_violation": "fall_hazard",
            },
            {
                "predicted_violation": None,
                "expected_violation": None,
            },
        ]
        metrics = compute_per_class_metrics(results)
        assert metrics["macro"]["f1"] == pytest.approx(1.0)
        assert metrics["micro"]["f1"] == pytest.approx(1.0)
        assert metrics["macro"]["precision"] == pytest.approx(1.0)
        assert metrics["macro"]["recall"] == pytest.approx(1.0)

    def test_all_wrong_predictions(self):
        """All-wrong predictions should give 0.0 F1.

        # Priya: If the model gets everything wrong, macro and micro F1
        # should both be 0. This is our sanity check for the metric formula.
        """
        results = [
            {
                "predicted_violation": "fall_hazard",
                "expected_violation": "no_hardhat",
            },
            {
                "predicted_violation": "no_hardhat",
                "expected_violation": "fall_hazard",
            },
        ]
        metrics = compute_per_class_metrics(results)
        assert metrics["macro"]["f1"] == pytest.approx(0.0)
        assert metrics["micro"]["f1"] == pytest.approx(0.0)

    def test_mixed_results_partial_f1(self, mock_benchmark_results):
        """Mixed correct/incorrect should give F1 between 0 and 1."""
        metrics = compute_per_class_metrics(mock_benchmark_results)
        assert 0.0 < metrics["macro"]["f1"] < 1.0
        assert 0.0 < metrics["micro"]["f1"] < 1.0

    def test_per_class_keys_present(self, mock_benchmark_results):
        """Each class in per_class should have precision, recall, f1, support."""
        metrics = compute_per_class_metrics(mock_benchmark_results)
        for label, class_metrics in metrics["per_class"].items():
            assert "precision" in class_metrics
            assert "recall" in class_metrics
            assert "f1" in class_metrics
            assert "support" in class_metrics

    def test_support_sums_to_total(self, mock_benchmark_results):
        """Total support across all classes should equal number of results.

        # Priya: Support = number of actual instances of each class.
        # Sum of support = total test cases. If not, we're losing or
        # double-counting examples.
        """
        metrics = compute_per_class_metrics(mock_benchmark_results)
        total_support = sum(m["support"] for m in metrics["per_class"].values())
        assert total_support == len(mock_benchmark_results)


class TestConfusionMatrix:
    """Tests for confusion matrix generation."""

    def test_perfect_diagonal(self):
        """All-correct predictions should produce a diagonal matrix.

        # Priya: In a confusion matrix, the diagonal represents correct
        # predictions. Off-diagonal entries are errors.
        """
        results = [
            {"predicted_violation": "no_hardhat", "expected_violation": "no_hardhat"},
            {"predicted_violation": "fall_hazard", "expected_violation": "fall_hazard"},
        ]
        matrix = build_confusion_matrix(results)
        assert matrix["no_hardhat"]["no_hardhat"] == 1
        assert matrix["fall_hazard"]["fall_hazard"] == 1

    def test_off_diagonal_errors(self):
        """Incorrect predictions should appear off-diagonal."""
        results = [
            {"predicted_violation": "fall_hazard", "expected_violation": "no_hardhat"},
        ]
        matrix = build_confusion_matrix(results)
        assert matrix["no_hardhat"]["fall_hazard"] == 1
        # Priya: no_hardhat → no_hardhat should NOT exist (we got it wrong)
        assert matrix["no_hardhat"].get("no_hardhat", 0) == 0

    def test_none_mapped_to_compliant(self):
        """None violations should be mapped to 'compliant' in the matrix.

        # Priya: We normalize None → "compliant" so the matrix is consistent.
        """
        results = [
            {"predicted_violation": None, "expected_violation": None},
        ]
        matrix = build_confusion_matrix(results)
        assert matrix["compliant"]["compliant"] == 1

    def test_confusion_matrix_text_format(self):
        """Text format should include all labels and be readable."""
        results = [
            {"predicted_violation": "no_hardhat", "expected_violation": "no_hardhat"},
            {"predicted_violation": "fall_hazard", "expected_violation": "no_hardhat"},
        ]
        matrix = build_confusion_matrix(results)
        text = format_confusion_matrix_text(matrix)
        assert "no_hardhat" in text
        assert "fall_hazard" in text


class TestLatencyStats:
    """Tests for latency statistics computation."""

    def test_single_value(self):
        """Single latency value should have 0 std and equal percentiles."""
        stats = compute_latency_stats([100.0])
        assert stats["mean_ms"] == 100.0
        assert stats["std_ms"] == 0.0
        assert stats["p50_ms"] == 100.0
        assert stats["min_ms"] == 100.0
        assert stats["max_ms"] == 100.0

    def test_multiple_values(self):
        """Multiple values should produce correct mean and percentiles."""
        latencies = [100.0, 200.0, 300.0, 400.0, 500.0]
        stats = compute_latency_stats(latencies)
        assert stats["mean_ms"] == pytest.approx(300.0)
        assert stats["min_ms"] == 100.0
        assert stats["max_ms"] == 500.0
        assert stats["p50_ms"] == 300.0

    def test_empty_latencies(self):
        """Empty latency list should return all zeros.

        # Priya: Edge case for when no inferences were run (e.g., model
        # failed to load). Should not crash, just return zeros.
        """
        stats = compute_latency_stats([])
        assert stats["mean_ms"] == 0
        assert stats["p95_ms"] == 0

    def test_p95_above_p50(self):
        """P95 should be ≥ P50 for any distribution.

        # Priya: Basic statistical property. If this fails, the percentile
        # calculation is broken.
        """
        latencies = [10.0, 20.0, 30.0, 40.0, 50.0, 100.0, 200.0, 500.0, 600.0, 1000.0]
        stats = compute_latency_stats(latencies)
        assert stats["p95_ms"] >= stats["p50_ms"]


class TestReportFormat:
    """Tests for markdown report generation."""

    def test_markdown_report_structure(self, mock_benchmark_results):
        """Markdown report should have required sections.

        # Priya: The report is our permanent record. It MUST have:
        # title, overall metrics, per-class metrics, latency, and confusion matrix.
        """
        latencies = [100.0, 150.0, 200.0, 250.0, 300.0]
        class_metrics = compute_per_class_metrics(mock_benchmark_results)
        confusion = build_confusion_matrix(mock_benchmark_results)
        latency_stats = compute_latency_stats(latencies)

        class MockArgs:
            adapter = "safety"
            device = "cpu"

        report = generate_markdown_report(
            mock_benchmark_results, latencies, class_metrics,
            confusion, latency_stats, MockArgs()
        )

        assert "# Duchess Benchmark Report" in report
        assert "## Overall Metrics" in report
        assert "## Per-Class Metrics" in report
        assert "## Latency Statistics" in report
        assert "## Confusion Matrix" in report

    def test_markdown_contains_adapter_name(self, mock_benchmark_results):
        """Report should mention the adapter being evaluated."""
        class_metrics = compute_per_class_metrics(mock_benchmark_results)
        confusion = build_confusion_matrix(mock_benchmark_results)
        latency_stats = compute_latency_stats([100.0])

        class MockArgs:
            adapter = "spanish_jargon"
            device = "cpu"

        report = generate_markdown_report(
            mock_benchmark_results, [100.0], class_metrics,
            confusion, latency_stats, MockArgs()
        )

        assert "spanish_jargon" in report


class TestComparisonBaseline:
    """Tests for baseline comparison functionality."""

    def test_evaluate_response_valid_json(self):
        """Valid JSON response with correct predictions should score 5/5."""
        response = json.dumps({
            "violation": "no_hardhat",
            "severity": 3,
            "description_en": "Worker without hardhat",
            "description_es": "Trabajador sin casco",
        })
        expected = {
            "expected_violation": "no_hardhat",
            "expected_severity": 3,
        }
        scores = evaluate_response(response, expected)
        assert scores["violation_correct"] is True
        assert scores["severity_correct"] is True
        assert scores["has_en"] is True
        assert scores["has_es"] is True
        assert scores["valid_json"] is True

    def test_evaluate_response_invalid_json(self):
        """Invalid JSON response should score 0/5.

        # Priya: The model sometimes outputs free text instead of JSON,
        # especially early in training. We must handle this gracefully.
        """
        scores = evaluate_response("This is not JSON at all", {
            "expected_violation": "no_hardhat",
            "expected_severity": 3,
        })
        assert scores["valid_json"] is False
        assert scores["violation_correct"] is False

    def test_evaluate_response_wrong_prediction(self):
        """Correct JSON but wrong violation should score 3/5.

        # Priya: The model parses fine but predicted the wrong class.
        # JSON, EN, ES should still pass.
        """
        response = json.dumps({
            "violation": "fall_hazard",
            "severity": 4,
            "description_en": "Fall risk detected",
            "description_es": "Riesgo de caída detectado",
        })
        expected = {
            "expected_violation": "no_hardhat",
            "expected_severity": 3,
        }
        scores = evaluate_response(response, expected)
        assert scores["valid_json"] is True
        assert scores["violation_correct"] is False
        assert scores["severity_correct"] is False
        assert scores["has_en"] is True
        assert scores["has_es"] is True

    def test_test_cases_have_required_fields(self):
        """Built-in TEST_CASES should all have input, expected_violation, expected_severity.

        # Priya: Schema validation for our built-in test cases. If someone
        # adds a malformed test case, this catches it.
        """
        for i, tc in enumerate(TEST_CASES):
            assert "input" in tc, f"TEST_CASE {i} missing 'input'"
            assert "expected_violation" in tc, f"TEST_CASE {i} missing 'expected_violation'"
            assert "expected_severity" in tc, f"TEST_CASE {i} missing 'expected_severity'"

    def test_all_labels_covers_test_cases(self):
        """ALL_LABELS should include every expected_violation from TEST_CASES.

        # Priya: Our label taxonomy must cover all test case labels.
        # If we add a new test case with a new violation type, ALL_LABELS
        # must be updated too.
        """
        for tc in TEST_CASES:
            violation = tc["expected_violation"]
            if violation is None:
                assert "compliant" in ALL_LABELS
            else:
                assert violation in ALL_LABELS, (
                    f"'{violation}' in TEST_CASES but not in ALL_LABELS"
                )
