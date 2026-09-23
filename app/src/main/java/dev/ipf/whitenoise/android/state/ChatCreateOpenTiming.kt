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
        const val STAGE_IDENTIFIER_INPUT = "identifier_input"
        const val STAGE_IDENTIFIER_RESOLVED = "identifier_resolved"
        const val STAGE_IDENTIFIER_INVALID = "identifier_invalid"
        const val STAGE_RECIPIENT_REPLACED = "recipient_replaced"
        const val STAGE_RECIPIENT_ROW_READY = "recipient_row_ready"
        const val STAGE_PROFILE_REFRESH_START = "profile_refresh_start"
        const val STAGE_PROFILE_REFRESH_RETURN = "profile_refresh_return"
        const val STAGE_PROFILE_DISPLAYED = "profile_displayed"
        const val STAGE_KEY_PACKAGE_PREWARM_START = "key_package_prewarm_start"
        const val STAGE_KEY_PACKAGE_PREWARM_RETURN = "key_package_prewarm_return"
        const val STAGE_KEY_PACKAGE_PREWARM_FAILED = "key_package_prewarm_failed"
        const val STAGE_EXISTING_DM_LOOKUP_START = "existing_dm_lookup_start"
        const val STAGE_EXISTING_DM_LOOKUP_RETURN = "existing_dm_lookup_return"
        const val STAGE_EXISTING_DM_LOOKUP_FAILED = "existing_dm_lookup_failed"
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

/** Owns one end-to-end recipient/create/open trace without adding more state to AppState. */
internal class ChatCreateOpenTimingTracker {
    private var timing: ChatCreateOpenTiming? = null

    fun begin(
        stage: String,
        restart: Boolean,
    ) {
        if (restart || timing == null) timing = ChatCreateOpenTiming.begin()
        timing?.mark(stage)
    }

    fun mark(stage: String) = timing?.mark(stage)

    fun isActive(): Boolean = timing != null

    fun finish(stage: String) {
        mark(stage)
        timing = null
    }
}
