package dev.ipf.whitenoise.android.ui.conversation

import android.net.Uri
import dev.ipf.marmotkit.MessageDraftAttachmentFfi
import dev.ipf.whitenoise.android.media.editor.DraftBackedPhoto
import dev.ipf.whitenoise.android.media.editor.DraftPreparedPhoto
import dev.ipf.whitenoise.android.media.editor.EditorPixelSize
import dev.ipf.whitenoise.android.media.editor.PhotoEditRecipe
import dev.ipf.whitenoise.android.media.editor.PhotoEditorSourceInfo
import dev.ipf.whitenoise.android.state.MediaQuality
import dev.ipf.whitenoise.android.ui.conversation.media.PendingMediaSlot
import org.junit.Assert.assertEquals
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
                documents = listOf(staleUri, freshUri),
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

    /** Builds restoration inputs with an empty shelf. */
    private fun emptyInputs() =
        DraftRestorationInputs(
            slots = emptyList(),
            documents = emptyList(),
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
