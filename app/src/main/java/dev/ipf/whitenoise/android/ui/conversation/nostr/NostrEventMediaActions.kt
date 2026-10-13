package dev.ipf.whitenoise.android.ui.conversation.nostr

import java.net.URI

/** Called only after the renderer's ordinary link confirmation, when applicable. */
internal fun activateNostrMediaLink(
    raw: String,
    media: NostrEventMediaCandidates,
    onViewImage: ((String) -> Unit)?,
    onPlayVideo: ((String) -> Unit)?,
): Boolean {
    val safe = safeNostrMediaUrl(raw) ?: return false
    val destination = URI(safe)
    val knownImage = media.imageUrls.firstOrNull { runCatching { URI(it) == destination }.getOrDefault(false) }
    val knownVideo = media.videoUrls.firstOrNull { runCatching { URI(it) == destination }.getOrDefault(false) }
    val kind =
        when {
            knownImage != null -> NostrLinkedMediaKind.Image
            knownVideo != null -> NostrLinkedMediaKind.Video
            else -> nostrLinkedMediaKind(safe)
        }
    return when {
        kind == NostrLinkedMediaKind.Image && onViewImage != null -> {
            onViewImage(knownImage ?: safe)
            true
        }
        kind == NostrLinkedMediaKind.Video && onPlayVideo != null -> {
            onPlayVideo(knownVideo ?: safe)
            true
        }
        else -> false
    }
}
