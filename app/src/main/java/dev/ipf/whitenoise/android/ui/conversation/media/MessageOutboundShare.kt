@file:Suppress(
    "MatchingDeclarationName", // The sealed source model and its materialization boundary are one contract.
    "TooGenericExceptionCaught", // Cleanup must cover every failure, including cancellation and provider errors.
)

package dev.ipf.whitenoise.android.ui.conversation.media

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.whitenoise.android.media.AttachmentPlaintextCache
import dev.ipf.whitenoise.android.media.MediaCacheDirs
import dev.ipf.whitenoise.android.media.MediaPipeline
import dev.ipf.whitenoise.android.share.OutboundShareStream
import dev.ipf.whitenoise.android.share.launchOutboundShare
import dev.ipf.whitenoise.android.share.outboundShareChooser
import dev.ipf.whitenoise.android.share.outboundShareIntent
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.PendingAttachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

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

internal fun messageHasShareablePayload(
    text: String?,
    references: List<MediaAttachmentReferenceFfi>,
    retained: List<PendingAttachment>,
    protocolAttachmentCount: Int = references.size,
): Boolean =
    messageShareAttachmentSetIsComplete(protocolAttachmentCount, references.size) &&
        (!text.isNullOrBlank() || references.isNotEmpty() || retained.isNotEmpty())

internal fun messageShareAttachmentSetIsComplete(
    protocolAttachmentCount: Int,
    projectedAttachmentCount: Int,
): Boolean = protocolAttachmentCount == 0 || protocolAttachmentCount == projectedAttachmentCount

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
    val streams =
        stageMessageShareStreams(context, sources) { source ->
            when (source) {
                is MessageShareAttachmentSource.Confirmed ->
                    attachmentBytes(
                        controller = controller,
                        messageIdHex = messageIdHex,
                        attachmentIndex = source.attachmentIndex,
                        reference = source.reference,
                        mine = mine,
                    )
                is MessageShareAttachmentSource.Retained -> source.attachment.plaintextBytes
            }
        }
    currentCoroutineContext().ensureActive()
    launchOutboundShare(context, outboundShareIntent(text, streams), chooserTitle).getOrThrow()
}

/**
 * Materialize every attachment into the bounded shared-media cache. Files are
 * publication-protected as one set so the LRU cannot evict an earlier stream
 * while a later stream is still being written.
 */
internal suspend fun stageMessageShareStreams(
    context: Context,
    sources: List<MessageShareAttachmentSource>,
    resolveBytes: suspend (MessageShareAttachmentSource) -> ByteArray,
): List<OutboundShareStream> {
    if (sources.isEmpty()) return emptyList()
    val protectedFiles = mutableListOf<File>()
    var totalBytes = 0L
    try {
        val streams =
            sources.map { source ->
                currentCoroutineContext().ensureActive()
                val bytes = resolveBytes(source)
                currentCoroutineContext().ensureActive()
                totalBytes = boundedShareTotal(totalBytes, bytes.size.toLong())
                withContext(Dispatchers.IO) {
                    val directory = File(context.cacheDir, MediaCacheDirs.SHARED).apply { mkdirs() }
                    check(directory.isDirectory) { "Unable to create outbound share cache" }
                    val safeName = safeOutboundFileName(source.fileName)
                    val file = File.createTempFile("message_", "_$safeName", directory)
                    try {
                        AttachmentPlaintextCache.requireEntryWithinLimit(file, bytes.size.toLong())
                        AttachmentPlaintextCache.protectPublicationFile(file)
                        protectedFiles += file
                        file.outputStream().use { it.write(bytes) }
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                        OutboundShareStream(uri, source.mediaType)
                    } catch (failure: Throwable) {
                        if (file !in protectedFiles) runCatching { file.delete() }
                        throw failure
                    }
                }
            }
        withContext(NonCancellable + Dispatchers.IO) {
            protectedFiles.forEach(AttachmentPlaintextCache::finishPublication)
            protectedFiles.clear()
        }
        return streams
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

internal fun safeOutboundFileName(untrustedName: String): String =
    MediaPipeline.safeDisplayName(
        untrustedName.filterNot { character ->
            character.isISOControl() || Character.getType(character) == Character.FORMAT.toInt()
        },
    )

internal fun boundedShareTotal(
    currentBytes: Long,
    nextBytes: Long,
): Long {
    val total = if (nextBytes > Long.MAX_VALUE - currentBytes) Long.MAX_VALUE else currentBytes + nextBytes
    if (total > AttachmentPlaintextCache.SHARED_MAX_DIRECTORY_BYTES) {
        throw IOException("outbound share exceeds bounded cache limit")
    }
    return total
}
