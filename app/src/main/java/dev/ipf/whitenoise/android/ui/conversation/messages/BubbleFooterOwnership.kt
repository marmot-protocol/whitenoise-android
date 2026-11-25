package dev.ipf.whitenoise.android.ui.conversation.messages

import dev.ipf.whitenoise.android.core.IndexedAttachment

internal fun ttsBodyIsCollapsed(
    collapseEnabled: Boolean,
    measuredBodyHeightPx: Int?,
    maxBodyHeightPx: Int,
): Boolean = collapseEnabled && (measuredBodyHeightPx == null || measuredBodyHeightPx > maxBodyHeightPx)

/**
 * Selects the final file card as the message footer owner when no visual tile already owns it and
 * the message has no caption; a caption is the message's last line and carries the footer itself.
 * Confirmed and optimistic file renderers apply the returned ownership only to their last card.
 */
internal fun fileCardOwnsFooter(
    deleted: Boolean,
    fileCount: Int,
    visualOwnsFooter: Boolean,
    hasCaption: Boolean = false,
): Boolean = !deleted && fileCount > 0 && !visualOwnsFooter && !hasCaption

/** Gives the final received recording the available footer when all attachments are accepted. */
internal fun BubbleMedia.transcriptFooterVoice(
    showReceivedVoice: Boolean,
    footerAvailable: Boolean,
): IndexedAttachment? = audio.lastOrNull()?.takeIf { showReceivedVoice && footerAvailable && rejected.isEmpty() }

/**
 * Selects an uncaptioned visual-only group for overlay metadata.
 * A file attachment always takes precedence so mixed groups keep one stable trailing footer.
 */
internal fun visualMediaOwnsFooter(
    deleted: Boolean,
    hasInvalidationWarning: Boolean,
    visualCount: Int,
    fileCount: Int,
    hasCaption: Boolean,
): Boolean = !deleted && !hasInvalidationWarning && visualCount > 0 && fileCount == 0 && !hasCaption
