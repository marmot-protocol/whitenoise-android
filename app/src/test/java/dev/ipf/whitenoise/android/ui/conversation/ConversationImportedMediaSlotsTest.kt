package dev.ipf.whitenoise.android.ui.conversation

import android.net.Uri
import dev.ipf.whitenoise.android.ui.conversation.media.PendingMediaSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ConversationImportedMediaSlotsTest {
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
