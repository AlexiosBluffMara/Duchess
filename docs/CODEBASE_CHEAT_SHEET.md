# Duchess Codebase Cheat Sheet

> **Internal reference only** — not published on the website.
> Written so a smart 13-year-old with coding experience could explain any part of this system.

---

## The Big Picture (30-Second Version)

Duchess is a safety system for construction sites. It uses AI-powered smart glasses to detect when workers aren't wearing their safety gear (hard hats, vests, safety glasses, gloves). When it spots a problem, it alerts everyone — the worker, the supervisor's phone, and the cloud — so nobody gets hurt.

There are three tiers:
1. **Glasses** (Vuzix M400) — the fastest, dumbest brain. Spots violations in <50ms using a tiny AI model.
2. **Phone** (Pixel 9 Fold) — the medium brain. Double-checks the glasses' work using a bigger, smarter AI (Gemma 4).
3. **Cloud** (AWS) — the biggest brain. Stores everything, runs reports, trains better models overnight.

---

## How the Code Is Organized

```
app-glasses/    ← Android app for the smart glasses (Kotlin, Camera2, LiteRT)
app-phone/      ← Android app for the companion phone (Kotlin, Compose, Hilt, Gemma 4)
cloud/          ← AWS infrastructure (Lambda, S3, CDK) — not yet built
docs/           ← Website + internal docs (this file lives here)
.memory/        ← Session handoff files for AI agents (Claude + Copilot)
specs/          ← Feature specification documents
```

---

## Glasses App (`app-glasses/`) — File by File

### `MainActivity.kt` — The Conductor
**What it does:** Wires everything together. It's like the project manager — doesn't do the work itself but tells everyone else when to start, stop, and what to do.

**Key concepts:**
- **SupervisorJob** — if one worker (coroutine) crashes, the others keep going. The camera crashing shouldn't kill the Bluetooth connection.
- **Detection pipeline** — Camera takes photo → AI checks for violations → Screen shows results. Assembly line.
- **Wake lock** — Tells Android "don't go to sleep, a construction worker is wearing me!" without this, Android thinks nobody is using the device and shuts things down after 30 seconds.

### `CameraSession.kt` — The Eyes
**What it does:** Opens the camera, grabs photos, converts them from the camera's weird color format (YUV) to normal colors (RGB), and sends them downstream at the right frame rate.

**Key concepts:**
- **callbackFlow** — Translator between two coding styles. Camera uses "call me when ready" (callbacks). Our code uses "I'll wait for a stream" (Flow). callbackFlow bridges them.
- **YUV → RGB via RenderScript** — The camera speaks one color language, the AI speaks another. RenderScript translates using the GPU (~2ms), which is 7x faster than doing it on the CPU (~15ms).
- **Frame skipping** — The camera shoots at 30fps but the AI can only process 2-10 frames per second. We silently drop the extra frames instead of queuing them up (that would eat all the memory).

### `PpeDetector.kt` — The Brain
**What it does:** Runs a YOLOv8-nano AI model to spot hard hats, vests, gloves, and safety glasses (or their absence) in camera frames.

**Key concepts:**
- **LiteRT (formerly TFLite)** — Google's framework for running AI models on phones/embedded devices. The model is ~4MB, tiny enough for the glasses.
- **GPU delegate** — Runs inference on the graphics chip instead of the CPU. 18ms vs 35ms per frame. Falls back to CPU if the GPU is being weird.
- **NMS (Non-Maximum Suppression)** — The AI draws 5 boxes around the same hard hat. NMS picks the best one and throws away duplicates. Like choosing the best photo from a burst.
- **IoU (Intersection over Union)** — Measures how much two rectangles overlap. 0 = no overlap, 1 = identical. Used by NMS to decide if two boxes are "the same thing."
- **Stub/demo mode** — If no real AI model is loaded (we only have a 256-byte placeholder), the detector generates fake but realistic-looking detections so the entire pipeline can be tested end-to-end.
- **9 detection classes** — hardhat, no_hardhat, vest, no_vest, glasses, no_glasses, gloves, no_gloves, person. Labels starting with "no_" are violations.

