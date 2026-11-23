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
import kotlinx.coroutines.Job

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

/** A gallery's photo selection and the one share it may be staging. */
@Stable
internal class SharedPhotoShare {
    val selection = SharedPhotoSelection()

    private var job by mutableStateOf<Job?>(null)

    /** Whether a share of the selection is being staged. */
    val sharing: Boolean get() = job != null

    internal var start: () -> Unit = {}

    /** Ends selection mode and cancels a share still being staged, deleting its staged files. */
    fun exit() {
        job?.cancel()
        job = null
        selection.clear()
    }

    /** Records [next] as the share being staged. */
    internal fun begin(next: Job) {
        job = next
    }

    /** Forgets [finished] unless a newer share replaced it. */
    internal fun end(finished: Job?) {
        if (job === finished) job = null
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
 * their messages. [currentPhotos] returns the gallery's current tiles, or null once the gallery no longer owns the
 * share; it is checked before anything is staged, again before the share sheet opens, and after a failed staging. A
 * selection that stopped resolving fails with [SharedPhotosUnavailableException], a set over the staging budget with
 * `OutboundShareTooLargeException`, and a lost owner cancels. Each interruption deletes every staged file and opens
 * no share sheet.
 */
@Suppress("LongParameterList", "TooGenericExceptionCaught")
internal suspend fun shareSelectedPhotos(
    context: Context,
    accountRef: String,
    photos: List<SharedMediaTile>,
    chooserTitle: String,
    currentPhotos: suspend () -> List<SharedMediaTile>?,
    source: suspend (SharedMediaTile) -> AttachmentPlaintext,
) {
    require(photos.isNotEmpty()) { "No photos selected" }

    /** The selected photos that no longer resolve, or null once the gallery no longer owns the share. */
    suspend fun unavailable(): List<SharedMediaTile>? = currentPhotos()?.let { unavailableSelectedPhotos(photos, it) }

    requireSharedPhotosAvailable(unavailable())
    try {
        shareAttachmentsExternally(
            context = context,
            accountRef = accountRef,
            attachments = photos.map { photo -> OutboundShareAttachment(photo.reference) { source(photo) } },
            chooserTitle = chooserTitle,
            beforeLaunch = { requireSharedPhotosAvailable(unavailable()) },
        )
    } catch (failure: Exception) {
        // A photo removed while it was read usually fails its read; report it as unavailable so it is deselected.
        val settled = failure is CancellationException || failure is SharedPhotosUnavailableException
        throw sharedPhotosFailure(failure, if (settled) null else unavailable())
    }
}

/** Cancels once the gallery lost the share, and fails while any selected photo is [unavailable]. */
private fun requireSharedPhotosAvailable(unavailable: List<SharedMediaTile>?) {
    if (unavailable == null) throw CancellationException("shared photo gallery owner changed")
    if (unavailable.isNotEmpty()) throw SharedPhotosUnavailableException(unavailable)
}

/** [failure], unless photos found [unavailable] after it explain it better. */
private fun sharedPhotosFailure(
    failure: Exception,
    unavailable: List<SharedMediaTile>?,
): Exception =
    if (unavailable.isNullOrEmpty()) {
        failure
    } else {
        SharedPhotosUnavailableException(unavailable).apply { addSuppressed(failure) }
    }
