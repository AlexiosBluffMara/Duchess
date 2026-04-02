---
description: "Use when handling video data, worker information, biometric data, or any data that leaves the construction site mesh. Covers HIPAA compliance, data anonymization, encryption requirements, privacy-by-design principles."
applyTo: "**"
---

# Data Privacy & Compliance Instructions

## Core Privacy Principles

1. **Video never leaves the jobsite** unless explicitly escalated through the PPE pipeline OR nightly batch upload
2. **Worker identifiers anonymized** before any data reaches cloud services
3. **WireGuard encryption** on all mesh traffic — Tailscale cannot decrypt (keys managed by org)
4. **HIPAA-compliant** for any biometric data (face detection, body pose, health indicators)
5. **No PII in logs** — not in CloudWatch, not in local logs, not in crash reports
6. **Contractually mandatory** for union sites and government contracts

## Data Classification

| Data Type | Classification | Storage | Encryption | Retention |
|-----------|---------------|---------|------------|-----------|
| Video (raw) | Sensitive | On-device only | Device encryption | Deleted after upload or 7 days |
| Video (cloud) | Sensitive | S3 (KMS encrypted) | AES-256 | 90 days, then delete |
| Alert metadata | Internal | DynamoDB | AWS managed | 1 year |
| Worker location | Sensitive | Memory only (not persisted) | WireGuard in transit | Session only |
| Safety reports | Internal | DynamoDB + S3 | AWS managed | 3 years |
| Model weights | Proprietary | Artifact registry | Encrypted at rest | Indefinite |

## Anonymization Requirements

Before ANY data leaves the jobsite mesh to cloud:
- Replace worker device IDs with rotating pseudonyms
- Strip GPS coordinates to zone-level granularity (not exact location)
- Remove any face/name data from video metadata
- Log only violation type, zone, timestamp — not worker identity
- Human review dashboard shows anonymized video (face blurring if needed)

## Compliance Checklist (Every PR)

- [ ] No PII in log statements
- [ ] Video data encrypted at rest and in transit
- [ ] Worker identifiers anonymized before cloud
- [ ] Location data not persisted to disk
- [ ] API endpoints authenticated (no open access)
- [ ] Secrets in secure storage (Keystore/Secrets Manager), never in code
