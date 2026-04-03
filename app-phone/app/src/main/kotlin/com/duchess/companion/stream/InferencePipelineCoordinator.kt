package com.duchess.companion.stream

import com.duchess.companion.ble.AlertSerializer
import com.duchess.companion.ble.BleGattServer
import com.duchess.companion.gemma.GemmaAnalysisResult
import com.duchess.companion.gemma.GemmaInferenceEngine
import com.duchess.companion.mesh.MeshManager
import com.duchess.companion.model.SafetyAlert
import com.meta.wearable.dat.camera.types.VideoFrame
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

// TODO-PRINCIPAL: Pipeline coordinator review — critical issues:
//   1. No backpressure mechanism. If BLE sendAlert() blocks or slows down (Bluetooth
//      congestion on a crowded jobsite with 20+ devices), the processMutex holds while
//      BLE writes, blocking the NEXT inference. Decouple BLE delivery into its own
//      coroutine channel with a bounded buffer and drop-oldest policy.
//   2. System.currentTimeMillis() is not monotonic — NTP jumps can cause the throttle
//      to skip frames or double-fire. Use SystemClock.elapsedRealtime() on Android.
//   3. No circuit breaker on GemmaInferenceEngine failures. If the model enters an
//      error state (OOM, corrupted weights), every frame still attempts analyze() →
//      gets an error result → silently drops it. Need a cooldown after N consecutive
//      failures before retrying, with metric emission for dashboards.
//   4. MIN_CONFIDENCE = 0.5 is hardcoded. This should be a remote config value so we
//      can tune it in production without a new APK release. Same for BLE_SEVERITY_THRESHOLD.
//   5. No alert deduplication. If the same worker is missing a hardhat for 30 seconds,
//      we emit 30 identical alerts. Need temporal dedup: same (violationType, zoneId)
//      within a window → suppress duplicates, update existing alert's lastSeen timestamp.
//   6. alertFlow has extraBufferCapacity=32 but no overflow strategy. If 33 alerts
//      queue before the subscriber processes them, emit() suspends and blocks inference.
//      Use BufferOverflow.DROP_OLDEST.
//
// TODO-ML-PROF: The 1-second throttle interval is a design choice that deserves ablation:
//   - At 1fps, we miss transient violations (worker briefly removes hardhat). What's
//     the violation duration distribution in real construction footage? If median
//     violation lasts <2s, 1fps catches <50% of events.
//   - For the Unsloth fine-tuned model: measure if the confidence distribution shifts
//     after QLoRA. If the fine-tuned model is more confident overall, MIN_CONFIDENCE=0.5
//     may be too low (letting through false positives the base model would suppress).
//   - The toSafetyAlert() extension maps GemmaAnalysisResult to SafetyAlert 1:1. But
//     Gemma 4's function calling could return MULTIPLE violations per frame (worker
//     missing BOTH hardhat AND vest). The current pipeline only handles single-violation
//     results. Need to support multi-violation output from the structured function call.

/**
 * Coordinates the real-time inference pipeline: VideoFrame → Gemma 4 E2B → SafetyAlert → BLE.
 *
 * Alex: This singleton bridges three independent systems that previously had no connection:
 *   1. StreamViewModel — produces VideoFrames from Meta glasses at 24fps
 *   2. GemmaInferenceEngine — analyzes frames for PPE violations (~500ms–2s per frame)
 *   3. AlertsViewModel + BleGattServer — consume SafetyAlerts for UI and HUD delivery
 *
 * The gap before this class existed: StreamViewModel collected frames and put them in
 * latestFrame (a StateFlow). That was it. Nothing consumed those frames for inference.
 * Alerts were a static hardcoded list in DemoDataProvider. Gemma was never called.
 *
 * THROTTLING: At most one inference per THROTTLE_MS. The 24fps stream produces 24 frames
 * per second. Each inference takes 500ms-2s. Running inference on every frame would:
 *   a) Queue up 24 pending analyze() calls per second (Mutex makes them serial)
 *   b) Keep the model in GemmaState.Running continuously → overheating
 *   c) Consume the full Pixel 9 Fold NPU capacity with no room for other tasks
 * 1 FPS for inference is the sweet spot: catches violations within ~1 second while
 * leaving the NPU 96% idle for background tasks and app responsiveness.
 *
 * ALERT ROUTING:
 *   - All detected violations emit on alertFlow (SharedFlow consumed by AlertsViewModel)
 *   - Severity >= BLE_SEVERITY_THRESHOLD (3 = serious) also pushed via BLE GATT notify
 *     to all connected glasses → immediate HUD overlay for the worker
 *
 * CONFIDENCE FILTER: Results with confidence < MIN_CONFIDENCE are dropped.
 * At 0.5 threshold we eliminate "the model saw something but isn't sure" false positives
 * that would generate alert spam. Safety is important but alert fatigue is real.
 *
 * CONCURRENCY: The processMutex ensures that even if StreamViewModel launches
 * processFrame() as a non-blocking coroutine, only one inference runs at a time.
 * The throttle check + mutex together prevent both redundant calls AND concurrent calls.
 */
