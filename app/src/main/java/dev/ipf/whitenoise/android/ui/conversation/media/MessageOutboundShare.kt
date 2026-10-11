@file:Suppress(
    "MatchingDeclarationName", // The sealed source model and its materialization boundary are one contract.
    "TooGenericExceptionCaught", // Cleanup must cover every failure, including cancellation and provider errors.
)

package dev.ipf.whitenoise.android.ui.conversation.media

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.whitenoise.android.media.AttachmentPlaintext
import dev.ipf.whitenoise.android.media.AttachmentPlaintextCache
import dev.ipf.whitenoise.android.media.MediaCacheDirs
import dev.ipf.whitenoise.android.media.MediaPipeline
import dev.ipf.whitenoise.android.share.OutboundShareStream
import dev.ipf.whitenoise.android.share.launchOutboundShare
import dev.ipf.whitenoise.android.share.outboundShareChooser
import dev.ipf.whitenoise.android.share.outboundShareIntent
import dev.ipf.whitenoise.android.state.AttachmentDownloadPriority
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.PendingAttachment
import dev.ipf.whitenoise.android.state.downloadAttachmentSource
import dev.ipf.whitenoise.android.state.enqueueInteractiveAttachmentDownload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream

internal sealed interface MessageShareAttachmentSource {
    val attachmentIndex: Int
    val fileName: String
    val mediaType: String

    data class Confirmed(
        override val attachmentIndex: Int,
        val reference: MediaAttachmentReferenceFfi,
    ) : MessageShareAttachmentSource {
        override val fileName: String = reference.fileName
        override val mediaType: String = reference.mediaType
    }

    data class Retained(
        override val attachmentIndex: Int,
        val attachment: PendingAttachment,
    ) : MessageShareAttachmentSource {
        override val fileName: String = attachment.fileName
        override val mediaType: String = attachment.mediaType
    }
}

/** Confirmed protocol identities win; retained optimistic plaintext is used only until projection arrives. */
internal fun messageShareAttachmentSources(
    references: List<MediaAttachmentReferenceFfi>,
    retained: List<PendingAttachment>,
): List<MessageShareAttachmentSource> =
    if (references.isNotEmpty()) {
        references.mapIndexed { index, reference -> MessageShareAttachmentSource.Confirmed(index, reference) }
    } else {
        retained.mapIndexed { index, attachment -> MessageShareAttachmentSource.Retained(index, attachment) }
    }

/**
 * Whether Share can stage this message: a complete attachment set and some content, where a pending
 * attachment counts only while its bytes are in memory (a file-backed one becomes shareable once confirmed).
 */
internal fun messageHasShareablePayload(
    text: String?,
    references: List<MediaAttachmentReferenceFfi>,
    retained: List<PendingAttachment>,
    protocolAttachmentCount: Int = references.size,
): Boolean =
    messageShareAttachmentSetIsComplete(protocolAttachmentCount, references.size) &&
        (references.isNotEmpty() || retained.all { it.inMemoryBytes != null }) &&
        (!text.isNullOrBlank() || references.isNotEmpty() || retained.isNotEmpty())

/** Whether every protocol attachment of the message has a projected reference, or it has none at all. */
internal fun messageShareAttachmentSetIsComplete(
    protocolAttachmentCount: Int,
    projectedAttachmentCount: Int,
): Boolean = protocolAttachmentCount == 0 || protocolAttachmentCount == projectedAttachmentCount

