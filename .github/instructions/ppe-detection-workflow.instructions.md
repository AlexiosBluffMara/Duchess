---
description: "Use when implementing the PPE detection pipeline, working on alert escalation logic, or modifying the detection-to-alert flow. Covers the full path from camera frame to worker notification."
applyTo: ["app-glasses/**", "app-phone/**", "ml/**"]
---

# PPE Detection Workflow

## Critical Path: Detection → Alert

```
Glasses (YOLOv8-nano) → [BLE] → Phone (Gemma 4) → [HTTPS] → Cloud → Human → [Mesh] → Worker
```

### Step 1: Continuous Detection (Tier 1)
- YOLOv8-nano runs on every frame at 10 FPS
- Three confidence bands:
  - **>0.7**: PPE confirmed, no action
  - **0.3-0.7**: Uncertain, buffer frames for temporal voting
  - **<0.3**: PPE absent, prepare for escalation
- Temporal voting: 3 out of 5 consecutive frames must agree before escalating
- This prevents single-frame false positives from camera motion, occlusion, etc.

### Step 2: Tier 2 Confirmation (Phone)
- Glasses sends high-resolution frame + detection metadata via BLE
- Gemma 4 analyzes with context: "Is this a real PPE violation?"
- If confirmed: IMMEDIATELY send video clip to cloud (do NOT wait for nightly batch)
- If denied: Log for nightly batch review, continue monitoring

### Step 3: Cloud Assessment (Tier 4)
- Large model (Qwen2.5-VL-72B or Bedrock) performs thorough analysis
- Worker identifiers MUST be anonymized before cloud processing
- If confidence > 0.85: Add to human review queue
- If confidence < 0.85: Log as potential false positive, adjust thresholds

### Step 4: Human Review
- Safety officer reviews annotated frame/clip in web dashboard
- Can confirm, dismiss, or request more context
- Dismissals feed back to model threshold tuning
- Confirmations trigger alert delivery

### Step 5: Alert Delivery
- Geospatially identify the violating worker's device (glasses or phone)
- Find nearest supervisor device
- Send bilingual alert (EN + ES simultaneously)
- Glasses wearers: AR HUD display + audio
- Phone-only workers: Push notification + vibration
- Supervisor: Notification with location and violation details

## Implementation Rules

- The pipeline MUST function even if Tier 3 (Mac server) is not present
- Phone-only workers receive push notifications, never AR alerts
- Battery levels below 10% on glasses → suspend ML inference, rely on phone-only detection for nearby workers
- All alerts MUST be bilingual — no exceptions
- Temporal voting window is configurable but defaults to 3/5 frames
- Nightly batch runs at 2:00 AM local time on ALL video not already processed via escalation
