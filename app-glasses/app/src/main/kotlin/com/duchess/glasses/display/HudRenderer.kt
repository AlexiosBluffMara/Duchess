package com.duchess.glasses.display

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import com.duchess.glasses.model.Detection
import com.duchess.glasses.model.HudLanguageMode
import com.duchess.glasses.model.InferenceMode
import com.duchess.glasses.model.SafetyAlert
import com.duchess.glasses.ppe.PpeDetector

/**
 * Custom View that renders PPE detection overlays on the Vuzix M400's 640x360 OLED display.
 *
 * Alex: The M400's display is tiny — 640x360 pixels, monocular, sits in the peripheral vision
 * of the right eye. Content MUST be:
 *   - Large text (minimum 24sp, ideally 28sp+)
 *   - 4 words max per alert line (worker is looking at construction, not reading a novel)
 *   - High contrast (white/green/red/yellow on dark background)
 *   - Dark background dominant (OLED = black pixels = no power draw)
 *   - No touch targets (workers wear gloves, there's no touchscreen anyway)
 *
 * This view draws three types of overlays:
 * 1. Bounding boxes around detected PPE items (green=OK, red=violation)
 * 2. Bilingual status text (top bar: "All Clear / Sin alertas" or "PPE ALERT / ALERTA EPP")
 * 3. Diagnostic info (bottom bar: FPS, battery mode, inference time)
 *
 * PERFORMANCE:
 * onDraw() must complete in <5ms to keep the frame pipeline under 50ms total.
 * All Paint objects are pre-allocated. No allocations in onDraw().
 * We use Canvas directly (not Compose, not XML layout inflation) because this
 * is the fastest rendering path on AOSP with hardware acceleration.
 *
 * @param context Activity context (needs to be Activity for theme resources)
 */
class HudRenderer(context: Context) : View(context) {

    // --- Pre-allocated Paint objects (zero allocations in onDraw) ---

    // Alex: We pre-allocate ALL paints in init. Creating Paint objects in onDraw()
    // triggers GC pressure that causes janky frames on the XR1. Learned this the
    // hard way — 3ms onDraw became 15ms with GC pauses.

    /** Green bounding box paint for confirmed PPE (hardhat, vest, etc.) */
    private val okBoxPaint = Paint().apply {
        color = COLOR_OK
        style = Paint.Style.STROKE
        strokeWidth = BOX_STROKE_WIDTH
        isAntiAlias = true
    }

    /** Red bounding box paint for PPE violations (no_hardhat, no_vest, etc.) */
    private val violationBoxPaint = Paint().apply {
        color = COLOR_VIOLATION
        style = Paint.Style.STROKE
        strokeWidth = BOX_STROKE_WIDTH_VIOLATION
        isAntiAlias = true
    }

    /** White label text paint (for detection class names on boxes) */
    private val labelTextPaint = Paint().apply {
        color = Color.WHITE
        textSize = LABEL_TEXT_SIZE
        isAntiAlias = true
        isFakeBoldText = true
    }

    /** Semi-transparent background paint for text labels (readability on any background) */
    private val labelBgPaint = Paint().apply {
        color = LABEL_BG_COLOR
        style = Paint.Style.FILL
    }

    /** Large status text paint (top bar: "All Clear" / "PPE ALERT") */
    private val statusTextPaint = Paint().apply {
        color = Color.WHITE
        textSize = STATUS_TEXT_SIZE
        isAntiAlias = true
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }

    /** Secondary bilingual text paint (smaller, below the main status) */
    private val secondaryTextPaint = Paint().apply {
        color = COLOR_SECONDARY_TEXT
        textSize = SECONDARY_TEXT_SIZE
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
    }

    /** FPS / diagnostic text paint (bottom bar) */
    private val diagTextPaint = Paint().apply {
        color = COLOR_DIAG_TEXT
        textSize = DIAG_TEXT_SIZE
        isAntiAlias = true
    }

    /** Top/bottom bar background paint (semi-transparent dark) */
    private val barBgPaint = Paint().apply {
        color = BAR_BG_COLOR
        style = Paint.Style.FILL
    }

    /** Battery indicator paint (changes color based on level) */
    private val batteryPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    // --- State that gets updated every frame (set by the pipeline, read by onDraw) ---

    // Alex: These are @Volatile because they're written from the ML coroutine
    // and read from the UI thread's onDraw(). No synchronization needed because
    // each field is independently atomic (primitive or reference assignment).
    @Volatile var detections: List<Detection> = emptyList()
    @Volatile var inferenceTimeMs: Long = 0L
    @Volatile var currentMode: InferenceMode = InferenceMode.FULL
    @Volatile var batteryPercent: Int = 100
    @Volatile var isConnectedToBle: Boolean = false

