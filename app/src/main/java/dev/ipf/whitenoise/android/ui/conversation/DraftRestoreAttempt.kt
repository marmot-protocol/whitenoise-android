package dev.ipf.whitenoise.android.ui.conversation

import android.content.Context
import android.net.Uri
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.marmotkit.MessageDraftAttachmentFfi
import dev.ipf.marmotkit.MessageDraftRevisionFfi
import dev.ipf.whitenoise.android.media.editor.MessageDraftRepository
import dev.ipf.whitenoise.android.media.editor.RevisionedMessageDraft
import dev.ipf.whitenoise.android.media.editor.hasInlineBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** How many times one stored-body read follows a draft that saves keep moving before the restore gives up on it. */
private const val MAX_DRAFT_REVISION_FOLLOWS = 8

/** Where restored draft bodies are written for the shelf, and the URI the shelf shows each one under. */
internal interface DraftPresentation {
    /** The presentation file for [attachment], or null when none can be named. */
    fun fileFor(attachment: MessageDraftAttachmentFfi): File?

    /** The shelf URI of [file], shown under [attachment]'s file name, or null when none can be made. */
    fun uriFor(
        file: File,
        attachment: MessageDraftAttachmentFfi,
    ): Uri?

    /** Whether the shelf can still open [uri]; a picker grant lost with the process is the usual reason it cannot. */
    fun isReadable(uri: Uri): Boolean
}

/** The composer's presentation files in the app cache, shared through its FileProvider. */
internal class ContextDraftPresentation(
    private val context: Context,
) : DraftPresentation {
    /** Names the cached presentation file of [attachment]. */
    override fun fileFor(attachment: MessageDraftAttachmentFfi): File? = draftPresentationFile(context, attachment)

    /** Shares [file] through the app's FileProvider. */
    override fun uriFor(
        file: File,
        attachment: MessageDraftAttachmentFfi,
    ): Uri? = draftPresentationUri(context, file, attachment)

    /** Opens [uri] for reading once through the content resolver, so a revoked grant or a missing file reads false. */
    override fun isReadable(uri: Uri): Boolean =
        runCatching { context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } == true }
            .getOrDefault(false)
}

/**
 * The shelf files one restore made: [added] for draft attachments the shelf did not show, and [relinked], by attachment
 * id, for shelf entries whose URI could no longer be read.
 */
internal data class MaterializedRestoration(
    val added: List<Pair<MessageDraftAttachmentFfi, Uri>>,
    val relinked: Map<String, Uri>,
)

/**
 * The stored files a restore shows from the native draft on entries the shelf already holds, each with the URI its
 * entry holds: photo slots and documents matched to the draft rather than prepared by this composer, such as a shelf
 * restored from saved state after the process died. [storedIds] names the draft's stored files.
 */
internal fun matchedStoredEntries(
    inputs: DraftRestorationInputs,
    plan: DraftRestorationPlan,
    storedIds: Set<String>,
): List<Pair<MessageDraftAttachmentFfi, Uri>> {
    val slotUris = plan.retainedSlots.associate { it.id to it.uri }
    val photos =
        nativePhotosNeedingRestoration(
            plan.reconciliation.mediaBySlotId,
            inputs.backedPhotos.keys,
            inputs.preparedPhotos,
        ).mapNotNull { (slotId, attachment) -> slotUris[slotId]?.let { attachment to it } }
    val documents =
        nativeDocumentsNeedingRestoration(plan.reconciliation.documentsByUriString, inputs.preparedDocuments)
            .map { (uri, attachment) -> attachment to uri }
    return (photos + documents).filter { (attachment, _) -> attachment.id in storedIds }
}

/**
 * One restoration's plaintext presentation files. Each file the attempt creates belongs to it until the composer
 * adopts the restored shelf with [adopt]; [releaseUnadopted] deletes the rest, so a restore that is cancelled part-way
 * or never published leaves no plaintext copy behind. A file that already existed, which an earlier restore's shelf
 * may still show, is never deleted here.
 */
