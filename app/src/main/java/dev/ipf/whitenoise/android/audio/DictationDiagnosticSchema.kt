package dev.ipf.whitenoise.android.audio

private const val MAX_DIAGNOSTIC_EVENT_CHARS = 2048

/** A closed export schema. Never persist a raw diagnostic string, exception or provider identity. */
internal object DictationDiagnosticSchema {
    private val events =
        setOf(
            "account_unavailable",
            "app_record_audio_access",
            "app_record_audio_permission",
            "app_visibility",
            "callback_beginning_of_speech",
            "callback_end_of_speech",
            "callback_error",
            "callback_ready",
            "callback_rejected",
            "callback_result",
            "caller_audio_backpressure",
            "caller_audio_chunk_fed",
            "caller_audio_chunk_sealed",
            "caller_audio_closed",
            "caller_audio_feed_failed",
            "caller_audio_finish",
            "caller_audio_known",
            "caller_audio_mode",
            "caller_audio_open_failed",
            "caller_audio_pipe_failed",
            "caller_audio_probe_error",
            "caller_audio_probe_result",
            "caller_audio_probe_session",
            "caller_audio_probe_settled",
            "caller_audio_probe_setup_failed",
            "caller_audio_probe_start",
            "caller_audio_probe_start_failed",
            "caller_audio_progress",
            "caller_audio_requirement",
            "caller_audio_retry_exhausted",
            "caller_audio_retry_requested",
            "caller_audio_retry_scheduled",
            "caller_audio_retry_skipped",
            "caller_audio_silence_acknowledged",
            "caller_audio_speech_detected",
            "caller_audio_start_failed",
            "caller_audio_started",
            "caller_audio_unavailable",
            "caller_audio_write_stalled",
            "completion_action",
            "create_recognizer",
            "failure_recovery",
            "foreground_notification_closed",
            "foreground_service_destroyed",
            "foreground_service_enqueue_failed",
            "foreground_service_on_start",
            "foreground_service_promoted",
            "foreground_service_promotion_rejected",
            "foreground_service_start",
            "foreground_service_stop",
            "foreground_service_stop_requested",
            "microphone_lease",
            "microphone_lease_released",
            "microphone_preflight",
            "origin_visibility",
            "paste_write",
            "platform_cancel",
            "platform_destroy",
            "platform_error",
            "platform_event",
            "platform_language_detection",
            "platform_partial_results",
            "platform_segment_results",
            "platform_segmented_session_end",
            "platform_start_listening",
            "platform_stop_listening",
            "provider_activity_cancelled",
            "provider_activity_check_result",
            "provider_activity_check_start",
            "provider_activity_launch_claimed",
            "provider_activity_launch_failed",
            "provider_activity_result",
            "recognition_available",
            "recognition_configured",
            "recognizer_generation_start",
            "recognizer_restart_scheduled",
            "recognizer_start_exception",
            "recognizer_start_timeout",
            "request_start",
            "retry",
            "send_outcome",
            "service_ownership",
            "session_abort",
            "session_failed",
            "session_finished",
            "session_started",
            "silence_check_armed",
            "silence_check_complete",
            "silence_check_rearmed",
            "start_target",
            "state_changed",
            "target_removed",
            "target_validation",
            "task_removed",
            "watchdog_armed",
            "watchdog_cancelled",
            "watchdog_fired",
        )
    private val numbers =
        setOf(
            "session",
            "active_session",
            "uptime_ms",
            "callback_session",
            "generation",
            "chunk",
            "code",
            "elapsed_ms",
            "delay_ms",
            "bytes",
            "buffered",
            "ms",
            "first_sample",
            "last_sample",
            "timeout_ms",
            "restart",
            "sample_rate",
            "channels",
            "attempts",
            "retry",
            "silence_ms",
            "buffer_seconds",
            "version_code",
            "read",
        )
    private val booleans =
        setOf(
            "accepted",
            "durable",
            "visible",
            "foreground",
            "has_text",
            "retained_audio",
            "retained_transcript",
            "finish_requested",
            "heard_speech",
            "enabled",
            "configured",
            "available",
            "system_selected",
            "provider_records",
            "supported",
            "ready",
            "claimed",
            "speech",
            "pending",
            "caller_audio",
            "active",
            "has_controller",
            "initialized",
            "requested",
            "granted",
            "speech_seen",
            "same_provider",
            "acquired",
        )
    private val categories =
        setOf(
            "requirement",
            "access",
            "type",
            "mode",
            "failure",
            "reason",
            "outcome",
            "phase",
            "from",
            "to",
            "result",
            "action",
            "path",
            "source",
            "fallback",
            "encoding",
            "chunk_seconds",
            "state",
        )
    private val allowedCategoryValues =
        setOf(
            "Idle",
            "ProviderSelectionRequired",
            "DisclosureRequired",
            "PermissionRequired",
            "CheckingProvider",
            "Starting",
            "Listening",
            "Processing",
            "ProviderActivityRequired",
            "ProviderActivityActive",
            "Failed",
            "Cancel",
            "Paste",
            "Send",
            "none",
            "continued",
            "aborted",
            "stale",
            "completed",
            "cancelled",
            "replaced",
            "retained",
            "failed",
            "accepted",
            "retry",
            "append",
            "fallback",
            "pending_visible",
            "rejected_before_transport",
            "app_background",
            "task_removed",
            "service_destroyed",
            "account_unavailable",
            "processing",
            "caller_audio_drain",
            "session",
            "recognizer_start",
            "provider_readiness",
            "caller_audio_probe",
            "capture",
            "delivery",
            "provider_result",
            "start",
            "stop",
            "teardown",
            "silence",
            "speech",
            "no_speech",
            "target_unavailable",
            "provider_activity_active",
            "transcript_pending",
            "session_active",
            "reply_unavailable",
            "state",
            "delivery_in_progress",
            "not_failed",
            "delivery_unknown",
            "fresh_start",
            "latest_draft",
            "failure",
            "draft_conflict",
            "dispatch_unavailable",
            "write_rejected",
            "write_retries_exhausted",
            "interrupted",
            "timeout",
            "rejected",
            "buffer_full",
            "stopped",
            "stop_capture",
            "read_failed",
            "requeue",
            "IOException",
            "ErrnoException",
            "SecurityException",
            "IllegalStateException",
            "RuntimeException",
            "ConversationDictationAudioFocusDenied",
            "ForegroundServiceStartNotAllowedException",
            "ForegroundServiceDidNotStartInTimeException",
            "DeadObjectException",
            "RemoteException",
            "IllegalArgumentException",
            "other",
            "not_dispatched",
            "stale_request",
            "transcript_retained",
            "session_cancelled",
            "connection_restored",
            "removed",
            "result",
            "permission",
            "provider_disconnected",
            "no_speech_advanced",
            "retained_transcript",
            "already_finishing",
            "retain",
            "advance",
            "draft_only",
            "local",
            "Done",
            "provider_microphone",
            "pcm16",
            "10-30",
        ) + ConversationDictationFailure.entries.map { it.name } +
            ConversationDictationMode.entries.map { it.name } +
            ConversationDictationDeliveryMode.entries.map { it.name } +
            ConversationDictationTargetValidation.entries.flatMap { listOf(it.name, "target_${it.name}") } +
            ConversationDictationMicrophoneAccess.entries.map { it.name } +
            ConversationDictationCallerAudioRequirement.entries.map { it.name } +
            ConversationDictationReadinessPhase.entries.map { it.name }

