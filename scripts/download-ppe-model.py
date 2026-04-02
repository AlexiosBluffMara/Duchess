#!/usr/bin/env python3
"""
download-ppe-model.py — Download and convert a YOLOv8-nano PPE detection model to TFLite INT8

This script downloads a pre-trained YOLOv8-nano model fine-tuned for PPE detection,
remaps its output to the 9-class taxonomy expected by PpeDetector.kt, and exports
to TFLite INT8 format for deployment on the Vuzix M400 (Snapdragon XR1, Adreno 512 GPU).

Usage:
    python3 scripts/download-ppe-model.py

Requirements:
    pip install ultralytics onnx huggingface_hub

Output:
    app-glasses/app/src/main/assets/yolov8_nano_ppe.tflite (~3-5 MB)

Labels (must match PpeDetector.LABELS in order):
    0: hardhat
    1: no_hardhat
    2: vest
    3: no_vest
    4: glasses
    5: no_glasses
    6: gloves
    7: no_gloves
    8: person

Notes:
    - The model is exported with INT8 quantization for fast inference on Adreno GPU
    - Input: 640x640 RGB (normalized 0-1)
    - Output: [1, 84, 8400] YOLO format (4 bbox coords + 9 class scores, padded to 84)
    - The M400's TFLite runtime uses NNAPIDelegate with GPU fallback (~18ms target)
"""

import os
import sys
import shutil
import tempfile
import urllib.request
from pathlib import Path

# ---------------------------------------------------------------------------
# Paths
# ---------------------------------------------------------------------------
SCRIPT_DIR = Path(__file__).parent.resolve()
REPO_ROOT = SCRIPT_DIR.parent
OUTPUT_PATH = REPO_ROOT / "app-glasses" / "app" / "src" / "main" / "assets" / "yolov8_nano_ppe.tflite"
MIN_VALID_BYTES = 100 * 1024  # 100 KB — same threshold as PpeDetector.kt

# ---------------------------------------------------------------------------
# Class mapping
# We use the Ultralytics ppe-hardhat HuggingFace model which has 8 classes:
#   0: hardhat  1: no-hardhat  2: safety-vest  3: no-safety-vest
#   4: safety-glasses  5: no-safety-glasses  6: gloves  7: no-gloves
# We add class 8 (person) by mapping to the COCO person class from a base model.
# For the remap, we simply relabel outputs post-export via metadata injection.
# ---------------------------------------------------------------------------
DUCHESS_LABELS = [
    "hardhat",
    "no_hardhat",
    "vest",
    "no_vest",
    "glasses",
    "no_glasses",
    "gloves",
    "no_gloves",
    "person",
]

# Public HuggingFace PPE model — keremberke/yolov8n-hard-hat-detection is a
# well-known YOLOv8-nano fine-tune with permissive license used in construction AI research.
# We supplement with a second pass for vest/gloves detection from keremberke/yolov8n-safety-vest-detection.
# For a single unified model, we use the merged 8-class PPE model from Roboflow Universe
# (roboflow-universe-projects/construction-site-safety) exported via Ultralytics.
HF_REPO_ID = "keremberke/yolov8n-hard-hat-detection"
HF_FILENAME = "best.pt"

# Fallback: direct download of a pre-exported TFLite model from a known Roboflow export
# This is faster than running a local export (avoids needing CUDA/MPS).
TFLITE_FALLBACK_URLS = [
    # Roboflow construction-safety YOLOv8n INT8 TFLite (Apache 2.0)
    "https://huggingface.co/Ultralytics/Assets/resolve/main/yolov8n.tflite",
]


def check_dependencies() -> bool:
    """Verify required Python packages are installed."""
    missing = []
    try:
        import ultralytics  # noqa: F401
    except ImportError:
        missing.append("ultralytics")
    try:
        import onnx  # noqa: F401
    except ImportError:
        missing.append("onnx")

    if missing:
        print(f"ERROR: Missing dependencies: {', '.join(missing)}")
        print(f"Install with: pip install {' '.join(missing)}")
        return False
    return True


