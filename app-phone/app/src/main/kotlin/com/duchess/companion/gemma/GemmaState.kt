package com.duchess.companion.gemma

/**
 * State machine for the Gemma 4 E2B inference engine lifecycle.
 *
 * Idle → Loading → Ready ⇌ Running
 *                       ↘ Error
 *                       ↘ Idle  (after unloadModel())
 */
sealed interface GemmaState {
    /** Model not loaded. Default state after init or after unloadModel(). */
    data object Idle : GemmaState

    /** Model is being loaded into memory (~3–8 seconds on Pixel 9 Fold). */
    data object Loading : GemmaState

    /** Model loaded and ready to accept inference requests. */
    data object Ready : GemmaState

    /** Inference in progress. Only one concurrent inference allowed (mutex). */
    data object Running : GemmaState

    /** Unrecoverable error during model load or inference. */
    data class Error(val message: String) : GemmaState
}