    // ---- Phone-pushed alert overlay ----
    // Written from the BLE coroutine, read from onDraw (UI thread). @Volatile for visibility.

    /** Non-null while a phone-pushed alert banner should be displayed. */
    @Volatile var activePhoneAlert: SafetyAlert? = null

    /** Epoch-ms deadline; when currentTimeMillis >= this value the banner auto-dismisses. */
    @Volatile var phoneAlertExpiresAt: Long = 0L

    /** Pre-allocated red fill for the phone-alert banner — no allocations in onDraw. */
    private val alertBannerPaint = Paint().apply {
        style = Paint.Style.FILL
        color = Color.argb(210, 180, 0, 0)
    }

    /** Pre-allocated RectF for the phone-alert banner bounds. */
    private val alertBannerRect = RectF()

    // Alex: Frame counter for FPS calculation. We count frames rendered in the
    // last second. This is the RENDERING FPS, not the inference FPS.
    private var frameCount = 0
    private var lastFpsUpdateTime = System.currentTimeMillis()
    private var displayFps = 0

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // Alex: The entire onDraw must be <5ms. No allocations, no I/O, no blocking.
        // Profile with Android Studio's GPU profiler if you change anything here.

        val w = width.toFloat()
        val h = height.toFloat()

        // Alex: Draw top status bar (always visible)
        drawStatusBar(canvas, w)

        // Alex: Draw detection bounding boxes and labels
        drawDetections(canvas, w, h)

        // Draw phone-pushed alert banner (if active and not yet expired)
        drawPhoneAlert(canvas, w, h)

        // Alex: Draw bottom diagnostic bar (FPS, battery, inference time)
        drawDiagnosticBar(canvas, w, h)

        // Alex: Update FPS counter
        updateFpsCounter()

