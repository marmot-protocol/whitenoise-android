package dev.ipf.whitenoise.android.ui.conversation

import android.net.Uri
import dev.ipf.whitenoise.android.media.pdf.PdfMetadataResult
import dev.ipf.whitenoise.android.state.AttachmentCollectionOperation
import dev.ipf.whitenoise.android.state.CollectionItemKind
import dev.ipf.whitenoise.android.ui.conversation.media.ComposerPdfDecision
import dev.ipf.whitenoise.android.ui.conversation.media.ComposerPdfMetadataChoices
import dev.ipf.whitenoise.android.ui.conversation.media.ComposerPdfState
import dev.ipf.whitenoise.android.ui.conversation.media.inspectComposerPdf
import dev.ipf.whitenoise.android.ui.conversation.media.safeDocumentDisplayName
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Checks remaining document inputs before changing a paused journal or starting its next chunk. */
internal suspend fun ConversationMediaSender.confirmResumedPdfMetadata(
    operation: AttachmentCollectionOperation,
    canAskForPdfConsent: () -> Boolean,
): Set<Uri>? {
    val documents =
        operation.items
            .filter { item ->
                item.kind == CollectionItemKind.DOCUMENT &&
                    !item.state.isSettled &&
                    item.id !in operation.unrecoverableItemIds
            }.map { Uri.parse(it.uri) }
            .distinct()
    val choices = collectionResumePdfChoices
    val refused =
        documents.mapNotNull { uri ->
            val name = safeDocumentDisplayName(uri.lastPathSegment)
            val metadata = inspectComposerPdf(context, uri, name)
            if (metadata is PdfMetadataResult.Refused) {
                uri to ComposerPdfState(name, metadata)
            } else {
                null
            }
        }
    if (refused.isEmpty()) return emptySet()
    val locked = appState.appLock.screenVisible || appState.appLock.evaluationPending
    val currentSender = latestCollectionSender(operation.accountRef, operation.groupIdHex) === this
    val canPrompt = canAskForPdfConsent() && currentSender && !locked
    return if (canPrompt && choices.decision == null) choices.awaitConsent(refused) else null
}

private suspend fun ComposerPdfMetadataChoices.awaitConsent(refused: List<Pair<Uri, ComposerPdfState>>): Set<Uri>? {
    var waiting: ComposerPdfDecision? = null
    try {
        return suspendCancellableCoroutine { continuation ->
            val uris = refused.map { it.first }
            retainOnly(emptyList())
            refused.forEach { (uri, state) -> record(uri, state) }
            confirmThen(uris, { continuation.resume(null) }) { continuation.resume(it) }
            waiting = decision
        }
    } finally {
        if (waiting != null && decision === waiting) cancel()
    }
}