### `TemporalVoter.kt` — The Jury
**What it does:** Prevents false alarms by requiring 3 out of 5 consecutive frames to agree before raising an alert.

**Key concepts:**
- **Sliding window** — Like asking "is that a bird?" 5 times in a row. If 3/5 times you see it, it's probably real. 1/5 = probably just a leaf.
- **Per-label voting** — Each type of violation (no_hardhat, no_vest, etc.) has its own independent 5-frame window. Missing a hard hat doesn't affect the vest vote.
- **Zero allocations** — Uses pre-allocated BooleanArray with a circular cursor. No `new` objects after initialization = no garbage collection pauses.

### `HudRenderer.kt` — The Display
**What it does:** Draws everything the worker sees on the 640x360 glasses display: status bar, detection boxes, diagnostic info.

**Key concepts:**
- **Zero-allocation onDraw()** — All Paint objects (brushes, pens, colors) are created once at startup and reused forever. Creating new objects during drawing would cause visible stuttering from garbage collection.
- **@Volatile fields** — The AI thread writes new detection results, and the drawing thread reads them. @Volatile ensures the drawing thread always sees the latest data, not stale old data.
- **Bilingual** — Status messages show English first, Spanish below. "PPE ALERT" / "ALERTA EPP". Non-negotiable requirement.
- **Dark-dominant design** — The display is OLED, so black pixels = zero power. The less we light up, the longer the battery lasts.

### `BleGattClient.kt` — The Walkie-Talkie
**What it does:** Connects to the companion phone via Bluetooth Low Energy (BLE). Sends violation data up, receives alert pushes down.

**Key concepts:**
- **GATT client** — The glasses are the "client" that connects to the phone's "server." This is backwards from what most people expect (usually the small device is the server).
- **Exponential backoff reconnection** — If Bluetooth drops (steel walls, concrete, elevator), we retry with increasing delays: 1s, 2s, 4s, 8s, 16s. Prevents all 10 pairs of glasses from reconnecting simultaneously and crashing the phone.
- **Pipe-delimited payloads** — Alerts are sent as "id|type|severity|zone|time|english|spanish" instead of JSON to save space. BLE can only send ~247 bytes at a time.
- **CCCD subscription** — A special BLE setup step that says "hey phone, push me alerts automatically instead of making me ask every time."

### `BatteryAwareScheduler.kt` — The Power Manager
**What it does:** Monitors battery level and automatically slows down the AI to extend battery life.

**Key concepts:**
- **4 modes** — FULL (10fps, >50% battery), REDUCED (5fps, >30%), MINIMAL (2fps, >15%), SUSPENDED (0fps, <15%). Like your phone's battery saver but with 4 levels.
- **Sticky broadcast** — Android tells us the current battery level immediately when we register, then again on every change. No polling needed.
- **Pure function for testing** — `modeForBatteryLevel(percentage)` is a simple function with no Android dependencies, making it easy to unit test.

---

## Phone App (`app-phone/`) — File by File

### `StreamViewModel.kt` — The Video Controller
**What it does:** Manages the live video stream from the Meta glasses using the DAT SDK. Shows frames on screen and feeds them to the AI pipeline.

**Key concepts:**
- **DAT SDK** — Meta's SDK for connecting to their smart glasses (Ray-Ban Wayfarer). Handles device discovery, permission, and video streaming.
- **DatResult<T, E>** — Meta's error handling type. Always handle both success AND failure paths. Never use `getOrThrow()`.
- **Sealed class StreamUiState** — The screen can be in exactly one state: Idle, Connecting, Streaming, or Error. No ambiguity.

### `InferencePipelineCoordinator.kt` — The Traffic Cop
**What it does:** Takes video frames from the stream, runs them through Gemma 4 AI at 1fps, and routes the results to alerts and BLE.