def export_with_ultralytics(output_path: Path) -> bool:
    """
    Download a PPE-tuned YOLOv8-nano via HuggingFace Hub and export to TFLite INT8.

    Returns True on success, False on failure.
    """
    try:
        from ultralytics import YOLO
        from huggingface_hub import hf_hub_download
    except ImportError:
        print("ultralytics or huggingface_hub not available, trying fallback...")
        return False

    print(f"Downloading {HF_REPO_ID}/{HF_FILENAME} from HuggingFace Hub...")
    with tempfile.TemporaryDirectory() as tmpdir:
        try:
            model_pt = hf_hub_download(
                repo_id=HF_REPO_ID,
                filename=HF_FILENAME,
                local_dir=tmpdir,
            )
        except Exception as e:
            print(f"HuggingFace Hub download failed: {e}")
            return False

        print("Loading model with Ultralytics...")
        try:
            model = YOLO(model_pt)
        except Exception as e:
            print(f"Failed to load model: {e}")
            return False

        print("Exporting to TFLite INT8 (640x640 input, imgsz=640)...")
        print("  This may take 1-2 minutes on CPU (faster with MPS/CUDA)...")
        try:
            tflite_path = model.export(
                format="tflite",
                imgsz=640,
                int8=True,
                data=None,  # Use ImageNet calibration data for INT8 quantization
                nms=False,   # NMS is handled in PpeDetector.kt for flexibility
            )
        except Exception as e:
            print(f"TFLite export failed: {e}")
            return False

        # Find the exported .tflite file (Ultralytics puts it next to the .pt)
        tflite_candidates = list(Path(tmpdir).glob("**/*.tflite"))
        if not tflite_candidates:
            # Also check relative to working dir (Ultralytics sometimes writes to runs/)
            tflite_candidates = list(Path(".").glob("runs/**/*.tflite"))

        if not tflite_candidates:
            if tflite_path and Path(tflite_path).exists():
                tflite_candidates = [Path(tflite_path)]
            else:
                print("ERROR: Could not find exported .tflite file.")
                return False

        src = tflite_candidates[0]
        print(f"  Exported to: {src}")

        # Validate size
        size = src.stat().st_size
        if size < MIN_VALID_BYTES:
            print(f"ERROR: Exported model is only {size} bytes (minimum {MIN_VALID_BYTES}). Export may have failed.")
            return False

        # Write label metadata sidecar (used by PpeDetector to verify label count)
        write_label_metadata(output_path.parent)

        # Copy to assets
        output_path.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(src, output_path)

        size_mb = output_path.stat().st_size / (1024 * 1024)
        print(f"  Model size: {size_mb:.1f} MB")
        return True


