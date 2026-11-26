package dev.ipf.whitenoise.android.ui.conversation

import android.net.Uri
import dev.ipf.marmotkit.MessageDraftAttachmentFfi
import dev.ipf.whitenoise.android.media.editor.DraftBackedPhoto
import dev.ipf.whitenoise.android.media.editor.DraftPreparedPhoto
import dev.ipf.whitenoise.android.media.editor.EditorPixelSize
import dev.ipf.whitenoise.android.media.editor.PhotoEditRecipe
import dev.ipf.whitenoise.android.media.editor.PhotoEditorSourceInfo
import dev.ipf.whitenoise.android.media.editor.editorDigest
import dev.ipf.whitenoise.android.state.MediaQuality
import dev.ipf.whitenoise.android.ui.conversation.media.PendingMediaSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ConversationDraftRestorationPlanTest {
    /** A restored photo removed from the native draft leaves the shelf and its prepared bytes. */
    @Test
    fun staleNativePhotoCannotKeepRemovedRestoredBytesOnTheShelf() {
        val stale = attachment("removed-photo")
        val fresh = attachment("fresh-photo")
        val inputs =
            emptyInputs().copy(
                slots = listOf(slot(stale.id), slot(fresh.id)),
                preparedPhotos =
                    mapOf(
                        stale.id to DraftPreparedPhoto(stale, "restored", true),
                        fresh.id to DraftPreparedPhoto(fresh, "fresh"),
                    ),
                descriptions = mapOf(stale.id to "removed description", fresh.id to "fresh description"),
            )
        val projected = restore(inputs, stale)
        assertEquals(listOf(slot(fresh.id)), projected.media)
        assertEquals(mapOf(fresh.id to inputs.preparedPhotos.getValue(fresh.id)), projected.inputs.preparedPhotos)
        assertEquals(mapOf(fresh.id to "fresh description"), projected.inputs.descriptions)
    }

    /** A restored backed photo removed from the native draft loses its backing while fresh photos keep theirs. */
    @Test
    fun staleNativePhotoCannotKeepRemovedRestoredBacking() {
        val stale = attachment("removed-backing")
        val fresh = attachment("fresh-backing")
        val inputs =
            emptyInputs().copy(
                slots = listOf(slot(stale.id), slot(fresh.id)),
                backedPhotos = mapOf(stale.id to backed(stale, true), fresh.id to backed(fresh, false)),
            )
        val projected = restore(inputs, stale)
        assertEquals(listOf(slot(fresh.id)), projected.media)
        assertEquals(mapOf(fresh.id to inputs.backedPhotos.getValue(fresh.id)), projected.inputs.backedPhotos)
        assertTrue(projected.inputs.preparedPhotos.isEmpty())
    }

    /** A restored document removed from the native draft leaves the shelf and its prepared bytes. */
    @Test
    fun staleNativeDocumentCannotKeepRemovedRestoredBytes() {
        val stale = attachment("removed-document", "application/pdf")
        val fresh = attachment("fresh-document", "application/pdf")
        val staleUri = Uri.parse("content://draft/removed-document")
        val freshUri = Uri.parse("content://picker/fresh-document")
        val inputs =
            emptyInputs().copy(
                documentShelf = DraftDocumentShelf(listOf(staleUri, freshUri), emptyMap()),
                preparedDocuments =
                    mapOf(
                        staleUri to DraftPreparedPhoto(stale, "restored", true),
                        freshUri to DraftPreparedPhoto(fresh, "fresh"),
                    ),
            )
        val projected = restore(inputs, stale)
        assertEquals(listOf(freshUri), projected.documents)
        assertEquals(mapOf(freshUri to inputs.preparedDocuments.getValue(freshUri)), projected.inputs.preparedDocuments)
    }

    /**
     * A shelf restored from saved state after the process died: a stored video still points at its picker URI, whose
     * grant is gone, beside a second attachment. The video is relinked to its app-owned copy in place, and the second
     * attachment keeps its URI.
     */
    @Test
    fun savedStateStoredVideoIsRelinkedInPlaceBesideASecondAttachment() {
        val video = attachment("video", "video/mp4")
        val photo = attachment("photo")
        val pickerUri = Uri.parse("content://picker/video")
        val inputs = emptyInputs().copy(slots = listOf(PendingMediaSlot(video.id, pickerUri), slot(photo.id)))
        val plan = planDraftRestoration(inputs, "account", "group", listOf(video, photo), emptySet())
        assertEquals(listOf(video to pickerUri), matchedStoredEntries(inputs, plan, setOf(video.id)))

        val copy = Uri.parse("content://app/video")
        val projected = project(inputs, plan, mapOf(video.id to copy))

        assertEquals(listOf(PendingMediaSlot(video.id, copy), slot(photo.id)), projected.media)
        assertEquals(setOf(video.id, photo.id), projected.inputs.preparedPhotos.keys)
    }

    /** A saved-state stored document relinked to its copy keeps its place, and its prepared entry moves to the copy. */
    @Test
    fun savedStateStoredDocumentIsRelinkedInPlace() {
        val pickerUri = Uri.parse("content://picker/report")
        val otherUri = Uri.parse("content://picker/notes")
        val report = attachment(stagedDocumentAttachmentId("account", "group", pickerUri.toString()), "application/pdf")
        val notes = attachment(stagedDocumentAttachmentId("account", "group", otherUri.toString()), "application/pdf")
        val inputs = emptyInputs().copy(documentShelf = DraftDocumentShelf(listOf(pickerUri, otherUri), emptyMap()))
        val plan = planDraftRestoration(inputs, "account", "group", listOf(report, notes), emptySet())
        assertEquals(listOf(report to pickerUri), matchedStoredEntries(inputs, plan, setOf(report.id)))

        val copy = Uri.parse("content://app/report")
        val projected = project(inputs, plan, mapOf(report.id to copy))

        assertEquals(listOf(copy, otherUri), projected.documents)
        assertEquals(
            report,
            projected.inputs.preparedDocuments
                .getValue(copy)
                .attachment,
        )
        assertFalse(pickerUri in projected.inputs.preparedDocuments)
    }

    /**
     * The screen's merge of a restore that relinked a saved-state video and document keeps each copy where its old
     * entry stood, beside entries that kept their URIs, so preview and a collection send read the copies.
     */
    @Test
    fun relinkedEntriesReachTheComposerInPlaceThroughTheScreenMerge() {
        val video = attachment("video", "video/mp4")
        val photo = attachment("photo")
        val notesUri = Uri.parse("content://picker/notes")
        val reportUri = Uri.parse("content://picker/report")
        val notes = attachment(stagedDocumentAttachmentId("account", "group", notesUri.toString()), "application/pdf")
        val report = attachment(stagedDocumentAttachmentId("account", "group", reportUri.toString()), "application/pdf")
        val shelf = listOf(PendingMediaSlot(video.id, Uri.parse("content://picker/video")), slot(photo.id))
        val inputs =
            emptyInputs().copy(
                slots = shelf,
                documentShelf = DraftDocumentShelf(listOf(notesUri, reportUri), emptyMap()),
            )
        val plan = planDraftRestoration(inputs, "account", "group", listOf(video, photo, notes, report), emptySet())
        val videoCopy = Uri.parse("content://app/video")
        val reportCopy = Uri.parse("content://app/report")
        val restored = project(inputs, plan, mapOf(video.id to videoCopy, report.id to reportCopy)).shelf()

        val merged = mergeReconciledComposerAttachments(inputs.slots, inputs.documents, restored, owns = { false })

        assertEquals(listOf(PendingMediaSlot(video.id, videoCopy), slot(photo.id)), merged.mediaSlots)
        assertEquals(listOf(notesUri, reportCopy), merged.documentUris)
    }

    /** A stored pick this composer prepared itself is never offered for relinking. */
    @Test
    fun preparedStoredPickIsNotRelinked() {
        val video = attachment("video", "video/mp4")
        val inputs =
            emptyInputs().copy(
                slots = listOf(slot(video.id)),
                preparedPhotos = mapOf(video.id to DraftPreparedPhoto(video, "fresh")),
            )
        val plan = planDraftRestoration(inputs, "account", "group", listOf(video), emptySet())

        assertTrue(matchedStoredEntries(inputs, plan, setOf(video.id)).isEmpty())
    }

    /** Projects [plan] over [inputs] with nothing added and [relinked] entries moved to their copies. */
    private fun project(
        inputs: DraftRestorationInputs,
        plan: DraftRestorationPlan,
        relinked: Map<String, Uri>,
    ): DraftRestorationProjection {
        val unavailable = "source unavailable"
        return projectDraftRestoration(inputs, plan, emptyList(), emptySet(), unavailable, relinked)
    }

    /** Plans and projects a restoration where [stale] was removed natively, asserting nothing is reconciled. */
    private fun restore(
        inputs: DraftRestorationInputs,
        stale: MessageDraftAttachmentFfi,
    ): DraftRestorationProjection {
        val removedIds = setOf(stale.id)
        val plan = planDraftRestoration(inputs, "account", "group", listOf(stale), removedIds)
        assertTrue(plan.reconciliation.unmatched.isEmpty())
        assertTrue(plan.reconciliation.mediaBySlotId.isEmpty())
        assertTrue(plan.reconciliation.documentsByUriString.isEmpty())
        return projectDraftRestoration(inputs, plan, emptyList(), removedIds, "source unavailable")
    }

    /** A reselection keeps its new identity after the first send consumes a previous pick of the same URI. */
    @Test
    fun sameUriReselectionKeepsItsOwnStoredNativeAttachment() {
        val uri = Uri.parse("content://picker/document/report.zip")
        val previous = newPickerDocumentIds(emptyMap(), emptyList(), listOf(uri))
        val current = newPickerDocumentIds(previous, emptyList(), listOf(uri))
        val previousId = previous.getValue(uri.toString())
        val currentId = current.getValue(uri.toString())
        assertTrue(previousId != currentId)
        assertEquals(current, newPickerDocumentIds(current, listOf(uri), listOf(uri)))
        val body = attachment(currentId, "application/zip")
        val prepared = DraftPreparedPhoto(body, body.editorDigest(), restoredFromNative = true)
        val inputs =
            emptyInputs().copy(
                documentShelf = DraftDocumentShelf(listOf(uri), current),
                preparedDocuments = mapOf(uri to prepared),
            )

        val afterConsumption = planDraftRestoration(inputs, "account", "group", listOf(body), setOf(previousId))

        assertEquals(listOf(uri), afterConsumption.retainedDocuments)
        assertEquals(body, afterConsumption.reconciliation.documentsByUriString[uri.toString()])
        assertTrue(afterConsumption.removedDocuments.isEmpty())
    }

    /** Builds restoration inputs with an empty shelf. */
    private fun emptyInputs() =
        DraftRestorationInputs(
            slots = emptyList(),
            documentShelf = DraftDocumentShelf(emptyList(), emptyMap()),
            preparedPhotos = emptyMap(),
            preparedDocuments = emptyMap(),
            backedPhotos = emptyMap(),
            descriptions = emptyMap(),
        )

    /** Builds a shelf slot for draft attachment [id]. */
    private fun slot(id: String) = PendingMediaSlot(id, Uri.parse("content://draft/$id"))

    /** Builds a minimal native draft attachment with the given [mediaType]. */
    private fun attachment(
        id: String,
        mediaType: String = "image/png",
    ) = MessageDraftAttachmentFfi(
        id,
        "$id.bin",
        mediaType,
        byteArrayOf(1, 2, 3),
        null,
        null,
        null,
        emptyList(),
    )

    /** Builds a backed photo for [attachment], marked as restored from native when [restored]. */
    private fun backed(
        attachment: MessageDraftAttachmentFfi,
        restored: Boolean,
    ) = DraftBackedPhoto(
        attachment,
        "digest",
        "lease",
        PhotoEditorSourceInfo(EditorPixelSize(1, 1), EditorPixelSize(1, 1), 1, "image/png", false),
        PhotoEditRecipe(),
        MediaQuality.Standard,
        restored,
    )
}