/** Stages a message's text and attachments for Android's share sheet, re-reading retained state at tap time. */
internal suspend fun shareMessageExternally(
    context: Context,
    controller: ConversationController,
    messageIdHex: String,
    references: List<MediaAttachmentReferenceFfi>,
    protocolAttachmentCount: Int,
    mine: Boolean,
    text: String?,
    chooserTitle: String,
) {
    check(messageShareAttachmentSetIsComplete(protocolAttachmentCount, references.size)) {
        "Message attachment projection is incomplete"
    }
    // Re-read retained state at activation time. A stale optimistic row must
    // fail closed instead of sharing bytes captured before its ownership ended.
    val sources = messageShareAttachmentSources(references, controller.pendingAttachmentsList(messageIdHex))
    check(!text.isNullOrBlank() || sources.isNotEmpty()) { "Message no longer has shareable content" }
    // Resolve handler availability before decrypting or retaining any new
    // plaintext. Intent resolution depends on action + MIME, not real streams.
    val shareProbe =
        outboundShareIntent(
            text,
            sources.map { source ->
                OutboundShareStream(
                    uri = Uri.parse("content://${context.packageName}.fileprovider/outbound-share-probe"),
                    mediaType = source.mediaType,
                )
            },
        )
    outboundShareChooser(context, shareProbe, chooserTitle)
    val staged =
        stageMessageShareStreams(context, sources) { source ->
            when (source) {
                is MessageShareAttachmentSource.Confirmed ->
                    // An explicit Share stages the whole verified file, streamed from MDK's source so a large one is
                    // never read into memory.
                    attachmentSource(
                        controller = controller,
                        messageIdHex = messageIdHex,
                        attachmentIndex = source.attachmentIndex,
                        reference = source.reference,
                        mine = mine,
                    )
                // A file-backed item has no in-memory copy; its message becomes shareable once confirmed.
                is MessageShareAttachmentSource.Retained ->
                    AttachmentPlaintext.Bytes(
                        checkNotNull(source.attachment.inMemoryBytes) { "Attachment is still uploading" },
                    )
            }
        }
    launchStagedMessageShare(staged) { streams ->
        launchOutboundShare(context, outboundShareIntent(text, streams), chooserTitle)
    }
}

/**
 * The attachment's verified plaintext as a source that may be a private file, so a large attachment is never read
 * whole into memory: an own pending item's retained bytes, otherwise MDK's download of the authoritative reference.
 * The caller closes it.
 */
private suspend fun attachmentSource(
    controller: ConversationController,
    messageIdHex: String,
    attachmentIndex: Int,
    reference: MediaAttachmentReferenceFfi,
    mine: Boolean,
    priority: AttachmentDownloadPriority = AttachmentDownloadPriority.Interactive,
): AttachmentPlaintext {
    if (mine) {
        controller
            .pendingAttachmentsList(messageIdHex)
            .getOrNull(attachmentIndex)
            ?.inMemoryBytes
            ?.let { return AttachmentPlaintext.Bytes(it) }
    }
    val resolvedReference =
        authoritativeVisualMediaReference(reference, mine) {
            controller.authoritativeAttachmentReference(messageIdHex, attachmentIndex, reference)
        }
    // Durable work backs the foreground stream, as it does for a streaming Open or Save, and coalesces with any
    // earlier request for the same attachment.
    controller.enqueueInteractiveAttachmentDownload(messageIdHex, attachmentIndex, resolvedReference)
    return controller.downloadAttachmentSource(messageIdHex, attachmentIndex, resolvedReference, priority)
}

internal class StagedMessageShareStreams(
    val streams: List<OutboundShareStream>,
    private val plaintextFiles: List<File>,
) {
    suspend fun deletePlaintext() {
        withContext(NonCancellable + Dispatchers.IO) {
            plaintextFiles.forEach { file -> runCatching { file.delete() } }
        }
    }
}

/** Delete the staged set if cancellation or chooser launch interrupts publication. */
internal suspend fun launchStagedMessageShare(
    staged: StagedMessageShareStreams,
    launch: (List<OutboundShareStream>) -> Result<Unit>,
) {
    try {
        currentCoroutineContext().ensureActive()
        launch(staged.streams).getOrThrow()
    } catch (failure: Throwable) {
        staged.deletePlaintext()
        throw failure
    }
}

/**
 * Materialize every attachment into the bounded shared-media cache. Files are
 * publication-protected as one set so the LRU cannot evict an earlier stream
 * while a later stream is still being written. Each source is streamed, or a
 * private file lease is moved into place, so no attachment is read whole into
 * memory, and every source is closed. The cache's reader window then keeps the
 * newest staged files only up to its budget, so a second large share soon after
 * may evict the first one's files before a slow receiving app opens them.
 */
