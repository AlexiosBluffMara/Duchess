package com.duchess.glasses.model

/**
 * Battery-aware inference modes controlling how aggressively we run the
 * YOLOv8-nano PPE detector on camera frames.
 *
 * Alex: The Vuzix M400 has a *750mAh* battery. That's smaller than most smartwatches.
 * Running YOLOv8-nano at 10 FPS with the GPU delegate eats battery like crazy.
 * These modes let BatteryAwareScheduler throttle inference automatically so the
 * glasses last a full 4-hour shift instead of dying at lunch.
 *
 * The FPS values here are TARGETS — CameraSession uses them to skip frames.
 * We don't actually change the camera capture rate (that'd require reopening the
 * camera device, which causes a visible flicker on the XR1 chipset). Instead,
 * we capture at the hardware rate and only run inference on every Nth frame.
 *
 * @property fps Target frames per second for ML inference. 0 = suspended (no inference).
 */
enum class InferenceMode(val fps: Int) {
    /**
     * Full speed — 10 FPS inference. Battery >= 50%.
     * Alex: 10 FPS is already conservative. YOLOv8-nano can do 30+ on the XR1
     * GPU delegate, but the battery can't sustain it. 10 FPS gives us smooth
     * detection while keeping power draw manageable for a 4hr shift.
     */
    FULL(10),

    /**
     * Reduced — 5 FPS inference. Battery 30-49%.
     * Alex: At 5 FPS we still catch PPE violations (people don't move THAT fast)
     * but we cut inference power roughly in half. Good tradeoff territory.
     */
    REDUCED(5),

    /**
     * Minimal — 2 FPS inference. Battery 15-29%.
     * Alex: 2 FPS is barely usable for real-time detection but still catches
     * stationary violations (someone standing without a hardhat). At this point
     * we're in "survive until end of shift" mode.
     */
    MINIMAL(2),

    /**
     * Suspended — 0 FPS, no inference. Battery < 15%.
     * Alex: Below 15% we kill inference entirely to preserve battery for BLE
     * communication with the phone. The phone can still detect PPE violations
     * using its own camera if needed. Glasses become a dumb display-only device
     * that shows alerts pushed from the phone via BLE.
     */
    SUSPENDED(0)
}
