package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AttachmentTransferStateFfi

/**
 * True for MDK's removal states: the attachment was deleted, cleaned by a retention setting or is gone for good, so
 * its original and every derivative are removed. COMPLETED_UNRETAINED is not one: MDK only declined to keep the body
 * (over its budget, or quarantined), so this device's own copy of a file it sent stays. Exhaustive on purpose: a
 * state added by a future MarmotKit fails the build here instead of granting reads.
 */
internal fun AttachmentTransferStateFfi.removesDisplayThumbnail(): Boolean =
    when (this) {
        AttachmentTransferStateFfi.UNAVAILABLE,
        AttachmentTransferStateFfi.REMOVED,
        AttachmentTransferStateFfi.CLEANED_BY_SETTING,
        AttachmentTransferStateFfi.PREVIOUSLY_ACQUIRED_UNAVAILABLE,
        -> true
        AttachmentTransferStateFfi.COMPLETED_UNRETAINED,
        AttachmentTransferStateFfi.NOT_REQUESTED,
        AttachmentTransferStateFfi.QUEUED,
        AttachmentTransferStateFfi.DOWNLOADING,
        AttachmentTransferStateFfi.VERIFYING_CIPHERTEXT,
        AttachmentTransferStateFfi.DECRYPTING,
        AttachmentTransferStateFfi.VERIFYING_PLAINTEXT,
        AttachmentTransferStateFfi.READY,
        AttachmentTransferStateFfi.RETRY_SCHEDULED,
        AttachmentTransferStateFfi.FAILED,
        AttachmentTransferStateFfi.CANCELLED,
        AttachmentTransferStateFfi.PAUSED,
        AttachmentTransferStateFfi.POLICY_BLOCKED,
        AttachmentTransferStateFfi.RETRY_EXHAUSTED,
        AttachmentTransferStateFfi.STORAGE_BLOCKED,
        -> false
    }

/**
 * True when a derivative may be read, written or kept: MDK still retains the body (READY), or Android still holds
 * its own original ([hostOriginal], a file this device uploaded), asked only when the state is not READY. Any other
 * state has no body behind the derivative, for example CANCELLED or FAILED after media that a retention setting
 * cleaned was requested again.
 */
internal fun AttachmentTransferStateFfi.keepsDisplayThumbnail(hostOriginal: () -> Boolean): Boolean =
    this == AttachmentTransferStateFfi.READY || (!removesDisplayThumbnail() && hostOriginal())

/** Whether Android's own original cache holds attachment [key]. It may hydrate the cache index, so run it off main. */
internal fun WhiteNoiseAppState.hostHoldsOriginal(key: String): Boolean = diskMediaCache.containsAfterHydration(key)
