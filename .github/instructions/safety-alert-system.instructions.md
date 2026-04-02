---
description: "Use when designing or implementing the safety alert system, alert escalation logic, severity levels, notification delivery, or bilingual alert content."
applyTo: ["**/alerts/**", "**/notifications/**"]
---

# Safety Alert System Instructions

## Alert Severity Levels

| Level | Name | Visual | Audio | Haptic | Auto-dismiss | Action Required |
|-------|------|--------|-------|--------|-------------|-----------------|
| 1 | INFO | Green text | None | None | 3s | None |
| 2 | WARNING | Yellow, border | Soft chime | None | 10s or voice | Acknowledge |
| 3 | CRITICAL | Red, thick border | Alert tone | Short vibrate | Must acknowledge | Worker + supervisor |
| 4 | EMERGENCY | Flashing red | Loud alarm | Continuous | Cannot dismiss | Stop-work authority |

## Bilingual Requirement

**Every alert MUST include both English and Spanish text.** This is not optional.

- AR display: Show primary language (worker preference), secondary below in smaller text
- Phone notification: Title in primary language, body includes both
- Audio: Announce in worker's preferred language
- Voice acknowledgment: Accept "OK" in either language

## Alert Delivery Rules

1. **Glasses wearers**: AR HUD alert (visual + audio + haptic per severity)
2. **Phone-only workers**: Push notification (vibration pattern matches severity)
3. **Supervisors**: Always notified for CRITICAL and EMERGENCY
4. **Geospatial routing**: Alert goes to the specific worker, not broadcast to everyone
5. **Escalation timeout**: If CRITICAL alert not acknowledged in 30s, auto-escalate to supervisor
6. **Offline handling**: Alert displays locally even without mesh connectivity; sync state when reconnected

## Alert Message Format

```
AR Display (640x360):
Line 1: Icon + alert text (4 words max)
Line 2: Secondary language
Line 3: Action instruction

Phone Notification:
Title: [SEVERITY] Alert text
Body: Bilingual description + action
Action buttons: Acknowledge / View Details / Call Supervisor
```
