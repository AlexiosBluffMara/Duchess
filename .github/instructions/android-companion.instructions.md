---
description: "Use when developing the companion smartphone Android app. Covers Gemma 3n inference, BLE server for glasses, Tailscale mesh, Jetpack Compose UI, cloud API client, background processing, notifications."
applyTo: "app-phone/**"
---

# Companion Phone App Development

## Role of the Phone

The companion phone is the **central hub** of each worker's Duchess experience:
- Runs Gemma 3n E2B for Tier 2 inference
- Acts as BLE GATT server for connected glasses
- Participates in Tailscale mesh network
- Handles cloud API communication (escalation + nightly batch upload)
- Displays alerts, dashboards, and settings via Jetpack Compose
- Broadcasts GPS location for geospatial alert routing
- **ALL workers have the phone app, even those without glasses**

## Development Rules

- Kotlin with Jetpack Compose for all UI
- Minimum SDK: 30 (Android 11+)
- Google Play Services available (unlike glasses)
- Foreground service for BLE server + mesh + location broadcasting
- Notification channels: CRITICAL (PPE alerts), WARNING (hazards), INFO (status)
- Offline-first: all data saved locally, synced when connectivity available
- Gemma 3n model loaded lazily (only when Tier 2 inference needed)
- All strings bilingual (EN + ES)

## Key Services

### BLE GATT Server
- Advertise as Duchess companion device
- Accept connections from paired Vuzix glasses
- Receive: frame metadata, escalation requests
- Send: alert commands, status updates, model update notifications

### Tailscale Mesh Participant
- Maintain VPN connection to site mesh
- Broadcast GPS location every 30 seconds
- Receive mesh alerts from other devices
- Route escalations to cloud via exit node

### Gemma 3n Inference
- Load model on first escalation request (not at startup — saves memory)
- Run with low temperature (0.1) for deterministic safety responses
- Output structured JSON with EN and ES descriptions
- Unload model after 5 minutes of inactivity (free memory for other apps)

### Cloud API Client
- Retrofit/Ktor for HTTP client
- API key authentication (stored in Android Keystore, not SharedPreferences)
- Exponential backoff on failures
- Queue failed requests for retry
- Nightly batch: upload video segments over WiFi only

## Phone-Only Workers

Workers without glasses are first-class users:
- They receive all alerts via push notification
- Their phone participates in mesh (provides location, receives alerts)
- Dashboard shows site-wide safety status
- They can report hazards manually through the app
- No BLE server needed if no glasses paired
