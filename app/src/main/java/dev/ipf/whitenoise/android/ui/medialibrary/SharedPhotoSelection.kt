package dev.ipf.whitenoise.android.ui.medialibrary

import android.content.Context
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ipf.whitenoise.android.media.AttachmentPlaintext
import dev.ipf.whitenoise.android.ui.conversation.media.OutboundShareAttachment
import dev.ipf.whitenoise.android.ui.conversation.media.shareAttachmentsExternally
import kotlinx.coroutines.CancellationException

/** A gallery slot: the message and authored attachment index a tile was selected from. */
private fun SharedMediaTile.slot() = messageIdHex to attachmentIndex

/**
 * Photos selected in one gallery for a single share, in the order they were picked. Selecting the first photo starts
 * selection mode and deselecting the last one ends it. Videos cannot be selected, and nothing is pruned when more
 * tiles load: a share checks every selected slot against the gallery when it starts and again before it launches.
 */
@Stable
internal class SharedPhotoSelection {
    private var selected by mutableStateOf<Map<Pair<String, Int>, SharedMediaTile>>(emptyMap())

    /** The selected photos, as they were when picked. */
    val photos: List<SharedMediaTile> get() = selected.values.toList()

    /** Number of selected photos. */
    val count: Int get() = selected.size

    /** Whether selection mode is on. */
    val active: Boolean get() = selected.isNotEmpty()

    /** Whether [tile]'s slot is selected. */
    fun isSelected(tile: SharedMediaTile): Boolean = tile.slot() in selected

    /** Selects or deselects a photo's slot; videos are ignored. */
    fun toggle(tile: SharedMediaTile) {
        if (tile.isVideo) return
        val slot = tile.slot()
        selected = if (slot in selected) selected - slot else selected + (slot to tile)
    }

    /** Deselects [photos], leaving the rest of the selection. */
    fun deselect(photos: Collection<SharedMediaTile>) {
        selected = selected - photos.map { it.slot() }.toSet()
    }

    /** Ends selection mode. */
    fun clear() {
        selected = emptyMap()
    }
}

/**
 * Selected photos that no longer resolve, by slot, to the same photo in the gallery's [current] tiles: the slot is
 * gone or hidden, became a video, or now carries a different reference.
 */
internal fun unavailableSelectedPhotos(
    selected: List<SharedMediaTile>,
    current: List<SharedMediaTile>,
): List<SharedMediaTile> {
    val bySlot = current.associateBy { it.slot() }
    return selected.filter { photo ->
        val now = bySlot[photo.slot()]
        now == null || now.isVideo || now.reference != photo.reference
    }
}

/** Some selected photos no longer resolve in the gallery, so nothing was shared. */
internal class SharedPhotosUnavailableException(
    val photos: List<SharedMediaTile>,
) : IllegalStateException("${photos.size} selected photo(s) are no longer available")

/**
 * Shares the whole verified files of [photos] through one Android share sheet, with no caption and nothing else from
 * their messages. [currentPhotos] returns the gallery's settled tiles, or null once the gallery no longer owns the
 * share; it is checked before anything is staged and again before the share sheet opens. A selection that stopped
 * resolving fails with [SharedPhotosUnavailableException], a set over the staging budget with
 * `OutboundShareTooLargeException`, and a lost owner cancels. Each interruption deletes every staged file and opens
 * no share sheet.
 */
@Suppress("LongParameterList")
internal suspend fun shareSelectedPhotos(
    context: Context,
    accountRef: String,
    photos: List<SharedMediaTile>,
    chooserTitle: String,
    currentPhotos: suspend () -> List<SharedMediaTile>?,
    source: suspend (SharedMediaTile) -> AttachmentPlaintext,
) {
    require(photos.isNotEmpty()) { "No photos selected" }

    /** Cancels when the gallery lost the share and fails when a selected slot stopped resolving. */
    suspend fun requireAvailable() {
        val current = currentPhotos() ?: throw CancellationException("shared photo gallery owner changed")
        val unavailable = unavailableSelectedPhotos(photos, current)
        if (unavailable.isNotEmpty()) throw SharedPhotosUnavailableException(unavailable)
    }

    requireAvailable()
    shareAttachmentsExternally(
        context = context,
        accountRef = accountRef,
        attachments = photos.map { photo -> OutboundShareAttachment(photo.reference) { source(photo) } },
        chooserTitle = chooserTitle,
        beforeLaunch = { requireAvailable() },
    )
}
