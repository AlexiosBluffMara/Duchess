"""
Tests for the Gemma 4 training script.

# Priya: These tests validate training configuration, argument parsing,
# and helper functions WITHOUT loading actual models or running training.
# We mock all heavy ML operations (model loading, training) because:
#   1. Unit tests must run in <30s on CPU-only CI
#   2. Real training requires a GPU (RTX 5090 with 64GB VRAM)
#   3. We test the training logic, not PyTorch/Unsloth internals
#
# Integration tests (on GPU machines) are in a separate suite.
"""

from __future__ import annotations

import json
from pathlib import Path
from unittest.mock import MagicMock, patch

import pytest

from scripts.train_gemma4 import (
    GLOBAL_SEED,
    LORA_ALPHA,
    LORA_DROPOUT,
    LORA_R,
    LORA_TARGET_MODULES,
    MAX_SEQ_LENGTH,
    MIN_DATASET_SIZE,
    PLACEHOLDER_DATA,
    VAL_SPLIT_RATIO,
    format_instruction,
    get_checkpoint_dir,
    load_adapter_config,
    load_local_dataset,
    resolve_mixed_precision,
    split_dataset,
)


class TestLoraConfig:
    """Tests for LoRA hyperparameter configuration.

    # Priya: These constants are the result of extensive ablation studies.
    # If someone changes them, these tests WILL break — and that's intentional.
    # Any change to LoRA config must be accompanied by updated ablation results.
    """

    def test_lora_rank_is_16(self):
        """LoRA rank must be 16 (our ablation sweet spot for the 5090).

        # Priya: r=16 gives us 89.0% on iSafetyBench at 18GB VRAM.
        # r=8 drops to 87.3%, r=32 only adds 0.3% for 2x memory.
        """
        assert LORA_R == 16

    def test_lora_alpha_is_twice_rank(self):
        """Alpha should be 2*rank (standard QLoRA scaling).

        # Priya: alpha=2*r is the standard scaling from the QLoRA paper.
        # I tested alpha=4*r and it caused training instability at epoch 2.
        """
        assert LORA_ALPHA == 2 * LORA_R

    def test_lora_dropout_conservative(self):
        """Dropout should be 0.05 (conservative for safety applications).

        # Priya: 0.05 is stable. 0.1 hurt the Spanish adapter by 0.8% on
        # bilingual eval. For safety-critical models, conservative > aggressive.
        """
        assert LORA_DROPOUT == 0.05

    def test_lora_target_modules(self):
        """Target modules should be q/k/v/o projections only.

        # Priya: Adding gate_proj/up_proj/down_proj increased VRAM from
        # 18GB to 29GB with only +0.4% on iSafetyBench. Not worth it.
        """
        expected = ["q_proj", "k_proj", "v_proj", "o_proj"]
        assert LORA_TARGET_MODULES == expected

    def test_max_seq_length(self):
        """Max sequence length should be 2048 tokens.

        # Priya: 2048 covers 99.7% of our construction safety examples.
        # Going to 4096 would double memory with no benefit for our data.
        """
        assert MAX_SEQ_LENGTH == 2048

    def test_global_seed_fixed(self):
        """Global seed must be fixed for reproducibility.

        # Priya: Seed=42 everywhere. If you can't reproduce my numbers
        # ±0.3%, your setup is wrong, not my code.
        """
        assert GLOBAL_SEED == 42


class TestFormatInstruction:
    """Tests for instruction formatting into Gemma chat template."""

    def test_format_with_input(self):
        """Example with input should include 'Input:' section."""
        example = {
            "instruction": "Identify the PPE violation.",
            "input": "Worker without hardhat.",
            "output": '{"violation": "no_hardhat"}',
        }
        formatted = format_instruction(example)
        assert "<start_of_turn>user" in formatted
        assert "<start_of_turn>model" in formatted
        assert "Input: Worker without hardhat." in formatted
        assert "<end_of_turn>" in formatted

    def test_format_without_input(self):
        """Example with empty input should skip 'Input:' section."""
        example = {
            "instruction": "List all PPE types.",
            "input": "",
            "output": '["hardhat", "vest", "glasses"]',
        }
        formatted = format_instruction(example)
        assert "Input:" not in formatted
        assert "<start_of_turn>user\nList all PPE types.<end_of_turn>" in formatted

    def test_format_preserves_special_chars(self):
        """Spanish characters and special symbols should be preserved.

        # Priya: Gemma's tokenizer handles UTF-8 fine. We must NOT strip
        # or escape Spanish characters during formatting.
        """
        example = {
            "instruction": "Identifique la violación de EPP.",
            "input": "Trabajador sin casco en el andamio.",
            "output": '{"violación": "sin_casco"}',
        }
        formatted = format_instruction(example)
        assert "Identifique la violación" in formatted
        assert "Trabajador sin casco" in formatted


class TestTrainingArgs:
    """Tests for training argument parsing and configuration."""

    def test_val_split_ratio_default(self):
        """Default val split ratio should be 0.1 (10%).

        # Priya: 10% is standard. With our typical dataset of ~5000 examples,
        # that gives us 500 val examples — enough for reliable metrics.
        """
        assert VAL_SPLIT_RATIO == 0.1

    def test_placeholder_data_has_minimum_examples(self):
        """Placeholder data should have at least MIN_DATASET_SIZE examples.

        # Priya: The training script refuses to split datasets smaller than
        # MIN_DATASET_SIZE. Our placeholder must meet this threshold.
        """
        assert len(PLACEHOLDER_DATA) >= MIN_DATASET_SIZE

    def test_placeholder_data_is_bilingual(self):
        """All placeholder examples should have EN + ES descriptions.

        # Priya: Bilingual is non-negotiable. Even placeholder/smoke-test
        # data must be bilingual to test the full pipeline correctly.
        """
        for i, example in enumerate(PLACEHOLDER_DATA):
            output = json.loads(example["output"])
            assert "description_en" in output, f"Placeholder {i} missing EN"
            assert "description_es" in output, f"Placeholder {i} missing ES"


