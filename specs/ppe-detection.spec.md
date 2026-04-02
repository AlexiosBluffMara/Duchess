---
title: "PPE Detection — On-Glasses Inference"
tier: "glasses"
status: "implemented"
authors: ["claude-code"]
created: "2026-04-01"
updated: "2026-04-01"
---

## Overview

Real-time PPE (Personal Protective Equipment) detection running on the Vuzix M400 glasses using a YOLOv8-nano TFLite model. Captures camera frames at 15 FPS, runs INT8-quantized inference, and renders bounding boxes + bilingual alerts on the 640x360 HUD.

## Tier Placement

| Tier | Component | Rationale |
|------|-----------|-----------|
| Tier 1 (Glasses) | CameraSession, PpeDetector, HudRenderer | Sub-50ms latency requirement. Video never leaves device (privacy). Battery-constrained — must run efficiently on Snapdragon XR1. |
| Tier 2 (Phone) | BleGattServer receives escalations | Phone handles Gemma 4 triage for violations detected on-glasses. |

## Acceptance Criteria

- [x] **AC-1**: Given glasses are running, When a frame is captured, Then PPE detection runs and results display on HUD within 50ms.
- [x] **AC-2**: Given a PPE violation is detected (no_hardhat/no_vest), When confidence > 70%, Then an escalation is sent to the phone via BLE.
- [x] **AC-3**: Given no violations, Then HUD shows "OK / Sin alertas" in bilingual text.
- [x] **AC-4**: Given the camera session ends, Then all resources (camera, interpreter) are released.

## Inputs & Outputs

| Direction | Data | Format | Source / Destination |
|-----------|------|--------|----------------------|
| Input | Camera frames | YUV_420_888 Bitmap (640x480, 15 FPS) | CameraSession via Camera2 |
| Input | TFLite model | INT8 quantized .tflite (~4MB) | Assets |
| Output | Detections | `List<Detection>` | PpeDetector |
| Output | HUD overlay | Canvas drawing (640x360) | HudRenderer |
| Output | Escalation | BLE GATT write | BleGattClient → phone |

## Latency & Performance Budget

| Constraint | Target | Hard Limit |
|------------|--------|------------|
| Inference latency | <30ms | <50ms |
| Frame-to-HUD | <50ms | <100ms |
| Memory footprint | <200MB | <500MB (Vuzix 6GB limit) |
| Battery | 4hr active use | 3hr minimum |

## Privacy & Security Checklist

- [x] No PII in log statements
- [x] Video frames processed on-device only — never transmitted
- [x] No worker identification — detects PPE, not people
- [x] No secrets in code
- [x] Detection results contain only label/confidence/bbox

## Bilingual Requirements

| Key | English | Spanish |
|-----|---------|---------|
| `status_ok` | All Clear | Sin alertas |
| `status_violation` | PPE ALERT | ALERTA EPP |
| `label_no_hardhat` | NO HARDHAT | SIN CASCO |
| `label_no_vest` | NO VEST | SIN CHALECO |

## Open Questions

None — initial scaffold complete.

## Out of Scope

- Model training (handled by ml/ pipeline)
- Cloud escalation logic (handled by app-phone and cloud/)
- Voice command integration (future Vuzix SDK work)

## Implementation Notes

- Camera2 callbacks wrapped in `callbackFlow` per project convention
- GPU delegate with NNAPI fallback for TFLite
- YUV_420_888 → Bitmap conversion in CameraSession
- Battery-aware: PARTIAL_WAKE_LOCK only during active detection

---
*Spec approved by Duke before implementation begins.*