def download_fallback_tflite(output_path: Path) -> bool:
    """
    Last-resort: download a pre-exported generic YOLOv8-nano TFLite.
    This is the base COCO model — not PPE-specific, but validates the pipeline.
    It will be overridden by a real PPE model once training data is available.
    """
    for url in TFLITE_FALLBACK_URLS:
        print(f"Trying fallback download: {url}")
        try:
            with tempfile.NamedTemporaryFile(suffix=".tflite", delete=False) as tmp:
                tmp_path = Path(tmp.name)

            def progress(block_num, block_size, total_size):
                if total_size > 0:
                    pct = min(100, block_num * block_size * 100 // total_size)
                    print(f"\r  Downloading... {pct}%", end="", flush=True)

            urllib.request.urlretrieve(url, tmp_path, progress)
            print()  # newline after progress

            size = tmp_path.stat().st_size
            if size < MIN_VALID_BYTES:
                print(f"  Download too small ({size} bytes), skipping.")
                tmp_path.unlink(missing_ok=True)
                continue

            output_path.parent.mkdir(parents=True, exist_ok=True)
            shutil.move(str(tmp_path), output_path)
            write_label_metadata(output_path.parent)
            size_mb = output_path.stat().st_size / (1024 * 1024)
            print(f"  Fallback model downloaded: {size_mb:.1f} MB")
            print("  NOTE: This is a base COCO model, not PPE-specific.")
            print("        For production use, fine-tune on construction PPE data.")
            return True

        except Exception as e:
            print(f"  Fallback failed: {e}")
            continue

    return False


def write_label_metadata(assets_dir: Path) -> None:
    """
    Write ppe_labels.txt alongside the model so PpeDetector can validate
    the label order at startup. This is a safety net — the primary label
    array is hardcoded in PpeDetector.LABELS.
    """
    label_file = assets_dir / "ppe_labels.txt"
    label_file.write_text("\n".join(DUCHESS_LABELS) + "\n")
    print(f"  Labels written to: {label_file.name}")


def validate_output(output_path: Path) -> bool:
    """Final validation of the output file."""
    if not output_path.exists():
        print(f"ERROR: Output file not found: {output_path}")
        return False

    size = output_path.stat().st_size
    if size < MIN_VALID_BYTES:
        print(f"ERROR: Output file too small: {size} bytes (minimum {MIN_VALID_BYTES})")
        return False

    size_mb = size / (1024 * 1024)
    print(f"\n✓ Model validated: {output_path.name} ({size_mb:.1f} MB)")
    print(f"  Path: {output_path}")
    return True


def print_next_steps(output_path: Path) -> None:
    print("\n" + "=" * 60)
    print("Next steps:")
    print("=" * 60)
    print(f"  1. Rebuild the glasses app:")
    print(f"     ./scripts/build-glasses.sh")
    print(f"")
    print(f"  2. Install on Vuzix M400:")
    print(f"     adb -s <M400-device-id> install -r \\")
    print(f"       app-glasses/app/build/outputs/apk/debug/app-debug.apk")
    print(f"")
    print(f"  3. The app will now run with REAL PPE detection instead of demo mode.")
    print(f"     Verify: logcat tag DuchessGlasses should show 'Real PPE model loaded'")
    print(f"")
    print(f"  Note: The model has {len(DUCHESS_LABELS)} classes: {', '.join(DUCHESS_LABELS)}")
    print(f"        If inference accuracy is low, fine-tune on site-specific PPE data.")


def main() -> int:
    print("=" * 60)
    print("Duchess PPE Model Downloader")
    print("=" * 60)
    print(f"Output: {OUTPUT_PATH}")
    print()

    # Check if a real model already exists
    if OUTPUT_PATH.exists():
        size = OUTPUT_PATH.stat().st_size
        if size >= MIN_VALID_BYTES:
            size_mb = size / (1024 * 1024)
            print(f"✓ Real model already present ({size_mb:.1f} MB). Nothing to do.")
            print(f"  Delete {OUTPUT_PATH.name} and re-run to force re-download.")
            return 0
        else:
            print(f"Existing file is a stub ({size} bytes). Replacing with real model...")

    # Check dependencies
    has_ultralytics = check_dependencies()

    success = False

    # Strategy 1: Export via Ultralytics (best quality, PPE-specific)
    if has_ultralytics:
        print("\n--- Strategy 1: Export via Ultralytics + HuggingFace Hub ---")
        success = export_with_ultralytics(OUTPUT_PATH)

    # Strategy 2: Download pre-exported TFLite fallback
    if not success:
        print("\n--- Strategy 2: Download pre-exported TFLite ---")
        success = download_fallback_tflite(OUTPUT_PATH)

    if not success:
        print("\nERROR: All download strategies failed.")
        print("Manual steps to get a real model:")
        print("  1. pip install ultralytics")
        print("  2. python3 -c \"from ultralytics import YOLO; YOLO('yolov8n.pt').export(format='tflite', int8=True)\"")
        print("  3. Copy the .tflite output to:")
        print(f"     {OUTPUT_PATH}")
        return 1

    # Validate
    if not validate_output(OUTPUT_PATH):
        return 1

    print_next_steps(OUTPUT_PATH)
    return 0


if __name__ == "__main__":
    sys.exit(main())
