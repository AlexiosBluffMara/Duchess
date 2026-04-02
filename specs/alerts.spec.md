---
title: "Safety Alert System — Phone Companion"
tier: "phone"
status: "implemented"
authors: ["claude-code"]
created: "2026-04-02"
updated: "2026-04-02"
---

## Overview

Displays and manages safety alerts on the companion phone app. Alerts are sourced from two channels: live inference output from the Gemma 4 PPE pipeline (via `InferencePipelineCoordinator`) and seed demo data at startup. Workers receive real-time bilingual (English/Spanish) notifications when PPE violations are detected.

## Tier Placement

| Tier | Component | Rationale |
|------|-----------|-----------|
| Tier 2 (Phone) | AlertsViewModel, AlertListScreen, AlertDetailScreen | Phone is the hub for alert aggregation and display; workers who don't wear glasses access safety alerts here |

## Acceptance Criteria

- [x] **AC-1**: Given the app launches, When the alerts screen opens, Then demo alerts are pre-populated so the list is never empty on first run.
- [x] **AC-2**: Given a PPE violation is detected with confidence > 50%, When Gemma 4 emits a `SafetyAlert`, Then the alert appears at the top of the list in real time.
- [x] **AC-3**: Given the alert list, When the user applies a filter (ALL / CRITICAL / WARNING / INFO), Then only matching alerts are shown.
- [x] **AC-4**: Given any alert, When it is displayed, Then both English and Spanish descriptions are present in the data model (no PII, no worker identity).
- [x] **AC-5**: Given a critical alert, When it is emitted, Then a system notification is posted for workers who have the screen off.

## Inputs & Outputs

| Direction | Data | Format | Source / Destination |
|-----------|------|--------|----------------------|
| Input | Safety alerts (live) | `SafetyAlert` (sealed) | InferencePipelineCoordinator.alertFlow |
| Input | Seed alerts (demo) | List<SafetyAlert> | DemoDataProvider.getSampleAlerts() |
| Output | Alert list UI | Compose LazyColumn | AlertListScreen |
| Output | Alert detail UI | Compose Screen | AlertDetailScreen |
| Output | System notification | NotificationCompat | NotificationManager |

## Latency & Performance Budget

| Constraint | Target | Hard Limit |
|------------|--------|------------|
| Alert appears in list after detection | <500ms | <2s |
| Notification delivery | <1s | <3s |
| Memory for alert list | <10MB | <25MB |

## Privacy & Security Checklist

- [x] No worker name, face ID, or exact GPS in `SafetyAlert` data class
- [x] Alert data does not leave the phone (display only, no cloud upload of individual alerts)
- [x] No PII in log statements
- [x] Bilingual support: `messageEn` + `messageEs` fields required on all alerts
