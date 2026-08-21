package dev.ipf.whitenoise.android.ui.conversation.media

/**
 * Local bytes may always be materialized. A cache-missing own attachment may
 * bypass the per-media matrix, but not an explicit account backlog pause.
 */
internal fun shouldMaterializeAttachmentAutomatically(
    mine: Boolean,
    mediaAutoDownloadAllowed: Boolean,
    automaticDownloadsPaused: Boolean,
    hasCachedAttachment: Boolean = false,
    hasMaterializedFile: Boolean = false,
    hasRetainedPlaintext: Boolean = false,
): Boolean =
    hasCachedAttachment ||
        hasMaterializedFile ||
        hasRetainedPlaintext ||
        mediaAutoDownloadAllowed ||
        (mine && !automaticDownloadsPaused)
