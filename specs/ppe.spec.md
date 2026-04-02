---
title: "PPE Detection — Vuzix M400 On-Glass Edge Inference"
tier: "glasses"
status: "implemented"
authors: ["claude-code"]
created: "2026-04-02"
updated: "2026-04-02"
---

## Overview

Runs YOLOv8-nano PPE detection on live camera frames directly on the Vuzix M400 using LiteRT with GPU delegate (Qualcomm XR1). Detects missing helmets, vests, gloves, and eye protection in <50ms per frame. Positive detections are forwarded via BLE to the companion phone for Gemma 4 confirmation (Tier 2 escalation). A temporal voter debounces detections across frames to reduce false positives.

## Tier Placement

| Tier | Component | Rationale |
|------|-----------|-----------|
| Tier 1 (Glasses) | PpeDetector, TemporalVoter | <50ms hard latency limit; video never leaves the device; 6GB RAM budget |

## Acceptance Criteria

- [x] **AC-1**: Given the glasses camera is active, When a frame is captured, Then the YOLOv8-nano model runs inference in <50ms (GPU path) or <50ms (CPU fallback).
- [x] **AC-2**: Given the GPU delegate fails to initialize, When inference is requested, Then the detector transparently falls back to CPU with 2 threads.
- [x] **AC-3**: Given N consecutive frames with a detection above threshold, When the TemporalVoter confirms, Then a single detection event is emitted (no per-frame noise).
- [x] **AC-4**: Given a confirmed PPE violation, When detection is emitted, Then it is forwarded via BLE to the companion phone (no network required).
- [x] **AC-5**: Given the detector is closed, When `close()` is called, Then the LiteRT interpreter and GPU delegate are released without memory leaks.

## Inputs & Outputs

| Direction | Data | Format | Source / Destination |
|-----------|------|--------|----------------------|
| Input | Camera frame | `Bitmap` (640×640 center-crop) | Camera2 pipeline |
| Input | Model weights | INT8 TFLite flatbuffer | `assets/yolov8_nano_ppe.tflite` |
| Output | Detections | `List<Detection>` (class, confidence, bbox) | TemporalVoter → BLE → Phone |

## Latency & Performance Budget

| Constraint | Target | Hard Limit |
|------------|--------|------------|
| Inference latency (GPU) | ~18ms | <50ms |
| Inference latency (CPU) | ~35ms | <50ms |
| Model memory | <100MB | <200MB (of 500MB ML budget) |
| Battery impact | <20% overhead vs idle | No continuous governor lock |

## Privacy & Security Checklist

- [x] Video frames never leave the glasses — only `Detection` metadata is forwarded via BLE
- [x] No face recognition or worker identity in detection output (bounding box + class only)
- [x] No PII in log statements
- [x] All processing is local — no network access on Tier 1
