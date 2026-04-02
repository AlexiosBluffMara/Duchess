---
description: "Use when setting up or configuring the Tailscale mesh network, device provisioning, video streaming between devices, or network security policies."
applyTo: ["**/mesh/**", "**/tailscale/**", "**/network/**"]
---

# Mesh Network Instructions

## Network Principles

1. **On-site safety features MUST work without internet** — the mesh operates independently
2. **All traffic encrypted** with WireGuard (Tailscale manages keys)
3. **Least-privilege ACLs** — phones can't access admin endpoints, glasses have no direct network access (BLE to phone only)
4. **Failover is automatic**: Direct peer (<10ms) → Relay through peers → DERP fallback (50-150ms)
5. **Bandwidth budget**: 2.5-5 Mbps per video stream, max 10 concurrent streams per site

## Device Roles

| Device | Network Role | Tailscale Tags |
|--------|-------------|----------------|
| Vuzix Glasses | No direct mesh (BLE to phone) | N/A |
| Worker Phone | Mesh peer, location broadcaster | `tag:phone` |
| Supervisor Phone | Mesh peer, alert receiver, admin | `tag:phone, tag:supervisor` |
| Mac Server | Aggregation node, Tier 3 inference | `tag:macserver` |
| Cloud Gateway | Exit node for escalation/batch | `tag:cloud` |

## Security Rules

- Pre-auth keys: single-use, per-site, rotated monthly
- SSO required for admin (supervisor) access
- MFA on all admin nodes
- Certificate pinning for cloud gateway communication
- No plaintext data transmission between any devices
- Audit logs for all ACL changes

## Location Broadcasting

- Every phone broadcasts GPS every 30 seconds via Tailscale mesh channel
- Location data stored in memory only (not persisted to disk)
- Used for geospatial alert routing — find nearest device to violation
- Location accuracy: GPS (outdoor) or WiFi/BLE triangulation (indoor)
