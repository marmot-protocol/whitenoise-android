package dev.ipf.whitenoise.android.ui.conversation

import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageBatchSelectionActionsTest {
    @Test
    fun forwardDisabledWhenAnySelectedMessageIsNotForwardable() {
        val items =
            listOf(
                item("m1", forwardable = "one"),
                item("m2", forwardable = null),
            )

        val availability = batchSelectionActionAvailability(items, composerGate = ComposerGate.COMPOSER)

        assertFalse(availability.canForward)
        assertTrue(availability.canDelete)
    }

    @Test
    fun copyDisabledWhenAnySelectedMessageHasNoCopyableText() {
        val items =
            listOf(
                item("m1"),
                item("m2", copyable = null),
            )

        val availability = batchSelectionActionAvailability(items, composerGate = ComposerGate.COMPOSER)

        assertFalse(availability.canCopy)
    }

    @Test
    fun saveDisabledWhenAnySelectedMessageHasNoMedia() {
        val items =
            listOf(
                item("m1", saveable = true),
                item("m2", saveable = false),
            )

        val availability = batchSelectionActionAvailability(items, composerGate = ComposerGate.COMPOSER)

        assertFalse(availability.canSave)
    }

    @Test
    fun replyAndInfoOnlyForSingleSelection() {
        val single = batchSelectionActionAvailability(listOf(item("m1")), composerGate = ComposerGate.COMPOSER)
        assertTrue(single.canReply)
        assertTrue(single.canInfo)

        val multi =
            batchSelectionActionAvailability(
                listOf(item("m1"), item("m2")),
                composerGate = ComposerGate.COMPOSER,
            )
        assertFalse(multi.canReply)
        assertFalse(multi.canInfo)
    }

    @Test
    fun replyDisabledUnlessComposerGateIsComposer() {
        val items = listOf(item("m1"))
        assertTrue(batchSelectionActionAvailability(items, composerGate = ComposerGate.COMPOSER).canReply)

        ComposerGate.entries
            .filter { it != ComposerGate.COMPOSER }
            .forEach { gate ->
                val availability = batchSelectionActionAvailability(items, composerGate = gate)
                assertFalse("reply must be disabled for $gate", availability.canReply)
                assertTrue(availability.canInfo)
            }
    }

    @Test
    fun actionRowsWrapAndKeepDeleteOnItsOwnRowWhenNeeded() {
        val offered =
            listOf(
                MessageSelectionBarAction.Copy,
                MessageSelectionBarAction.Forward,
                MessageSelectionBarAction.Reply,
                MessageSelectionBarAction.Info,
                MessageSelectionBarAction.Save,
            )

        val rows = messageSelectionBarActionRows(offered = offered, maxActionsPerRow = 4)

        assertEquals(2, rows.size)
        assertEquals(offered.take(4), rows[0].actions)
        assertFalse(rows[0].includesDelete)
        assertEquals(listOf(MessageSelectionBarAction.Save), rows[1].actions)
        assertTrue(rows[1].includesDelete)
    }

    @Test
    fun actionRowsKeepDeleteOnSameRowWhenThereIsRoom() {
        val offered =
            listOf(
                MessageSelectionBarAction.Copy,
                MessageSelectionBarAction.Forward,
            )

        val rows = messageSelectionBarActionRows(offered = offered, maxActionsPerRow = 3)

        assertEquals(1, rows.size)
        assertEquals(offered, rows[0].actions)
        assertTrue(rows[0].includesDelete)
    }

    private fun item(
        id: String,
        copyable: String? = id,
        forwardable: String? = id,
        saveable: Boolean = false,
    ): BatchMessageActionItem =
        BatchMessageActionItem(
            messageId = id,
            senderId = "alice",
            senderDisplayName = "Alice",
            copyableText = copyable,
            forwardableText = forwardable,
            canDeleteForEveryone = false,
            hasSaveableMedia = saveable,
        )
}