@Singleton
class InferencePipelineCoordinator @Inject constructor(
    private val engine: GemmaInferenceEngine,
    private val bleServer: BleGattServer,
    private val meshManager: MeshManager,
) {
    companion object {
        // Alex: 1 inference per second. Balances detection latency vs. NPU load.
        // Worst-case violation detection delay = THROTTLE_MS + inference time ≈ 2-3 seconds.
        // OSHA requires "prompt" notification of hazards — 3 seconds qualifies.
        const val THROTTLE_MS = 1_000L

        // Alex: Severity >= 3 triggers BLE push to glasses HUD.
        // 0-1 = info/minor → app notification only, worker doesn't need immediate HUD
        // 2 = moderate → app notification only, can wait for worker to check phone
        // 3+ = serious/severe/critical → immediate HUD push, worker must see it NOW
        const val BLE_SEVERITY_THRESHOLD = 3

        // Alex: Minimum confidence to emit an alert. 0.5 = 50% confidence threshold.
        // Below this, the model is guessing and we're better off staying silent.
        // At 1 FPS, a false positive every few seconds would desensitize workers.
        const val MIN_CONFIDENCE = 0.5
    }

    // Alex: replay=0 because alerts are fire-and-forget — a new subscriber shouldn't
    // get all past inference alerts, only future ones. AlertsViewModel initializes
    // with demo data for historical context; live alerts append on top.
    // extraBufferCapacity=32 means up to 32 alerts can queue if the subscriber is slow.
    private val _alertFlow = MutableSharedFlow<SafetyAlert>(replay = 0, extraBufferCapacity = 32)
    val alertFlow: SharedFlow<SafetyAlert> = _alertFlow.asSharedFlow()

    // Alex: @Volatile because lastInferenceMs is read/written from different coroutines.
    // The processMutex prevents concurrent inference, but the pre-mutex throttle check
    // (before acquiring the lock) reads this value from the calling coroutine's thread.
    @Volatile
    private var lastInferenceMs = 0L

    // Alex: Mutex serializes inference calls. Even if StreamViewModel fires two
    // processFrame() calls quickly (e.g., connection hiccup bursts frames), the mutex
    // ensures they run sequentially through the engine, not concurrently.
    private val processMutex = Mutex()

    /**
     * Process a video frame through the full inference pipeline.
     *
     * Called from StreamViewModel.collectFrames() for each frame when inference is enabled.
     * Returns immediately (no-op) if throttle has not elapsed since the last inference.
     * Otherwise: acquires mutex → runs Gemma inference → emits alert → BLE push if severe.
     *
     * Alex: Why suspend? Because engine.analyze() is a suspend function (it blocks on the
     * MediaPipe inference). StreamViewModel wraps each call in a separate launch() so frame
     * collection doesn't stall waiting for inference to complete. The throttle + mutex means
     * extra launches are cheap no-ops most of the time.
     *
     * PRIVACY: frame bitmap never leaves this method — only the typed SafetyAlert does.
     *
     * @param frame VideoFrame from the Meta glasses DAT SDK stream
     * @param zoneId Current zone identifier (e.g., "zone-A-framing"). Used in SafetyAlert.
     *               NOT exact GPS — zone-level granularity only (privacy + OSHA).
     */
    suspend fun processFrame(frame: VideoFrame, zoneId: String) {
        // Pre-mutex throttle check: avoids locking if we know we'd skip anyway.
        // This is intentionally non-atomic (no lock needed for a "maybe skip" check).
        val now = System.currentTimeMillis()
        if (now - lastInferenceMs < THROTTLE_MS) return

        processMutex.withLock {
            // Re-check under lock: two coroutines might both pass the pre-mutex check
            // if they arrive within the same millisecond. The double-check prevents
            // running inference twice for the same throttle window.
            val nowLocked = System.currentTimeMillis()
            if (nowLocked - lastInferenceMs < THROTTLE_MS) return@withLock
            lastInferenceMs = nowLocked

            val result = engine.analyze(frame)

            if (!result.violationDetected || result.confidence < MIN_CONFIDENCE) return@withLock

            val alert = result.toSafetyAlert(zoneId)
            _alertFlow.emit(alert)

            // Alex: BLE push for serious+ violations only. The sendAlert() call is
            // synchronous (it just writes to the GATT characteristic and calls notify).
            // Non-blocking from our perspective — the BLE stack handles delivery async.
            if (alert.severity >= BLE_SEVERITY_THRESHOLD) {
                val payload = AlertSerializer.serialize(alert)
                bleServer.sendAlert(payload)
                // Parallel path: mesh → Tailscale coordinator → cloud escalation.
                // broadcastAlert() is non-suspending — enqueues internally, drains on reconnect.
                meshManager.broadcastAlert(alert)
            }
        }
    }

    /**
     * Emit a manually-triggered alert (e.g., worker pressed the "report hazard" button).
     *
     * Alex: The inference pipeline is automated, but workers can also manually report
     * hazards they observe. This routes manual reports through the same alert flow and
     * BLE broadcast as automated detections — same delivery guarantees.
     */
    suspend fun emitManualAlert(alert: SafetyAlert) {
        _alertFlow.emit(alert)
        if (alert.severity >= BLE_SEVERITY_THRESHOLD) {
            val payload = AlertSerializer.serialize(alert)
            bleServer.sendAlert(payload)
            meshManager.broadcastAlert(alert)
        }
    }
}

/**
 * Convert a GemmaAnalysisResult to a SafetyAlert for the alert system.
 *
 * Alex: Extension function keeps the mapping logic close to the types it converts,
 * without polluting either GemmaAnalysisResult (model output) or SafetyAlert (domain model).
 * This is the seam where inference output becomes an actionable safety alert.
 *
 * PRIVACY: GemmaAnalysisResult already contains no PII — Gemma analyzed pixels, not
 * worker identity. SafetyAlert adds an ID, zone, and timestamp. Still no PII.
 * SafetyAlertTest's PII scanner validates this constraint on every CI run.
 */
fun GemmaAnalysisResult.toSafetyAlert(zoneId: String): SafetyAlert = SafetyAlert(
    id = UUID.randomUUID().toString(),
    violationType = violationType ?: "UNKNOWN",
    severity = severity,
    zoneId = zoneId,
    timestamp = System.currentTimeMillis(),
    messageEn = descriptionEn,
    messageEs = descriptionEs,
)
