package dev.ipf.whitenoise.android.state

import android.os.Build

/** How one durable transfer may run once Android would otherwise stop ordinary background work. */
internal enum class AttachmentExecutionClass {
    /** An Android 14+ user-initiated data-transfer job, scheduled only for a visible explicit request. */
    UserInitiatedJob,

    /** A WorkManager request that promotes itself to a typed foreground service when it runs. */
    ForegroundWork,

    /** Ordinary background work that Android may stop whenever the app leaves the foreground. */
    OrdinaryWork,
}

/** The one decision that elevates reader-requested transfers; automatic work is never elevated. */
internal fun attachmentExecutionClass(
    priority: AttachmentDownloadPriority,
    userVisible: Boolean,
    sdkInt: Int,
): AttachmentExecutionClass =
    when {
        priority != AttachmentDownloadPriority.Interactive -> AttachmentExecutionClass.OrdinaryWork
        userVisible && sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> AttachmentExecutionClass.UserInitiatedJob
        else -> AttachmentExecutionClass.ForegroundWork
    }
