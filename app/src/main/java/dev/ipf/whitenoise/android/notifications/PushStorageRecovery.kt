package dev.ipf.whitenoise.android.notifications

/** Presentation of retained push state; none of these values authorizes deleting data or clearing a fence. */
enum class PushStorageRecovery {
    Checking,
    Ready,
    StorageUnavailable,
    LegacyImportPending,
    CleanupReconciliationRequired,
}
