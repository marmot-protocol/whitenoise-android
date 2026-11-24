package dev.ipf.whitenoise.android.ui.conversation.media

import dev.ipf.marmotkit.MediaAttachmentReferenceFfi

/**
 * Upgrade receive-side compatibility references before a transfer. Timeline
 * fallback records can temporarily carry sourceEpoch=0; passing that value to
 * MarmotKit can never succeed and previously left visual media spinning.
 */
internal suspend fun authoritativeVisualMediaReference(
    reference: MediaAttachmentReferenceFfi,
    mine: Boolean,
    resolve: suspend () -> MediaAttachmentReferenceFfi,
): MediaAttachmentReferenceFfi =
    if (mine || reference.sourceEpoch != 0uL) {
        reference
    } else {
        resolve()
    }