**Key concepts:**
- **Throttling** — The camera sends 24 frames per second, but we only analyze 1 per second. Running AI on every frame would overheat the phone.
- **Double-check pattern** — Before grabbing the mutex lock, we check "should I even bother?" If the answer is "too soon since last check," we skip instantly without the overhead of locking. Then inside the lock, we check AGAIN because two threads might have passed the first check simultaneously.
- **Alert routing** — All violations go to the dashboard. Severity 3+ also get pushed to the glasses via BLE and broadcast over the mesh network.

### `GemmaInferenceEngine.kt` — The Smart Brain
**What it does:** Runs Google's Gemma 4 E2B vision model to analyze camera frames for safety violations. This is the "are you SURE that's a violation?" double-check.

**Key concepts:**
- **Builder pattern** — Instead of `new Engine(path, 512, 1, ...)` (which arguments are what?!), we use `.setModelPath(...)`, `.setMaxTokens(...)`, etc. Like filling out a labeled form.
- **Vision + text** — The old code only sent text descriptions to the AI ("frame is 640x480"). The fixed version sends the ACTUAL IMAGE using `session.addImage()`. The AI can now see what's in the photo.
- **JSON extraction** — The AI sometimes wraps its JSON answer in markdown code fences or adds chatty text around it. We try 3 strategies to dig out the JSON: look for ```json fences, find { and }, or just take whatever we got.
- **Lazy loading** — The model takes 3-8 seconds to load and uses ~1.2GB RAM. We only load it when first needed, and unload it after 5 minutes of inactivity to free memory.

### `BleGattServer.kt` — The Phone's Mailbox
**What it does:** Runs a BLE GATT server on the phone so multiple pairs of glasses can connect and receive alert pushes.

**Key concepts:**
- **Server, not client** — The phone is the "server" (hub) that multiple glasses connect to. One phone can push alerts to many glasses at once.
- **Characteristics = mailboxes** — Each characteristic is like a mailbox with different access rules:
  - ALERT (READ + NOTIFY) — glasses can check it OR get automatic pushes
  - STATUS (READ + WRITE) — glasses write escalation requests, phone reads them
- **CCCD descriptor** — A special registration that tells the phone "this pair of glasses wants automatic push notifications." Without it, notifications silently fail. (This bug takes hours to debug.)
- **Fire-and-forget notifications** — The `false` parameter in `notifyCharacteristicChanged()` means we don't wait for the glasses to confirm receipt. Faster, and fine because the next alert will come soon anyway.

### `MeshManager.kt` — The Site Network
**What it does:** Manages the Tailscale mesh VPN network that connects all phones on the jobsite for peer-to-peer alert sharing.

**Key concepts:**
- **Graceful degradation** — Try mesh network first (best, reaches everyone). If that fails, queue the alert and retry later. Never lose an alert, never crash.
- **Privacy-first serialization** — When we serialize alerts for the network, we deliberately exclude worker names, face data, and exact GPS. Only zone-level location. A hacker would only see "someone in Zone B isn't wearing a hardhat."
- **Offline queue** — Alerts are saved in a ConcurrentLinkedQueue (thread-safe, lock-free) when the network is down. Capped at 100 — newer alerts are more actionable than stale ones.
- **Connectivity check** — Every 30 seconds, we "knock on the coordinator's door" (TCP connect probe). If it opens, we're on the mesh. If not, we flip to disconnected and start queuing.

### `DashboardViewModel.kt` — The Control Center
**What it does:** Powers the main dashboard screen with live safety score, zone statuses, alert feed, and mesh connectivity.

**Key concepts:**
- **Safety score formula** — `maxOf(10, 100 - criticalCount * 8)`. Starts at 100, loses 8 points per critical alert. Floor of 10 (never zero — zero looks like "no data"). ~11 criticals drops you to the floor.
- **Status levels** — CRITICAL (any critical alert), WARNING (>5 active), CAUTION (1-5 active), SAFE (zero). Like a traffic light for the whole jobsite.
- **WhileSubscribed(5000)** — Keeps data streams alive for 5 seconds after the screen goes away. Covers phone rotation (screen dies and rebuilds in ~1 second) without restarting all data loading.

---

## Key Design Decisions (Why We Did It This Way)

| Decision | Why |
|----------|-----|
| No Google Play Services | Vuzix M400 runs pure AOSP. No Firebase, no CameraX, no GMS. |
| Camera2 instead of CameraX | CameraX requires Play Services. Camera2 is harder but works on AOSP. |
| RenderScript (deprecated) | Still the fastest YUV→RGB path on the XR1. Will replace with Vulkan compute eventually. |
| Pipe-delimited BLE payloads | JSON takes ~200 bytes, pipes take ~120 bytes. BLE MTU is only 247 bytes. |
| 3/5 temporal voting | Balances false positive filtering (~500ms latency at 10fps) vs. detection speed. |
| Exponential backoff reconnection | Prevents "thundering herd" when multiple glasses reconnect simultaneously. |
| Zero-allocation onDraw() | Garbage collection pauses cause visible stuttering on the 640x360 display. |
| @Volatile over locks | Simpler, faster for single-field reads/writes between two threads. |
| Gemma 4 vision vs text-only | The old code sent dimensions as text. The AI needs to SEE the actual image. |
| 1fps inference throttle | Balances detection latency (~2-3s worst case) vs. NPU heat + battery drain. |
| Privacy-first alerts | No PII ever. No names, no faces, no exact GPS. Zone-level location only. |
| Bilingual EN/ES | ~30% of US construction workforce is Spanish-speaking. Safety is not English-only. |

---

## How Data Flows (The Full Pipeline)

```
[Camera on Glasses]
       |
       v
