---
title: "Camera Stream — Glasses to Phone"
tier: "phone"
status: "implemented"
authors: ["claude-code"]
created: "2026-04-01"
updated: "2026-04-01"
---

## Overview

Streams live video from connected Meta Ray-Ban glasses to the companion phone via the DAT SDK. The phone displays the live feed and allows photo capture for safety analysis. This is the primary visual input for Tier 2 inference.

## Tier Placement

| Tier | Component | Rationale |
|------|-----------|-----------|
| Tier 2 (Phone) | StreamViewModel, StreamScreen | Phone is the processing hub; glasses are sensors only. Video frames stay on-device (privacy). |

## Acceptance Criteria

- [x] **AC-1**: Given glasses are paired, When user taps "Live Feed", Then a stream session starts and frames display on screen.
- [x] **AC-2**: Given an active stream, When user taps "Capture Photo", Then a photo is captured and a confirmation snackbar appears.
- [x] **AC-3**: Given a stream error, When the session fails, Then an error message displays with a retry option.
- [x] **AC-4**: Given the ViewModel is cleared, Then the stream session is stopped automatically.

## Inputs & Outputs

| Direction | Data | Format | Source / Destination |
|-----------|------|--------|----------------------|
| Input | Video frames | `VideoFrame` (bitmap) | DAT SDK StreamSession |
| Output | Displayed frame | Compose `Image` | StreamScreen UI |
| Output | Captured photo | `PhotoData` | DAT SDK capturePhoto() |

## Latency & Performance Budget

| Constraint | Target | Hard Limit |
|------------|--------|------------|
| Frame display latency | <100ms | <200ms |
| Photo capture | <500ms | <1s |
| Memory footprint | <50MB | <100MB |

## Privacy & Security Checklist

- [x] No PII in log statements
- [x] Worker identifiers anonymized before cloud
- [x] Video data encrypted in transit (WireGuard / TLS)
- [x] Location data not persisted to disk
- [x] No secrets in code (stored in local.properties or Secrets Manager)

## Bilingual Requirements

| Key | English | Spanish |
|-----|---------|---------|
| `stream_connecting` | Connecting to glasses... | Conectando a los lentes... |
| `stream_active` | Live Feed | Transmision en vivo |
| `stream_disconnected` | Glasses disconnected | Lentes desconectados |
| `capture_photo` | Capture Photo | Capturar foto |

## Open Questions

None — initial scaffold complete.

## Out of Scope

- Frame-level AI inference (handled by GemmaInferenceService)
- Recording/saving video to disk
- Multi-device streaming

## Implementation Notes

- Uses `AutoDeviceSelector()` for automatic device selection
- `VideoQuality.MEDIUM` at 24fps as default configuration
- All SDK calls use `DatResult.fold()` — no exceptions
- State managed via `StateFlow` in ViewModel

---
*Spec approved by Duke before implementation begins.*
