package dev.ipf.whitenoise.android.ui.medialibrary

import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.whitenoise.android.media.MediaInventory

// A renderable image/video tile resolved from the conversation timeline. Unlike
// MediaInventory's MediaEntry (which is transport-free and carries only the
// reference), this also carries the protocol-level attachmentIndex and the
// receive-side `mine` flag the existing decrypt/download pipeline needs to
// materialize the thumbnail through the same path the bubbles use.
internal data class SharedMediaTile(
    val messageIdHex: String,
    val attachmentIndex: Int,
    val reference: MediaAttachmentReferenceFfi,
    val mine: Boolean,
    val recordedAt: ULong,
    val sender: String,
    val isVideo: Boolean,
)

// An attachment row for the Voice/Files vertical lists. Like SharedMediaTile it
// carries the protocol-level attachmentIndex and `mine` flag the decrypt path
// needs, plus the sender hex so the row can resolve a display name + avatar.
internal data class SharedMediaRow(
    val messageIdHex: String,
    val attachmentIndex: Int,
    val reference: MediaAttachmentReferenceFfi,
    val mine: Boolean,
    val recordedAt: ULong,
    val sender: String,
)

internal data class MediaMonthSection<T>(
    val monthKey: Int,
    val items: List<T>,
    val sectionKey: String = monthKey.toString(),
)

internal data class SharedMediaTiles(
    val visuals: List<SharedMediaTile>,
    val images: List<SharedMediaTile>,
    val videos: List<SharedMediaTile>,
    val voice: List<SharedMediaRow>,
    val files: List<SharedMediaRow>,
    val urls: List<MediaInventory.UrlEntry>,
    val imageSections: List<MediaMonthSection<SharedMediaTile>>,
    val videoSections: List<MediaMonthSection<SharedMediaTile>>,
    val voiceSections: List<MediaMonthSection<SharedMediaRow>>,
    val fileSections: List<MediaMonthSection<SharedMediaRow>>,
    val urlSections: List<MediaMonthSection<IndexedValue<MediaInventory.UrlEntry>>>,
    // True when the conversation carries media beyond the rendered image/video
    // grids — voice, files, urls, or bare image-URL links that aren't gridded.
    // Carried so the section can decide between the strip, the single
    // "View shared media" row, and hiding entirely without re-deriving.
    val hasOther: Boolean,
    val visualSections: List<MediaMonthSection<SharedMediaTile>> = emptyList(),
    val isLoading: Boolean = false,
) {
    val isEmpty: Boolean
        get() =
            images.isEmpty() &&
                videos.isEmpty() &&
                voice.isEmpty() &&
                files.isEmpty() &&
                urls.isEmpty() &&
                !hasOther
}
