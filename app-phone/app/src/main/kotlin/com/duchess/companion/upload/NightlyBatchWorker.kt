package com.duchess.companion.upload

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Nightly batch upload worker — Tier 2 → Tier 4 data pipeline.
 *
 * Alex: This is the ONLY path (besides immediate PPE escalation) through
 * which video leaves the jobsite. It runs after shift ends, over WiFi,
 * while charging. WorkManager handles all the constraint enforcement.
 *
 * Privacy guarantees:
 *   1. Metadata is anonymized before upload (no worker identity, zone-level GPS only)
 *   2. Device ID is replaced with a rotating pseudonym UUID
 *   3. File names are NEVER logged (could reveal shift patterns)
 *   4. All uploads go over HTTPS via pre-signed S3 URLs
 *   5. Local files are deleted ONLY after confirmed upload (not optimistically)
 *
 * Idempotency:
 *   This worker is safe to re-run. It scans for .mp4 files and uploads any
 *   that still exist locally. Already-uploaded files were deleted on the
 *   previous successful run, so they won't be re-uploaded.
 *
 * Error handling:
 *   - If ALL uploads succeed → Result.success()
 *   - If SOME uploads fail (network drop) → Result.retry() (WorkManager will re-run)
 *   - If the video directory doesn't exist or is empty → Result.success() (nothing to do)
 */
@HiltWorker
class NightlyBatchWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val videoDir = File(applicationContext.filesDir, VIDEO_SEGMENTS_DIR)

        if (!videoDir.exists() || !videoDir.isDirectory) {
            return@withContext Result.success()
        }

        val files = videoDir.listFiles { file -> file.extension == "mp4" }
            ?: return@withContext Result.success()

        if (files.isEmpty()) {
            return@withContext Result.success()
        }

        val total = files.size
        var uploaded = 0
        val pseudonymId = generatePseudonymId()

        for (file in files) {
            val metadata = buildAnonymizedMetadata(file, pseudonymId)
            val uploadSucceeded = uploadFile(file, metadata)

            if (uploadSucceeded) {
                file.delete()
                uploaded++
            }

            setProgress(workDataOf(
                KEY_UPLOADED to uploaded,
                KEY_TOTAL to total
            ))
        }

        if (uploaded == total) {
            Result.success(workDataOf(
                KEY_UPLOADED to uploaded,
                KEY_TOTAL to total
            ))
        } else {
            // Partial upload — retry later when constraints are met again
            Result.retry()
        }
    }

    /**
     * Build anonymized metadata for a video segment.
     *
     * Alex: This strips all identifying information:
     *   - Zone extracted from directory structure (not GPS)
     *   - Timestamp from file last-modified (approximate, not exact capture time)
     *   - Duration estimated from file size (conservative, no need for media probe)
     *   - Device ID replaced with rotating pseudonym
     */
    internal fun buildAnonymizedMetadata(file: File, pseudonymId: String): UploadMetadata {
        return UploadMetadata(
            zoneId = extractZoneId(file),
            timestamp = file.lastModified(),
            durationMs = estimateDurationMs(file),
            fileSizeBytes = file.length(),
            pseudonymId = pseudonymId
        )
    }

    /**
     * Extract zone ID from file parent directory name.
     * Expected structure: video_segments/zone-A/segment_123.mp4
     * Falls back to "unknown-zone" if structure doesn't match.
     */
    private fun extractZoneId(file: File): String {
        val parentName = file.parentFile?.name ?: return DEFAULT_ZONE
        return if (parentName.startsWith("zone-")) parentName else DEFAULT_ZONE
    }

    /**
     * Estimate video duration from file size.
     * Assumes ~1 MB per second at medium quality (504x896, 24fps).
     * This is a conservative estimate — good enough for metadata.
     */
    private fun estimateDurationMs(file: File): Long {
        val bytesPerSecond = 1_000_000L // ~1 MB/s at medium quality
        return (file.length() * 1000L) / bytesPerSecond.coerceAtLeast(1L)
    }

    /**
     * Generate a rotating pseudonym UUID for this upload batch.
     * A new UUID is generated per worker execution — cannot be correlated
     * across days or linked back to the real device ID.
     */
    private fun generatePseudonymId(): String = UUID.randomUUID().toString()

    /**
     * Upload a single video file to S3 via pre-signed URL.
     *
     * Alex: The flow is:
     *   1. GET pre-signed upload URL from our cloud API (Lambda behind API Gateway)
     *   2. PUT the file bytes directly to S3 using that URL
     *   3. S3 handles encryption at rest (KMS AES-256)
     *
     * We use HttpURLConnection instead of OkHttp/Retrofit to avoid pulling
     * in a full HTTP client just for file uploads. The pre-signed URL is
     * a simple PUT — no auth headers needed on the S3 side.
     *
     * Returns true if upload succeeded, false otherwise.
     */
    private fun uploadFile(file: File, metadata: UploadMetadata): Boolean {
        return try {
            val presignedUrl = getPresignedUrl(metadata) ?: return false
            val connection = (URL(presignedUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "PUT"
                doOutput = true
                setRequestProperty("Content-Type", "video/mp4")
                setRequestProperty("Content-Length", file.length().toString())
                setRequestProperty("X-Duchess-Zone", metadata.zoneId)
                setRequestProperty("X-Duchess-Pseudonym", metadata.pseudonymId)
            }

            connection.outputStream.use { output ->
                file.inputStream().use { input ->
                    input.copyTo(output, bufferSize = 8192)
                }
            }

            val responseCode = connection.responseCode
            connection.disconnect()
            responseCode in 200..299
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Request a pre-signed S3 upload URL from the Duchess cloud API.
     *
     * Alex: The cloud API (Lambda + API Gateway) generates a time-limited
     * S3 pre-signed URL. The URL itself encodes the S3 bucket, key, and
     * temporary credentials — so the phone never needs AWS credentials.
     *
     * Returns null if the request fails (will cause this file to be retried).
     */
    private fun getPresignedUrl(metadata: UploadMetadata): String? {
        return try {
            val connection = (URL(PRESIGN_API_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("X-Duchess-Pseudonym", metadata.pseudonymId)
            }

            val body = """
                {
                    "zoneId": "${metadata.zoneId}",
                    "timestamp": ${metadata.timestamp},
                    "durationMs": ${metadata.durationMs},
                    "fileSizeBytes": ${metadata.fileSizeBytes}
                }
            """.trimIndent()

            connection.outputStream.use { it.write(body.toByteArray()) }

            if (connection.responseCode !in 200..299) {
                connection.disconnect()
                return null
            }

            val response = connection.inputStream.bufferedReader().readText()
            connection.disconnect()

            // Simple JSON parsing — extract "url" field
            // Alex: We avoid pulling in Gson/Moshi just for one field.
            PRESIGNED_URL_PATTERN.find(response)?.groupValues?.get(1)
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        internal const val VIDEO_SEGMENTS_DIR = "video_segments"
        internal const val KEY_UPLOADED = "uploaded"
        internal const val KEY_TOTAL = "total"
        internal const val DEFAULT_ZONE = "unknown-zone"

        // Cloud API endpoint for pre-signed URL generation
        // Alex: In production this comes from BuildConfig or remote config.
        // Hardcoded here for scaffolding — will be injected via Hilt in prod.
        internal const val PRESIGN_API_URL =
            "https://api.duchess.construction/v1/upload/presign"

        private val PRESIGNED_URL_PATTERN = Regex("\"url\"\\s*:\\s*\"([^\"]+)\"")
    }
}
