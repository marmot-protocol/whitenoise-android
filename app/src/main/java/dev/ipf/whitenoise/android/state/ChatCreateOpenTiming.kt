package dev.ipf.whitenoise.android.state

import android.util.Log

/**
 * Short-lived, privacy-safe timing for one create/open attempt (#1729).
 * Logs stage names and elapsed durations only — never names, identities, or ids.
 */
internal class ChatCreateOpenTiming private constructor() {
    private val startedAtNanos = System.nanoTime()
    private var lastMarkNanos = startedAtNanos

    fun mark(stage: String) {
        val now = System.nanoTime()
        val elapsedMs = (now - startedAtNanos) / NANOS_PER_MS
        val deltaMs = (now - lastMarkNanos) / NANOS_PER_MS
        lastMarkNanos = now
        Log.d(TAG, "stage=$stage elapsed_ms=$elapsedMs delta_ms=$deltaMs")
    }

    companion object {
        private const val TAG = "ChatCreateOpen"
        private const val NANOS_PER_MS = 1_000_000L

        const val STAGE_CONFIRM_TAP = "confirm_tap"
        const val STAGE_MDK_CREATE_START = "mdk_create_start"
        const val STAGE_MDK_CREATE_RETURN = "mdk_create_return"
        const val STAGE_AUTHORITATIVE_READ_START = "authoritative_read_start"
        const val STAGE_AUTHORITATIVE_READ_RETURN = "authoritative_read_return"
        const val STAGE_CONVERSATION_FRAME_READY = "conversation_frame_ready"
        const val STAGE_COMPOSER_READY = "composer_ready"
        const val STAGE_CREATE_FAILED = "create_failed"
        const val STAGE_AUTHORITATIVE_READ_FAILED = "authoritative_read_failed"
        const val STAGE_CANCELLED = "create_cancelled"

        fun begin(): ChatCreateOpenTiming = ChatCreateOpenTiming()
    }
}
