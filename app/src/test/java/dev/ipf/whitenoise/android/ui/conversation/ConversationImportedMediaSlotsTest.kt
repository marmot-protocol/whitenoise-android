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

    /** A partial current shelf must retain its edited occurrence while the imported URI order is restored. */
    @Test
    fun partialImportedRestorePreservesStagingOrderAndEditedOccurrence() {
        val camera = PendingMediaSlot("camera", Uri.parse("content://camera/photo"))
        val first = Uri.parse("content://private-share/first")
        val second = PendingMediaSlot("edited-second", Uri.parse("content://private-share/second"))
        val restored = restoreImportedMediaSlots(listOf(camera, second), listOf(first, second.uri), 10, owns)
        assertEquals(listOf(camera.uri, first, second.uri), restored.map { it.uri })
        assertSame(camera, restored.first())
        assertSame(second, restored.last())
    }

    /** A stale native projection cannot discard newly picked occurrences or replace their current source URI. */
    @Test
    fun lateNativeRestorePreservesNewOrdinaryPicksAndCurrentOccurrences() {
        val old = PendingMediaSlot("saved", Uri.parse("content://native/old"))
        val current = PendingMediaSlot(old.id, Uri.parse("content://camera/current"))
        val picked = PendingMediaSlot("picked", Uri.parse("content://picker/photo"))
        val duplicate = PendingMediaSlot("picked-again", picked.uri)
        val imported = PendingMediaSlot("private", Uri.parse("content://private-share/photo"))
        val materialized = PendingMediaSlot(imported.id, Uri.parse("content://native/materialized-private"))
        val oldDocument = Uri.parse("content://native/document")
        val newDocument = Uri.parse("content://picker/document")
        val privateDocument = Uri.parse("content://private-share/document")
        val merged =
            mergeRestoredComposerAttachments(
                listOf(current, picked, duplicate, imported),
                listOf(newDocument, privateDocument),
                RestoredConversationAttachments(listOf(old, materialized), listOf(oldDocument)),
                owns,
            )
        assertEquals(listOf(current, picked, duplicate, imported), merged.mediaSlots)
        assertSame(current, merged.mediaSlots.first())
        assertSame(picked, merged.mediaSlots[1])
        assertSame(duplicate, merged.mediaSlots[2])
        assertEquals(listOf(oldDocument, newDocument, privateDocument), merged.documentUris)
    }

    /** Restores mixed content with a media URI on the document shelf and preserves its existing edited slot. */
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

    /** Checks eleven restored sources in both hydration orders; sending resumes only after explicit removal. */
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

    /** Appends to an already overflowing recovered shelf without truncating its accepted originals. */
    @Test
    fun aNewDocumentPickCannotTrimAlreadyRecoveredSources() {
        val recovered = List(11) { Uri.parse("content://private-share/$it") }
        assertEquals(recovered, appendRecoveredDocuments(recovered, listOf(Uri.parse("content://native/new")), 10))
        assertFalse(importedComposerExceedsLimit(emptyList(), recovered, 10) { false })
    }

    /** Repeats shelf revisions and removal while checking slot object identity, including the camera item. */
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

    /** Feeds a stale native snapshot containing a removed private URI; current private ownership wins. */
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