internal suspend fun stageMessageShareStreams(
    context: Context,
    sources: List<MessageShareAttachmentSource>,
    resolveSource: suspend (MessageShareAttachmentSource) -> AttachmentPlaintext,
): StagedMessageShareStreams {
    if (sources.isEmpty()) return StagedMessageShareStreams(emptyList(), emptyList())
    val protectedFiles = mutableListOf<File>()
    var totalBytes = 0L
    try {
        val streams =
            sources.map { source ->
                currentCoroutineContext().ensureActive()
                resolveSource(source).use { plaintext ->
                    currentCoroutineContext().ensureActive()
                    totalBytes = boundedShareTotal(totalBytes, plaintext.size)
                    stageShareFile(context, source, plaintext, protectedFiles)
                }
            }
        val stagedFiles = protectedFiles.toList()
        withContext(NonCancellable + Dispatchers.IO) {
            protectedFiles.forEach(AttachmentPlaintextCache::finishPublication)
            protectedFiles.clear()
        }
        return StagedMessageShareStreams(streams, stagedFiles)
    } catch (failure: Throwable) {
        withContext(NonCancellable + Dispatchers.IO) {
            protectedFiles.forEach { file ->
                AttachmentPlaintextCache.unprotectPublicationFile(file)
                runCatching { file.delete() }
            }
            protectedFiles.clear()
        }
        throw failure
    }
}

/**
 * Writes one share source into a new file of the shared cache and returns its FileProvider stream. The file joins
 * [protectedFiles] before anything is written, so a failure anywhere leaves it to the caller's cleanup. A private
 * file lease is moved into place when it can be, so a large attachment is not copied twice.
 */
private suspend fun stageShareFile(
    context: Context,
    source: MessageShareAttachmentSource,
    plaintext: AttachmentPlaintext,
    protectedFiles: MutableList<File>,
): OutboundShareStream {
    val job = currentCoroutineContext()[Job]
    return withContext(Dispatchers.IO) {
        val directory = File(context.cacheDir, MediaCacheDirs.SHARED).apply { mkdirs() }
        check(directory.isDirectory) { "Unable to create outbound share cache" }
        val safeName = safeOutboundFileName(source.fileName)
        val expectedBytes = plaintext.size
        val file = File.createTempFile("message_", "_$safeName", directory)
        try {
            AttachmentPlaintextCache.requireEntryWithinLimit(file, expectedBytes)
            AttachmentPlaintextCache.protectPublicationFile(file)
            protectedFiles += file
            if (!(plaintext is AttachmentPlaintext.Lease && plaintext.file.renameTo(file))) {
                // A cancelled Share stops copying at the next chunk instead of finishing a large file.
                CancellableOutputStream(file.outputStream(), job).use(plaintext::copyTo)
            }
            if (file.length() != expectedBytes) throw IOException("share source length changed while staging")
            // The cache file keeps its collision-safe generated name, while the
            // receiving app is told the attachment's own sanitized name, so
            // `story.pdf` arrives as `story.pdf` rather than `message_…_story.pdf`.
            val authority = "${context.packageName}.fileprovider"
            val uri = FileProvider.getUriForFile(context, authority, file, safeName)
            OutboundShareStream(uri, source.mediaType)
        } catch (failure: Throwable) {
            if (file !in protectedFiles) runCatching { file.delete() }
            throw failure
        }
    }
}

/** Writes through to [delegate], refusing every write once [job] is cancelled, so a copy stops at its next chunk. */
private class CancellableOutputStream(
    private val delegate: OutputStream,
    private val job: Job?,
) : FilterOutputStream(delegate) {
    /** Writes one byte unless the share was cancelled. */
    override fun write(b: Int) {
        job?.ensureActive()
        delegate.write(b)
    }

    /** Writes [len] bytes of [b] from [off] in one call unless the share was cancelled. */
    override fun write(
        b: ByteArray,
        off: Int,
        len: Int,
    ) {
        job?.ensureActive()
        delegate.write(b, off, len)
    }
}

internal fun safeOutboundFileName(untrustedName: String): String =
    MediaPipeline.safeDisplayName(
        untrustedName.filterNot { character ->
            character.isISOControl() || Character.getType(character) == Character.FORMAT.toInt()
        },
    )

// A staged set must fit the shared cache's budget, so its trim never evicts a stream the receiving app has yet to
// read. That is also MarmotKit's attachment ceiling, so one large sent or received file can be shared.
internal const val OUTBOUND_SHARE_MAX_BYTES: Long = AttachmentPlaintextCache.SHARED_MAX_DIRECTORY_BYTES

internal fun boundedShareTotal(
    currentBytes: Long,
    nextBytes: Long,
): Long {
    val total = if (nextBytes > Long.MAX_VALUE - currentBytes) Long.MAX_VALUE else currentBytes + nextBytes
    if (total > OUTBOUND_SHARE_MAX_BYTES) {
        throw IOException("outbound share exceeds bounded cache limit")
    }
    return total
}
