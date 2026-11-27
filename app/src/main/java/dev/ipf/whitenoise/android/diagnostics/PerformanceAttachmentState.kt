package dev.ipf.whitenoise.android.diagnostics

/** Closed native attachment state; no attachment identity is serialized. */
internal enum class PerformanceAttachmentState(
    val wireName: String,
) {
    NOT_REQUESTED("not_requested"),
    QUEUED("queued"),
    DOWNLOADING("downloading"),
    VERIFYING_CIPHERTEXT("verifying_ciphertext"),
    DECRYPTING("decrypting"),
    VERIFYING_PLAINTEXT("verifying_plaintext"),
    RETRY_SCHEDULED("retry_scheduled"),
    PAUSED("paused"),
    READY("ready"),
    UNAVAILABLE("unavailable"),
    FAILED("failed"),
    CANCELLED("cancelled"),
    REMOVED("removed"),
    POLICY_BLOCKED("policy_blocked"),
    PREVIOUSLY_ACQUIRED_UNAVAILABLE("previously_acquired_unavailable"),
    COMPLETED_UNRETAINED("completed_unretained"),
    RETRY_EXHAUSTED("retry_exhausted"),
    STORAGE_BLOCKED("storage_blocked"),
    CLEANED_BY_SETTING("cleaned_by_setting"),
}