    /** Unknown fields and free-form values are counted and dropped, never copied into the export. */
    fun fields(event: String): Map<String, Any>? = parse(event)?.fields

    fun parse(event: String): DictationDiagnosticEvent? {
        val tokens =
            event
                .takeIf { it.length <= MAX_DIAGNOSTIC_EVENT_CHARS }
                .orEmpty()
                .split(' ')
                .mapNotNull { token ->
                    val separator = token.indexOf('=')
                    if (separator <= 0) null else token.substring(0, separator) to token.substring(separator + 1)
                }.toMap()
        val name = tokens["event"]?.takeIf { it in events } ?: return null
        var filtered = 0L
        val fields = buildMap<String, Any> {
            put("event", name)
            tokens.filterKeys { it != "event" }.forEach { (key, value) ->
                retainField(key, value)
                if (!containsKey(key)) filtered += 1
            }
        }
        return DictationDiagnosticEvent(fields, filtered)
    }

    private fun MutableMap<String, Any>.retainField(
        key: String,
        value: String,
    ) {
        if (key == "reason" && value.startsWith("read=")) {
            value.removePrefix("read=").toIntOrNull()?.let {
                put("reason", "read_failed")
                put("read_code", it)
            }
        } else {
            retainedValue(key, value)?.let { put(key, it) }
        }
    }

    private fun retainedValue(key: String, value: String): Any? =
        when {
            key in numbers -> value.toLongOrNull()
            key == "state" -> value.toLongOrNull() ?: value.takeIf { it == "none" }
            key in setOf("type", "error") -> value.takeIf { it in allowedCategoryValues } ?: "other"
            key == "peak" -> validPeak(value)
            key in booleans -> value.toBooleanStrictOrNull()
            key in categories -> value.takeIf { it in allowedCategoryValues }
            else -> null
        }

    private fun validPeak(value: String): Double? =
        value.toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.0..1.0 }
}

internal data class DictationDiagnosticEvent(
    val fields: Map<String, Any>,
    val filteredFields: Long,
)