[CameraSession] ── YUV → RGB conversion (GPU, ~2ms) ── frame skipping
       |
       v
[PpeDetector] ── YOLOv8-nano inference (GPU, ~18ms) ── NMS ── confidence filter
       |
       v
[TemporalVoter] ── 3/5 frame agreement check
       |
       v (if violation confirmed)
[BleGattClient] ── sends to phone via BLE (escalation)
       |
       v
[BleGattServer on Phone] ── receives escalation
       |
       v
[InferencePipelineCoordinator] ── throttles to 1fps ── runs Gemma 4
       |
       v
[GemmaInferenceEngine] ── vision + text analysis (~500ms-2s)
       |
       v (if violation confirmed)
[DashboardViewModel] ── updates safety score, alert feed
       |
       v (severity >= 3)
[BleGattServer] ── pushes alert BACK to glasses HUD
[MeshManager] ── broadcasts to all phones on mesh network
```

---

## Common Questions

**Q: Why does the glasses app have a "stub mode"?**
A: We don't have a real trained YOLOv8-nano model yet (just a 256-byte placeholder). Stub mode generates fake but realistic detections so the entire pipeline — camera, inference, HUD, BLE, temporal voting — can be tested end-to-end without a real model. It proves the architecture works.

**Q: Why is the phone the BLE server and not the glasses?**
A: One phone can serve multiple pairs of glasses. If the glasses were the server, each pair would need a separate connection to every other device. The hub-and-spoke model (phone = hub, glasses = spokes) scales better.

**Q: Why Tailscale and not just WiFi?**
A: WiFi connects you to the internet. Tailscale creates a private encrypted mesh network where all phones on the jobsite can talk directly to each other (peer-to-peer, <10ms latency) without going through the internet. It also works across different networks (WiFi, cellular, hotspot).

**Q: What happens when the battery dies?**
A: As battery drops, the system gracefully degrades: 10fps → 5fps → 2fps → screen-only. At 15% battery, the AI brain sleeps entirely and the glasses just display alerts pushed from the phone. This extends a 3-hour runtime to 4+ hours.

**Q: Why both English AND Spanish for every alert?**
A: ~30% of US construction workers are Spanish-speaking. A safety alert that someone can't read is useless. Bilingual is a non-negotiable requirement — every user-facing string exists in both languages.

**Q: Why no cloud in the current code?**
A: The cloud tier (`cloud/`) isn't built yet. Tiers 1 (glasses) and 2 (phone) work independently without cloud. Cloud will add: nightly model retraining, cross-site dashboards, OSHA report generation, and fleet management.

---

*Last updated: 2026-04-03 by Claude Code*
*Internal document — do not publish to website*
