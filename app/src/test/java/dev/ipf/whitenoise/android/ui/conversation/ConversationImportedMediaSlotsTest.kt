package dev.ipf.whitenoise.android.ui.conversation

import android.net.Uri
import dev.ipf.whitenoise.android.share.ShareStreamStaging
import dev.ipf.whitenoise.android.ui.conversation.media.PendingMediaSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ConversationImportedMediaSlotsTest {
    private val owns: (Uri) -> Boolean = { it.authority == "private-share" }

    @Test
    fun mixedShelfRefreshPreservesAnExistingEditedMediaOccurrence() {
        val edited = PendingMediaSlot("edited-preview", Uri.parse("content://private-share/image"))
        val document = Uri.parse("content://private-share/document")
        val recovered =
            restoreImportedComposerAttachments(
                listOf(edited),
                emptyList(),
                ShareStreamStaging(emptyList(), listOf(edited.uri, document)),
                owns,
            )
        assertEquals(listOf(edited), recovered.mediaSlots)
        assertSame(edited, recovered.mediaSlots.single())
        assertEquals(listOf(document), recovered.documentUris)
    }

    @Test
    fun bothRestoreOrdersKeepAllAcceptedSourcesAndRequireExplicitOverflowRemoval() {
        val ordinary =
            RestoredConversationAttachments(
                List(8) { PendingMediaSlot("native-$it", Uri.parse("content://native/$it")) },
                emptyList(),
            )
        val staging = ShareStreamStaging(List(3) { Uri.parse("content://private-share/$it") }, emptyList())
        val firstShelf = restoreImportedComposerAttachments(emptyList(), emptyList(), staging, owns)
        val shelfFirst =
            mergeRestoredComposerAttachments(firstShelf.mediaSlots, firstShelf.documentUris, ordinary, owns)
        val nativeFirst = restoreImportedComposerAttachments(ordinary.mediaSlots, ordinary.documentUris, staging, owns)
        assertEquals(11, shelfFirst.mediaSlots.size)
        assertEquals(shelfFirst.mediaSlots.map { it.uri }.toSet(), nativeFirst.mediaSlots.map { it.uri }.toSet())
        assertTrue(importedComposerExceedsLimit(shelfFirst.mediaSlots, emptyList(), 10, owns))
        assertTrue(importedComposerExceedsLimit(nativeFirst.mediaSlots, emptyList(), 10, owns))
        assertFalse(importedComposerExceedsLimit(nativeFirst.mediaSlots.dropLast(1), emptyList(), 10, owns))
    }

    @Test
    fun aNewDocumentPickCannotTrimAlreadyRecoveredSources() {
        val recovered = List(11) { Uri.parse("content://private-share/$it") }
        assertEquals(recovered, appendRecoveredDocuments(recovered, listOf(Uri.parse("content://native/new")), 10))
        assertFalse(importedComposerExceedsLimit(emptyList(), recovered, 10) { false })
    }

    @Test
    fun revisionsPreserveExistingPreviewIdentityAndOnlyAddNewSources() {
        val camera = PendingMediaSlot("camera", Uri.parse("content://camera/photo"))
        val imported = PendingMediaSlot("prepared-preview", Uri.parse("content://private-share/old"))
        val added = Uri.parse("content://private-share/new")
        val initial = listOf(camera, imported)
        val owns: (Uri) -> Boolean = { it.authority == "private-share" }
        val restored = restoreImportedMediaSlots(initial, listOf(imported.uri, added), 10, owns)
        val repeated = restoreImportedMediaSlots(restored, listOf(imported.uri, added), 10, owns)
        assertSame(camera, restored[0])
        assertSame(imported, restored[1])
        assertEquals(restored, repeated)
        assertSame(restored[2], repeated[2])
        val removed = restoreImportedMediaSlots(repeated, listOf(added), 10, owns)
        assertEquals(listOf(camera, restored[2]), removed)
    }

    @Test
    fun ordinaryRestorePreservesCurrentPrivateSourcesAndCannotResurrectRemovedOnes() {
        val ordinary = PendingMediaSlot("native", Uri.parse("content://native/photo"))
        val imported = PendingMediaSlot("prepared", Uri.parse("content://private-share/photo"))
        val removed = PendingMediaSlot("removed", Uri.parse("content://private-share/removed"))
        val ordinaryDocument = Uri.parse("content://native/document")
        val importedDocument = Uri.parse("content://private-share/document")
        val restored = RestoredConversationAttachments(listOf(ordinary, removed), listOf(ordinaryDocument))
        val merged =
            mergeRestoredComposerAttachments(
                listOf(imported),
                listOf(importedDocument),
                restored,
                owns = { it.authority == "private-share" },
            )
        assertEquals(listOf(ordinary, imported), merged.mediaSlots)
        assertSame(imported, merged.mediaSlots.last())
        assertEquals(listOf(ordinaryDocument, importedDocument), merged.documentUris)
    }
}
