package dev.ipf.whitenoise.android.ui.conversation

import android.net.Uri
import dev.ipf.marmotkit.MessageDraftAttachmentFfi
import dev.ipf.marmotkit.MessageDraftFfi
import dev.ipf.whitenoise.android.media.editor.DraftBackedPhoto
import dev.ipf.whitenoise.android.media.editor.DraftPreparedPhoto
import dev.ipf.whitenoise.android.media.editor.MessageDraftRepository
import dev.ipf.whitenoise.android.media.editor.editorDigest
import dev.ipf.whitenoise.android.ui.conversation.media.PendingMediaSlot
import kotlinx.coroutines.delay

private const val DRAFT_RESTORATION_RETRY_DELAY_MILLIS = 250L

/** One bounded retry keeps a transient initial read from stranding the document shelf. */
internal suspend fun readDraftForRestoration(
    drafts: MessageDraftRepository,
    accountRef: String,
    groupIdHex: String,
): Result<MessageDraftFfi?> {
    val initial = drafts.draft(accountRef, groupIdHex)
    if (initial.isSuccess) return initial
    delay(DRAFT_RESTORATION_RETRY_DELAY_MILLIS)
    return drafts.draft(accountRef, groupIdHex)
}

/** A native refresh cannot replace locally editable bytes or relabel freshly prepared picks. */
internal fun nativePhotosNeedingRestoration(
    nativeBySlot: Map<String, MessageDraftAttachmentFfi>,
    backedSlotIds: Set<String>,
    prepared: Map<String, DraftPreparedPhoto>,
): Map<String, MessageDraftAttachmentFfi> =
    nativeBySlot.filterKeys {
        it !in backedSlotIds && prepared[it]?.restoredFromNative != false
    }

/** Documents prepared by this composer retain their local lifetime across native refreshes. */
internal fun nativeDocumentsNeedingRestoration(
    nativeByUri: Map<String, MessageDraftAttachmentFfi>,
    prepared: Map<Uri, DraftPreparedPhoto>,
): Map<Uri, MessageDraftAttachmentFfi> =
    nativeByUri
        .mapKeys { (uri, _) -> Uri.parse(uri) }
        .filterKeys { prepared[it]?.restoredFromNative != false }

/** Wraps a native draft attachment as a prepared photo marked as restored from the native draft. */
private fun MessageDraftAttachmentFfi.asRestoredPhoto(): DraftPreparedPhoto =
    DraftPreparedPhoto(
        this,
        editorDigest(),
        restoredFromNative = true,
    )

/** Returns the slot ids whose restored photo is no longer among the live native attachment [nativeIds]. */
internal fun restoredPhotosMissingFrom(
    slots: List<PendingMediaSlot>,
    prepared: Map<String, DraftPreparedPhoto>,
    backed: Map<String, DraftBackedPhoto>,
    nativeIds: Set<String>,
): Set<String> =
    slots
        .filter { slot ->
            prepared[slot.id]?.let { it.restoredFromNative && it.attachment.id !in nativeIds } == true ||
                backed[slot.id]?.let { it.restoredFromNative && it.attachment.id !in nativeIds } == true
        }.mapTo(mutableSetOf()) { it.id }

/** Returns the document URIs whose restored attachment is no longer among the live native attachment [nativeIds]. */
internal fun restoredDocumentsMissingFrom(
    documents: List<Uri>,
    prepared: Map<Uri, DraftPreparedPhoto>,
    nativeIds: Set<String>,
): Set<Uri> =
    documents
        .filter { uri ->
            prepared[uri]?.let { it.restoredFromNative && it.attachment.id !in nativeIds } == true
        }.toSet()

internal data class DraftRestorationInputs(
    val slots: List<PendingMediaSlot>,
    val documents: List<Uri>,
    val preparedPhotos: Map<String, DraftPreparedPhoto>,
    val preparedDocuments: Map<Uri, DraftPreparedPhoto>,
    val backedPhotos: Map<String, DraftBackedPhoto>,
    val descriptions: Map<String, String>,
)

internal data class DraftRestorationPlan(
    val removedPhotos: Set<String>,
    val removedDocuments: Set<Uri>,
    val retainedSlots: List<PendingMediaSlot>,
    val retainedDocuments: List<Uri>,
    val reconciliation: PersistedDraftAttachmentReconciliation,
)