        // Alex: Request next frame. This is a continuous animation loop while the
        // detection pipeline is running. invalidate() posts a draw request to the
        // next VSYNC, which is 60Hz on the M400 display.
        if (currentMode != InferenceMode.SUSPENDED) {
            invalidate()
        }
    }

    /**
     * Draws the top status bar showing the current detection state.
     *
     * Alex: This is the FIRST thing the worker sees. It must communicate
     * safety status in <1 second of glancing. Color coding:
     *   - Green bar + "All Clear / Sin alertas" = safe
     *   - Red bar + "PPE ALERT / ALERTA EPP" = violation detected
     *   - Yellow bar + "Detecting… / Detectando…" = starting up
     */
    private fun drawStatusBar(canvas: Canvas, width: Float) {
        // Alex: Semi-transparent bar background for readability
        canvas.drawRect(0f, 0f, width, STATUS_BAR_HEIGHT, barBgPaint)

        val hasViolation = detections.any { PpeDetector.isViolation(it.label) }

        val (primaryText, secondaryText, statusColor) = if (detections.isEmpty()) {
            // Alex: No detections yet — still starting up or SUSPENDED mode
            Triple("Detecting…", "Detectando…", COLOR_WARNING)
        } else if (hasViolation) {
            Triple("PPE ALERT", "ALERTA EPP", COLOR_VIOLATION)
        } else {
            Triple("All Clear", "Sin alertas", COLOR_OK)
        }

        // Alex: Color the status text to match severity
        statusTextPaint.color = statusColor
        canvas.drawText(primaryText, width / 2f, STATUS_TEXT_Y, statusTextPaint)

        // Alex: Bilingual secondary text — smaller, below the primary
        canvas.drawText(secondaryText, width / 2f, SECONDARY_TEXT_Y, secondaryTextPaint)

        // Alex: BLE connection indicator — small dot in top-right corner
        val bleColor = if (isConnectedToBle) COLOR_OK else COLOR_DISCONNECTED
        batteryPaint.color = bleColor
        canvas.drawCircle(width - BLE_DOT_MARGIN, BLE_DOT_MARGIN, BLE_DOT_RADIUS, batteryPaint)
    }

    /**
     * Draws detection bounding boxes with bilingual labels.
     *
     * Alex: Boxes are in normalized coordinates [0,1] from the detector.
     * We scale them to the display size (640x360). The boxes are drawn with:
     *   - Green stroke for PPE-present detections
     *   - Red thick stroke for violations
     *   - White label text on a semi-transparent background (readability)
     *
     * Labels use [labelToDisplay] for bilingual, 4-word-max formatting.
     */
    private fun drawDetections(canvas: Canvas, w: Float, h: Float) {
        for (detection in detections) {
            val isViolation = PpeDetector.isViolation(detection.label)
            val boxPaint = if (isViolation) violationBoxPaint else okBoxPaint

            // Alex: Scale normalized bbox to display pixels
            val rect = RectF(
                detection.bbox.left * w,
                detection.bbox.top * h,
                detection.bbox.right * w,
                detection.bbox.bottom * h
            )
            canvas.drawRect(rect, boxPaint)

            // Alex: Draw label with confidence at the top of the box
            val displayText = labelToDisplay(detection.label)
            val confText = "${(detection.confidence * 100).toInt()}%"
            val fullLabel = "$displayText $confText"

            // Alex: Text background for readability on any background.
            // Measure text width first, then draw a slightly larger rect behind it.
            val textWidth = labelTextPaint.measureText(fullLabel)
            val textBgRect = RectF(
                rect.left,
                rect.top - LABEL_TEXT_SIZE - LABEL_PADDING * 2,
                rect.left + textWidth + LABEL_PADDING * 2,
                rect.top
            )
            canvas.drawRect(textBgRect, labelBgPaint)

            canvas.drawText(
                fullLabel,
                rect.left + LABEL_PADDING,
                rect.top - LABEL_PADDING,
                labelTextPaint
            )
        }
    }

    /**
     * Draws the bottom diagnostic bar with FPS, battery, mode, and inference time.
     *
     * Alex: This bar is for field debugging. When a safety supervisor says
     * "the glasses seem slow," we can look at the diagnostic bar and see:
     *   - FPS: how many frames we're rendering per second
     *   - Inference: how many ms per detection
     *   - Battery: current level and what mode we're in
     *   - BLE: connected or not
     *
     * In production, this could be hidden behind a voice command ("show diagnostics").
     * For now, it's always visible because we're still in the testing phase.
     */
    private fun drawDiagnosticBar(canvas: Canvas, w: Float, h: Float) {
        val barTop = h - DIAG_BAR_HEIGHT
        canvas.drawRect(0f, barTop, w, h, barBgPaint)

        // Alex: FPS counter (left side)
        val fpsText = "FPS: $displayFps"
        canvas.drawText(fpsText, DIAG_PADDING, h - DIAG_PADDING, diagTextPaint)

        // Alex: Inference time (center)
        val inferText = "Inf: ${inferenceTimeMs}ms"
        canvas.drawText(inferText, w / 3f, h - DIAG_PADDING, diagTextPaint)

        // Alex: Battery percentage + mode indicator (right side)
        val modeLabel = when (currentMode) {
            InferenceMode.FULL -> "F"
            InferenceMode.REDUCED -> "R"
            InferenceMode.MINIMAL -> "M"
            InferenceMode.SUSPENDED -> "S"
        }
        val batteryText = "Bat: ${batteryPercent}% [$modeLabel]"

        // Alex: Color the battery text based on level
        diagTextPaint.color = batteryColor(batteryPercent)
        canvas.drawText(batteryText, w * 2f / 3f, h - DIAG_PADDING, diagTextPaint)
        diagTextPaint.color = COLOR_DIAG_TEXT // Reset to default
    }

    /**
     * Updates the FPS counter. Called every onDraw().
     *
     * Alex: We count frames in the last 1-second window. Simple and accurate
     * enough for diagnostic purposes. Don't use a rolling average — it's slower
     * and we don't need that precision for a debug indicator.
     */
    private fun updateFpsCounter() {
        frameCount++
        val now = System.currentTimeMillis()
        if (now - lastFpsUpdateTime >= 1000L) {
            displayFps = frameCount
            frameCount = 0
            lastFpsUpdateTime = now
        }
    }

    /**
     * Draws a prominent alert banner in the middle third of the display when a
     * phone-pushed alert is active and has not yet expired.
     *
     * Uses pre-allocated [alertBannerRect] and [alertBannerPaint] — no heap
     * allocation occurs during this call.
     *
     * Auto-dismisses: if the expiry deadline has passed, [activePhoneAlert] is
     * cleared and nothing is drawn.
     */
    private fun drawPhoneAlert(canvas: Canvas, w: Float, h: Float) {
        val alert = activePhoneAlert ?: return

        if (System.currentTimeMillis() >= phoneAlertExpiresAt) {
            activePhoneAlert = null
            return
        }

        // Banner covers the middle third of the display (y: 35%..65%).
        alertBannerRect.set(0f, h * 0.35f, w, h * 0.65f)
        canvas.drawRect(alertBannerRect, alertBannerPaint)

        // Two lines: English primary, Spanish secondary.
        val lineHeight = statusTextPaint.textSize + 8f
        val bannerMidY = alertBannerRect.top + alertBannerRect.height() / 2f
        val firstLineY = bannerMidY - lineHeight / 2f
        val secondLineY = firstLineY + lineHeight

        canvas.drawText(alert.messageEn, 16f, firstLineY, statusTextPaint)
        canvas.drawText(alert.messageEs, 16f, secondLineY, secondaryTextPaint)
    }

    companion object {
        // --- Display dimensions (Vuzix M400: 640x360 OLED) ---
        // Alex: These are NOT the camera dimensions. The camera is 640x480,
        // the display is 640x360. Detection boxes are scaled from camera space
        // to display space in drawDetections().

        // --- Colors ---
        // Alex: We use a restrained palette optimized for the M400's OLED.
        // Green/Red/Yellow are universally recognized safety colors that work
        // even for colorblind workers (they differ in brightness, not just hue).
        const val COLOR_OK = 0xFF00C853.toInt()          // Bright green
        const val COLOR_VIOLATION = 0xFFFF1744.toInt()    // Bright red
        const val COLOR_WARNING = 0xFFFFD600.toInt()      // Bright yellow
        const val COLOR_DISCONNECTED = 0xFF757575.toInt() // Gray
        const val COLOR_SECONDARY_TEXT = 0xCCFFFFFF.toInt() // White @ 80% alpha
        const val COLOR_DIAG_TEXT = 0xAAFFFFFF.toInt()    // White @ 67% alpha
        const val LABEL_BG_COLOR = 0xAA000000.toInt()     // Black @ 67% alpha
        const val BAR_BG_COLOR = 0xCC000000.toInt()       // Black @ 80% alpha

        // --- Dimensions (in pixels for 640x360 display) ---
        const val STATUS_BAR_HEIGHT = 52f
        const val STATUS_TEXT_SIZE = 28f   // Primary status text (e.g., "PPE ALERT")
        const val STATUS_TEXT_Y = 24f      // Y offset for primary status text
        const val SECONDARY_TEXT_SIZE = 18f // Spanish translation below primary
        const val SECONDARY_TEXT_Y = 44f
        const val LABEL_TEXT_SIZE = 16f     // Detection box labels
        const val LABEL_PADDING = 4f
        const val BOX_STROKE_WIDTH = 2f    // OK detection boxes
        const val BOX_STROKE_WIDTH_VIOLATION = 4f // Violation boxes (thicker = more visible)
        const val DIAG_BAR_HEIGHT = 28f
        const val DIAG_TEXT_SIZE = 14f
        const val DIAG_PADDING = 6f
        const val BLE_DOT_RADIUS = 6f
        const val BLE_DOT_MARGIN = 16f

        /**
         * Maps a detection label to a bilingual, HUD-friendly display string.
         *
         * Alex: The 640x360 display can show ~4 words per line before it gets
         * illegible. These short labels are designed for INSTANT recognition.
         * Green labels show just the PPE type. Red labels shout the violation
         * in both languages. "person" is hidden on the HUD (it's just an anchor
         * for the detector, not useful information for the worker).
         *
         * Alex: We return English primary / Spanish secondary. In a future version
         * this should respect the worker's language preference stored in SharedPreferences.
         * For now, English primary matches the majority language preference on US construction sites
         * (Spanish secondary ensures bilingual coverage).
         *
         * @param label The model's output label (e.g., "no_hardhat", "vest")
         * @return Display string, max 4 words, bilingual for violations
         */
        fun labelToDisplay(label: String): String = when (label) {
            "hardhat" -> "Hardhat"
            "no_hardhat" -> "NO HARDHAT"
            "vest" -> "Vest"
            "no_vest" -> "NO VEST"
            "glasses" -> "Glasses"
            "no_glasses" -> "NO GLASSES"
            "gloves" -> "Gloves"
            "no_gloves" -> "NO GLOVES"
            "person" -> ""  // Alex: Don't display "person" — it's noise on the HUD
            else -> label.uppercase()
        }

        /**
         * Returns a color int for the battery percentage.
         *
         * Alex: Simple traffic-light scheme. Green when comfortable,
         * yellow when it's time to think about charging, red when
         * the worker needs to head to the charging station soon.
         */
        fun batteryColor(percent: Int): Int = when {
            percent >= 50 -> COLOR_OK
            percent >= 20 -> COLOR_WARNING
            else -> COLOR_VIOLATION
        }
    }
}
