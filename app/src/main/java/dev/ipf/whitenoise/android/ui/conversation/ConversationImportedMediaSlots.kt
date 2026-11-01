package dev.ipf.whitenoise.android.ui.conversation

import android.content.Context
import android.net.Uri
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.share.ShareStreamStaging
import dev.ipf.whitenoise.android.state.ATTACHMENT_COLLECTION_MAX_ITEMS
import dev.ipf.whitenoise.android.state.AppText
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.conversation.media.PendingMediaSlot
import dev.ipf.whitenoise.android.ui.conversation.media.appendPendingMediaSlots

/** Restores imported ordering while preserving edited occurrence identities and ordinary picker positions. */
internal fun restoreImportedMediaSlots(
    current: List<PendingMediaSlot>,
    restored: List<Uri>,
    maxItems: Int,
    owns: (Uri) -> Boolean,
): List<PendingMediaSlot> {
    val retained = current.filter { !owns(it.uri) || it.uri in restored }
    val added = restored.filterNot { uri -> retained.any { it.uri == uri } }
    val appended = appendPendingMediaSlots(retained, added, maxItems)
    val importedInOrder = appended.filter { owns(it.uri) }.sortedBy { restored.indexOf(it.uri) }.iterator()
    return appended.map { slot -> if (owns(slot.uri)) importedInOrder.next() else slot }
}

/** Recover accepted sources without discarding bytes or changing an existing edited occurrence. */
internal fun restoreImportedComposerAttachments(
    currentMedia: List<PendingMediaSlot>,
    currentDocuments: List<Uri>,
    staging: ShareStreamStaging,
    owns: (Uri) -> Boolean,
): RestoredConversationAttachments {
    val allRestored = (staging.mediaUris + staging.documentUris).toSet()
    val existingMedia = currentMedia.filter { owns(it.uri) && it.uri in allRestored }.map { it.uri }
    val existingDocuments = currentDocuments.filter { owns(it) && it in allRestored }
    val media = (staging.mediaUris.filterNot { it in existingDocuments } + existingMedia).distinct()
    val documents = (staging.documentUris.filterNot { it in existingMedia } + existingDocuments).distinct()
    return RestoredConversationAttachments(
        restoreImportedMediaSlots(currentMedia, media, Int.MAX_VALUE, owns),
        (currentDocuments.filterNot(owns) + documents).distinct(),
    )
}

/** An over-limit recovery requires explicit removal; it must never silently drop a saved source. */
internal fun importedComposerExceedsLimit(
    media: List<PendingMediaSlot>,
    documents: List<Uri>,
    maxItems: Int,
    owns: (Uri) -> Boolean,
): Boolean {
    if (media.size + documents.size <= maxItems) return false
    return media.any { owns(it.uri) } || documents.any(owns)
}

/** The leading part of a pick that fits the composer's combined limit, and how many items did not fit. */
internal data class ComposerAttachmentAdmission<T>(
    val accepted: List<T>,
    val droppedCount: Int,
)

/**
 * Keeps the first [added] items, in order, that fit beside [mediaCount] staged media and [documentCount]
 * staged documents under one combined [maxItems] limit (#2576). Already staged items are never trimmed,
 * even when a recovered shelf is over the limit, and every item that does not fit is counted.
 */
internal fun <T> admitComposerAttachments(
    added: List<T>,
    mediaCount: Int,
    documentCount: Int,
    maxItems: Int,
): ComposerAttachmentAdmission<T> {
    val accepted = added.take((maxItems - mediaCount - documentCount).coerceAtLeast(0))
    return ComposerAttachmentAdmission(accepted, added.size - accepted.size)
}

/**
 * Tells the user how many of [offeredCount] picks were added, how many did not fit and what the combined
 * [limit] is, so no path drops a pick without saying so (#2576). [detail] carries a second outcome of the
 * same pick, such as an unreadable image, in the same notice instead of a later notice replacing this one.
 */
internal fun WhiteNoiseAppState.presentAttachmentAdmission(
    context: Context,
    acceptedCount: Int,
    offeredCount: Int,
    limit: Int = ATTACHMENT_COLLECTION_MAX_ITEMS,
    detail: AppText? = null,
) {
    val rejected = offeredCount - acceptedCount
    if (rejected <= 0) return
    presentText(
        AppText.Plain(
            context.resources.getQuantityString(
                R.plurals.attachment_collection_limit_result,
                rejected,
                acceptedCount,
                offeredCount,
                rejected,
                limit,
            ),
        ),
        detail = detail,
    )
}

/** The admission's accepted, rejected and limit counts as one notice; nothing is shown when all fit. */
internal fun <T> WhiteNoiseAppState.presentAdmission(
    context: Context,
    admission: ComposerAttachmentAdmission<T>,
    detail: AppText? = null,
) = presentAttachmentAdmission(
    context,
    admission.accepted.size,
    admission.accepted.size + admission.droppedCount,
    detail = detail,
)

/**
 * How many items one system photo-picker result may hold: the combined limit, lowered to what the platform
 * picker accepts where it reports a smaller maximum. Over-limit results are still admitted and reported.
 */
@android.annotation.SuppressLint("NewApi") // getPickImagesMaxLimit is guarded by the SDK and extension checks.
internal fun photoPickerItemLimit(): Int {
    val platformPicker =
        android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU ||
            (
                android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R &&
                    android.os.ext.SdkExtensions
                        .getExtensionVersion(android.os.Build.VERSION_CODES.R) >= 2
            )
    val platformLimit = if (platformPicker) android.provider.MediaStore.getPickImagesMaxLimit() else Int.MAX_VALUE
    return platformLimit.coerceIn(2, ATTACHMENT_COLLECTION_MAX_ITEMS)
}

/** Adds missing native items without discarding later picks, current occurrence URIs or private ownership. */
internal fun mergeRestoredComposerAttachments(
    currentMedia: List<PendingMediaSlot>,
    currentDocuments: List<Uri>,
    restored: RestoredConversationAttachments,
    owns: (Uri) -> Boolean,
): RestoredConversationAttachments {
    val currentIds = currentMedia.map(PendingMediaSlot::id).toSet()
    val missingNative = restored.mediaSlots.filter { !owns(it.uri) && it.id !in currentIds }
    return RestoredConversationAttachments(
        mediaSlots = missingNative + currentMedia,
        documentUris = (restored.documentUris.filterNot(owns) + currentDocuments).distinct(),
    )
}

/** Apply authoritative ordinary reconciliation while retaining independent imported picks in place. */
internal fun mergeReconciledComposerAttachments(
    currentMedia: List<PendingMediaSlot>,
    currentDocuments: List<Uri>,
    reconciled: RestoredConversationAttachments,
    owns: (Uri) -> Boolean,
): RestoredConversationAttachments {
    val survivingIds = reconciled.mediaSlots.map(PendingMediaSlot::id).toSet()
    val retainedMedia = currentMedia.filter { owns(it.uri) || it.id in survivingIds }
    val retainedDocuments = currentDocuments.filter { owns(it) || it in reconciled.documentUris }
    val currentIds = retainedMedia.map(PendingMediaSlot::id).toSet()
    return RestoredConversationAttachments(
        reconciled.mediaSlots.filter { !owns(it.uri) && it.id !in currentIds } + retainedMedia,
        reconciled.documentUris.filter { !owns(it) && it !in retainedDocuments } + retainedDocuments,
    )
}