internal data class DraftRestorationProjection(
    val inputs: DraftRestorationInputs,
    val media: List<PendingMediaSlot>,
    val documents: List<Uri>,
)

/**
 * Plans which restored photos and documents to drop and which slots to keep, reconciling the composer against the
 * native draft minus [removedAttachmentIds].
 */
internal fun planDraftRestoration(
    inputs: DraftRestorationInputs,
    accountRef: String,
    groupIdHex: String,
    attachments: List<MessageDraftAttachmentFfi>,
    removedAttachmentIds: Set<String>,
): DraftRestorationPlan {
    val nativeIds = attachments.mapTo(mutableSetOf()) { it.id } - removedAttachmentIds
    val removedPhotos = restoredPhotosMissingFrom(inputs.slots, inputs.preparedPhotos, inputs.backedPhotos, nativeIds)
    val removedDocuments = restoredDocumentsMissingFrom(inputs.documents, inputs.preparedDocuments, nativeIds)
    val retainedSlots = inputs.slots.filterNot { it.id in removedPhotos }
    val retainedDocuments = inputs.documents.filterNot { it in removedDocuments }
    val reconciliation =
        reconcilePersistedDraftAttachments(
            accountRef = accountRef,
            groupIdHex = groupIdHex,
            mediaSlotIds = retainedSlots.map(PendingMediaSlot::id),
            documentUriStrings = retainedDocuments.map(Uri::toString),
            attachments = attachments,
            removedAttachmentIds = removedAttachmentIds,
        )
    return DraftRestorationPlan(removedPhotos, removedDocuments, retainedSlots, retainedDocuments, reconciliation)
}

/**
 * Applies [plan] to [inputs], adding materialized native attachments and labelling restored photos with
 * [sourceUnavailable], to produce the visible shelf.
 */
internal fun projectDraftRestoration(
    inputs: DraftRestorationInputs,
    plan: DraftRestorationPlan,
    materialized: List<Pair<MessageDraftAttachmentFfi, Uri>>,
    removedAttachmentIds: Set<String>,
    sourceUnavailable: String,
): DraftRestorationProjection {
    val restoredPhotos =
        nativePhotosNeedingRestoration(
            plan.reconciliation.mediaBySlotId,
            inputs.backedPhotos.keys,
            inputs.preparedPhotos,
        )
    var photos =
        inputs.preparedPhotos.filterKeys { it !in plan.removedPhotos } +
            restoredPhotos.mapValues { (_, attachment) -> attachment.asRestoredPhoto() }
    var documents =
        inputs.preparedDocuments.filterKeys { it !in plan.removedDocuments } +
            nativeDocumentsNeedingRestoration(plan.reconciliation.documentsByUriString, inputs.preparedDocuments)
                .mapValues { (_, attachment) -> attachment.asRestoredPhoto() }
    var descriptions = inputs.descriptions.filterKeys { it !in plan.removedPhotos }
    restoredPhotos.forEach { (slotId, attachment) ->
        if (attachment.mediaType.startsWith("image/", ignoreCase = true)) descriptions += slotId to sourceUnavailable
    }
    val media = plan.retainedSlots.toMutableList()
    val documentUris = plan.retainedDocuments.toMutableList()
    materialized.forEach { (attachment, uri) ->
        if (attachment.id in removedAttachmentIds) return@forEach
        val prepared = attachment.asRestoredPhoto()
        if (attachment.isComposerVisual() && !attachment.isComposerDocument()) {
            media += PendingMediaSlot(attachment.id, uri)
            photos += attachment.id to prepared
            if (attachment.mediaType.startsWith("image/", ignoreCase = true)) {
                descriptions += attachment.id to sourceUnavailable
            }
        } else {
            if (uri !in documentUris) documentUris += uri
            documents += uri to prepared
        }
    }
    val projected =
        inputs.copy(
            preparedPhotos = photos,
            preparedDocuments = documents,
            backedPhotos = inputs.backedPhotos.filterKeys { it !in plan.removedPhotos },
            descriptions = descriptions,
        )
    return DraftRestorationProjection(projected, media, documentUris)
}
