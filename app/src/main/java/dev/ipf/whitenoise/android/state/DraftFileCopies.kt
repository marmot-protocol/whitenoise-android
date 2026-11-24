package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.marmotkit.MessageDraftRevisionFfi
import dev.ipf.whitenoise.android.ui.conversation.DRAFT_FILE_READ_CHUNK_BYTES
import dev.ipf.whitenoise.android.ui.conversation.media.StagedUploadSource
import dev.ipf.whitenoise.android.ui.conversation.media.prepareStagingDirectory
import dev.ipf.whitenoise.android.ui.conversation.replaceWithDraftFileBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** How many times one chunk follows a draft that saves keep moving before the copy gives up on the file. */
private const val MAX_DRAFT_COPY_FOLLOWS = 8

/**
 * Private copies of a failed send's stored draft files, by draft id. Each is registered before anything is written to
 * it, so a copy cancelled at any point, even as its finished file returns from the IO pool, still has this one owner.
 */
internal class DraftFileCopies {
    private val copies = linkedMapOf<String, StagedUploadSource>()

    /** Records [source] as the copy of draft file [id], before the copy writes it. */
    @Synchronized
    fun register(
        id: String,
        source: StagedUploadSource,
    ) {
        copies[id] = source
    }

    /** Hands every copy registered so far to the caller and keeps none. */
    @Synchronized
    fun take(): Map<String, StagedUploadSource> = LinkedHashMap(copies).also { copies.clear() }
}

/**
 * Gives this send private copies of its stored draft files, made by [copy] into the holder it is passed, so a send
 * whose upload failed owns its files whatever the draft does next, a new attachment or an unrelated send that clears it
 * included. The send adopts them only when [copy] reports every one copied. Any it did not adopt, after a refusal, a
 * failure or a cancellation, the return from [copy] included, goes to [closeStaged]; the send then keeps uploading from
 * the draft.
 */
internal suspend fun RetainedMediaUpload.keepDraftCopies(
    closeStaged: (StagedUploadSource) -> Unit,
    copy: suspend (DraftFileCopies) -> Boolean,
) {
    val copies = DraftFileCopies()
    try {
        // A copy that cannot even start must not replace the upload failure the bubble reports.
        if (runCatchingCancellable { copy(copies) }.getOrDefault(false)) adoptDraftCopies(copies.take(), closeStaged)
    } finally {
        copies.take().values.forEach(closeStaged)
    }
}

/**
 * Copies each stored draft file in [attachments] out of the selected draft into a private file under [directory],
 * registering each in [into] before writing it. Reports whether every file was copied: false when any file is no
 * longer in the draft or cannot be copied, and the files already registered are the caller's to delete.
 */
@Suppress("ReturnCount") // Each refusal ends the copy early, and the caller deletes what was made.
internal suspend fun MarmotInterface.copyDraftFiles(
    account: String,
    group: String,
    attachments: List<PendingAttachment>,
    directory: File,
    into: DraftFileCopies,
): Boolean {
    val ready = withContext(Dispatchers.IO) { runCatching { prepareStagingDirectory(directory) }.getOrDefault(false) }
    if (!ready) return false
    for (stored in attachments.mapNotNull { it.draftFile }) {
        val target = File(directory, "draft-copy-${UUID.randomUUID()}")
        val copy = DraftFileCopy(this, account, group, stored)
        into.register(stored.attachmentId, StagedUploadSource(target, stored.byteCount))
        if (!withContext(Dispatchers.IO) { copy.into(target) }) return false
    }
    return true
}

/** One stored draft file read out under whichever selected revision still holds it. */
private class DraftFileCopy(
    private val marmot: MarmotInterface,
    private val account: String,
    private val group: String,
    private val stored: DraftStoredFile,
) {
    private var revision: MessageDraftRevisionFfi? = null

    /** Writes the whole file to [target] through a partial file; reports whether [target] now holds all of it. */
    suspend fun into(target: File): Boolean {
        revision = heldRevision() ?: return false
        return replaceWithDraftFileBody(target, stored.byteCount, ::readChunk)
    }

    /**
     * Reads one chunk. A save that moved the draft meanwhile, such as typing, is followed while the draft still holds
     * the same file; null when it no longer does, or keeps moving.
     */
    @Suppress("ReturnCount", "SwallowedException") // A moved draft is followed, never reported as a failed read.
    private suspend fun readChunk(offset: Long): ByteArray? {
        repeat(MAX_DRAFT_COPY_FOLLOWS) {
            val current = revision ?: return null
            try {
                return marmot.readMessageDraftAttachment(
                    account,
                    current,
                    stored.attachmentId,
                    offset.toULong(),
                    DRAFT_FILE_READ_CHUNK_BYTES.toUInt(),
                )
            } catch (moved: MarmotKitException.MessageDraftRevisionConflict) {
                revision = heldRevision()
            }
        }
        return null
    }

    /** The selected draft's revision while it holds [stored] as the same stored file, otherwise null. */
    private fun heldRevision(): MessageDraftRevisionFfi? {
        val selected = marmot.selectedDraftOrNull(account, group) ?: return null
        val held =
            selected.draft
                ?.mediaAttachments
                .orEmpty()
                .any { isStoredFile(it, stored) }
        return selected.revision.takeIf { held }
    }
}