internal class DraftRestoreAttempt(
    private val presentation: DraftPresentation,
    private val drafts: MessageDraftRepository,
    private val accountRef: String,
    private val read: RevisionedMessageDraft,
) {
    private val created = mutableListOf<File>()
    private var adopted = false

    // The revision stored bodies are read under: the restore's read, then any newer one a text save moved it to.
    private var revision: MessageDraftRevisionFfi? = read.revision

    /**
     * Whether a stored body the draft may still hold went unread because the draft could not be read again after a
     * revision conflict, or kept moving. Unlike a confirmed removal, such a restore must not be published as done.
     */
    @Volatile
    var interrupted = false
        private set

    /**
     * Gives one restored attachment a shelf file: inline bytes are written out, and a stored body is streamed from
     * MarmotKit, reusing a file already there with the stored size. Returns null, keeping no new file, when the body
     * cannot be written or the shelf cannot be given a URI for it.
     */
    suspend fun materialize(attachment: MessageDraftAttachmentFfi): Uri? {
        val file = presentation.fileFor(attachment) ?: return null
        val createdHere = !file.exists()
        // Owned before any byte is written, so a cancelled write is still cleaned up.
        if (createdHere) synchronized(this) { created += file }
        val ready = if (attachment.hasInlineBody()) writeInline(file, attachment) else writeStored(file, attachment)
        val uri = if (ready) presentation.uriFor(file, attachment) else null
        if (uri == null && createdHere) {
            file.delete()
            synchronized(this) { created -= file }
        }
        return uri
    }

    /**
     * Gives one restore its shelf files: each of [unmatched], which the shelf does not show yet, and each of [matched],
     * shelf entries with the URI they hold, whose URI can no longer be read, such as a picker grant lost with the
     * process. Those are relinked to an app-owned copy, so preview and a send that reads the shelf never open the
     * stale URI.
     */
    suspend fun materializeAll(
        unmatched: List<MessageDraftAttachmentFfi>,
        matched: List<Pair<MessageDraftAttachmentFfi, Uri>>,
    ): MaterializedRestoration {
        val added = unmatched.mapNotNull { attachment -> materialize(attachment)?.let { attachment to it } }
        val relinked =
            matched
                .filterNot { (_, uri) -> presentation.isReadable(uri) }
                .mapNotNull { (attachment, _) -> materialize(attachment)?.let { attachment.id to it } }
                .toMap()
        return MaterializedRestoration(added, relinked)
    }

    /** Hands every file this attempt created to the composer, which now shows them. */
    fun adopt() {
        synchronized(this) { adopted = true }
    }

    /** Deletes every file this attempt created unless the composer adopted them; safe to repeat. */
    suspend fun releaseUnadopted() {
        val unadopted =
            synchronized(this) {
                if (adopted) emptyList() else created.toList().also { created.clear() }
            }
        if (unadopted.isEmpty()) return
        withContext(NonCancellable + Dispatchers.IO) { unadopted.forEach { runCatching { it.delete() } } }
    }

    /** Writes inline bytes through a partial file, so a failed write never leaves a truncated copy. */
    private suspend fun writeInline(
        file: File,
        attachment: MessageDraftAttachmentFfi,
    ): Boolean =
        replaceWithDraftFileBody(file, attachment.plaintext.size.toLong()) { offset ->
            if (offset == 0L) attachment.plaintext else ByteArray(0)
        }

    /** Streams a stored body, or reuses a presentation file that already holds all of it. */
    private suspend fun writeStored(
        file: File,
        attachment: MessageDraftAttachmentFfi,
    ): Boolean {
        val size = read.fileBacked[attachment.id]?.plaintextSize?.toLong()
        return when {
            read.revision == null || size == null -> false
            file.isFile && file.length() == size -> true
            else -> replaceWithDraftFileBody(file, size) { offset -> readStoredChunk(attachment.id, size, offset) }
        }
    }

    /**
     * Reads one chunk of a stored body. When a save moved the draft meanwhile, such as typing while a large file
     * streams back, the chunk is read again under the draft's new revision as long as that draft still holds the same
     * stored file. Returns null when the draft no longer holds it, keeping the revision for the attempt's other files.
     * Also returns null, marking the attempt [interrupted], when the draft cannot be read again or keeps moving after
     * [MAX_DRAFT_REVISION_FOLLOWS] reads.
     */
    @Suppress("ReturnCount", "SwallowedException") // A moved draft is followed, never reported as a failed read.
    private suspend fun readStoredChunk(
        attachmentId: String,
        size: Long,
        offset: Long,
    ): ByteArray? {
        var follows = 0
        while (true) {
            val current = revision ?: return null
            try {
                return drafts.files.readChunk(accountRef, current, attachmentId, offset, DRAFT_FILE_READ_CHUNK_BYTES)
            } catch (moved: MarmotKitException.MessageDraftRevisionConflict) {
                if (follows++ == MAX_DRAFT_REVISION_FOLLOWS) return interrupt()
                val fresh = freshRead() ?: return interrupt()
                // A draft that no longer holds this file says nothing about the attempt's other stored files.
                if (fresh.fileBacked[attachmentId]?.plaintextSize?.toLong() != size) return null
                revision = fresh.revision
            }
        }
    }

    /** The selected draft read again, or null when it cannot be read. */
    private suspend fun freshRead(): RevisionedMessageDraft? {
        val group = read.draft?.groupIdHex ?: return null
        return drafts.files.revisioned(accountRef, group).getOrNull()
    }

    /** Marks this attempt [interrupted] and gives up on the chunk being read. */
    private fun interrupt(): ByteArray? {
        interrupted = true
        return null
    }
}

/**
 * Streams a body into a sibling partial file and moves it over [file] only once all [size] bytes arrived, so a shelf
 * never shows a truncated copy. The partial file is deleted whatever happens, cancellation included. Reports whether
 * [file] now holds the body.
 */
@Suppress("SwallowedException") // A disk or native read failure only means the shelf skips this attachment.
internal suspend fun replaceWithDraftFileBody(
    file: File,
    size: Long,
    read: suspend (offset: Long) -> ByteArray?,
): Boolean {
    val partial = File(file.parentFile, "${file.name}.partial")
    var moved = false
    try {
        val copied =
            try {
                copyDraftFileBody(partial, read) == size
            } catch (unreadable: IOException) {
                false
            } catch (refused: MarmotKitException) {
                false
            }
        moved = copied && partial.renameTo(file)
    } finally {
        if (!moved) partial.delete()
    }
    return moved
}