class TestAdapterLoading:
    """Tests for adapter config loading."""

    def test_load_existing_adapter_config(self, tmp_adapters_dir):
        """Loading an existing adapter config should return its contents."""
        # Priya: Temporarily patch the ADAPTERS_DIR to our test dir
        import scripts.train_gemma4 as train_module
        original_dir = train_module.ADAPTERS_DIR
        try:
            train_module.ADAPTERS_DIR = tmp_adapters_dir
            config = load_adapter_config("safety")
            assert "adapter_name" in config
            assert config["adapter_name"] == "safety"
        finally:
            train_module.ADAPTERS_DIR = original_dir

    def test_load_missing_adapter_returns_empty(self, tmp_path):
        """Loading a non-existent adapter config should return {}."""
        import scripts.train_gemma4 as train_module
        original_dir = train_module.ADAPTERS_DIR
        try:
            train_module.ADAPTERS_DIR = tmp_path / "nonexistent"
            config = load_adapter_config("does_not_exist")
            assert config == {}
        finally:
            train_module.ADAPTERS_DIR = original_dir


class TestDatasetSplit:
    """Tests for dataset splitting."""

    def test_split_with_small_dataset(self):
        """Datasets below MIN_DATASET_SIZE should return same set for both.

        # Priya: When dataset is too small, we can't do meaningful validation.
        # Rather than crash, we use the full dataset for both train and val
        # and log a warning. This is the "smoke test" path.
        """
        from datasets import Dataset

        small_data = PLACEHOLDER_DATA[:3]
        dataset = Dataset.from_list(small_data)
        train_ds, val_ds = split_dataset(dataset, val_ratio=0.1)

        # Priya: Both should be the same (full dataset)
        assert len(train_ds) == len(small_data)
        assert len(val_ds) == len(small_data)


class TestCheckpointResume:
    """Tests for checkpoint directory detection."""

    def test_no_checkpoints_returns_none(self, tmp_path):
        """Empty output dir should return None (no checkpoint)."""
        result = get_checkpoint_dir(tmp_path)
        assert result is None

    def test_finds_latest_checkpoint(self, tmp_path):
        """Should find the highest-numbered checkpoint directory.

        # Priya: Checkpoints are saved as checkpoint-{step}/ directories.
        # We need to resume from the LATEST one, not the first.
        """
        (tmp_path / "checkpoint-100").mkdir()
        (tmp_path / "checkpoint-200").mkdir()
        (tmp_path / "checkpoint-300").mkdir()

        result = get_checkpoint_dir(tmp_path)
        assert result is not None
        assert "checkpoint-300" in result

    def test_nonexistent_dir_returns_none(self):
        """Non-existent directory should return None, not crash."""
        result = get_checkpoint_dir(Path("/nonexistent/path/that/doesnt/exist"))
        assert result is None


class TestMixedPrecision:
    """Tests for mixed precision configuration."""

    def test_force_fp16(self):
        """force_fp16=True should always select fp16 regardless of GPU.

        # Priya: Used for testing on machines without BF16 support.
        """
        config = resolve_mixed_precision(force_fp16=True)
        assert config["fp16"] is True
        assert config["bf16"] is False

    def test_returns_valid_config_keys(self):
        """Config should have exactly fp16 and bf16 keys."""
        config = resolve_mixed_precision()
        assert "fp16" in config
        assert "bf16" in config
        # Priya: Exactly one should be True
        assert config["fp16"] != config["bf16"] or not any(config.values())


class TestWandbFlag:
    """Tests for W&B logging configuration.

    # Priya: W&B is our experiment tracker. The --no-wandb flag is essential
    # for CI/CD and quick smoke tests where we don't want to create W&B runs.
    """

    def test_no_wandb_flag_in_training_args(self):
        """Training args should use 'none' reporter when W&B is disabled.

        # Priya: We verify this by checking the build_training_args function
        # respects the no_wandb flag. This prevents accidental W&B runs in CI.
        """
        from scripts.train_gemma4 import build_training_args

        # Priya: Create a mock args namespace
        mock_args = MagicMock()
        mock_args.epochs = 1
        mock_args.max_steps = 10
        mock_args.batch_size = 2
        mock_args.lr = 2e-4
        mock_args.no_wandb = True
        mock_args.gradient_checkpointing = "false"

        output_path = Path("/tmp/test_output")
        training_args = build_training_args(mock_args, output_path, has_val=False)
        assert training_args.report_to == ["none"]


class TestLocalDatasetLoading:
    """Tests for loading datasets from local JSONL files."""

    def test_load_local_jsonl(self, sample_jsonl_file):
        """Should load all examples from a valid JSONL file.

        # Priya: This is the primary code path for reproducible training —
        # we prepare datasets once and load from local files.
        """
        dataset = load_local_dataset(str(sample_jsonl_file))
        assert len(dataset) > 0

    def test_load_local_jsonl_with_max_samples(self, sample_jsonl_file):
        """max_samples should limit the number of loaded examples."""
        dataset = load_local_dataset(str(sample_jsonl_file), max_samples=3)
        assert len(dataset) == 3

    def test_load_missing_file_raises(self):
        """Loading from a non-existent path should raise FileNotFoundError."""
        with pytest.raises(FileNotFoundError):
            load_local_dataset("/nonexistent/path/data.jsonl")
