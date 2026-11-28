@file:Suppress("MatchingDeclarationName") // The attachment share entry points and their one-attachment model.

package dev.ipf.whitenoise.android.ui.conversation.media

import android.content.Context
import android.net.Uri
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.whitenoise.android.media.AttachmentPlaintext
import dev.ipf.whitenoise.android.share.OutboundShareStream
import dev.ipf.whitenoise.android.share.launchOutboundShare
import dev.ipf.whitenoise.android.share.outboundShareChooser
import dev.ipf.whitenoise.android.share.outboundShareIntent
import kotlinx.coroutines.CancellationException

/**
 * Stages one attachment's whole verified file, read through [plaintext], for Android's share sheet: its original
 * bytes and filename, never a reader's truncated preview, and none of its message's caption or other attachments.
 * [isCurrent] is checked again once the file is staged, so a reader closed or switched meanwhile opens no share sheet
 * and its staged plaintext is deleted.
 */
internal suspend fun shareAttachmentExternally(
    context: Context,
    accountRef: String,
    attachmentIndex: Int,
    reference: MediaAttachmentReferenceFfi,
    chooserTitle: String,
    isCurrent: () -> Boolean,
    plaintext: suspend () -> AttachmentPlaintext,
) = shareStagedAttachments(
    context = context,
    accountRef = accountRef,
    sources = listOf(MessageShareAttachmentSource.Confirmed(attachmentIndex, reference)),
    chooserTitle = chooserTitle,
    beforeLaunch = { if (!isCurrent()) throw CancellationException("shared attachment owner changed") },
) { plaintext() }

/** One verified attachment of a multi-attachment share, read through [plaintext] only while it is staged. */
internal class OutboundShareAttachment(
    val reference: MediaAttachmentReferenceFfi,
    val plaintext: suspend () -> AttachmentPlaintext,
)

/**
 * Stages every attachment's whole verified file as one bounded set and opens one share sheet for them, with no
 * caption. [beforeLaunch] runs once the set is staged and throws to cancel it, so no share sheet opens and every
 * staged file is deleted.
 */
internal suspend fun shareAttachmentsExternally(
    context: Context,
    accountRef: String,
    attachments: List<OutboundShareAttachment>,
    chooserTitle: String,
    beforeLaunch: suspend () -> Unit,
) = shareStagedAttachments(
    context = context,
    accountRef = accountRef,
    // A source's index is its position here, so the same file attached to two messages is still staged twice.
    sources =
        attachments.mapIndexed { position, attachment ->
            MessageShareAttachmentSource.Confirmed(position, attachment.reference)
        },
    chooserTitle = chooserTitle,
    beforeLaunch = beforeLaunch,
) { source -> attachments[source.attachmentIndex].plaintext() }

/** Resolves a share target before staging any plaintext, then stages [sources] and launches after [beforeLaunch]. */
private suspend fun shareStagedAttachments(
    context: Context,
    accountRef: String,
    sources: List<MessageShareAttachmentSource>,
    chooserTitle: String,
    beforeLaunch: suspend () -> Unit,
    resolveSource: suspend (MessageShareAttachmentSource) -> AttachmentPlaintext,
) {
    // Resolve handler availability before decrypting or retaining any new plaintext, as the message Share does.
    val probe =
        outboundShareIntent(
            null,
            sources.map { source ->
                OutboundShareStream(
                    uri = Uri.parse("content://${context.packageName}.fileprovider/outbound-share-probe"),
                    mediaType = source.mediaType,
                )
            },
        )
    outboundShareChooser(context, probe, chooserTitle)
    val staged = stageMessageShareStreams(context, accountRef, sources, resolveSource)
    launchStagedMessageShare(staged, context, beforeLaunch) { streams ->
        launchOutboundShare(context, outboundShareIntent(null, streams), chooserTitle)
    }
}
