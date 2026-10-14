package dev.ipf.whitenoise.android.ui.conversation

import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.whitenoise.android.core.MessageProjector
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    /**
     * Polls are excluded from multi-select rather than silently dropped from its Copy: the selection map takes only
     * kind-9 chats, and a poll has no batch copy text. Single-message Copy owns poll copying.
     */
    @Test
    fun pollsAreExcludedFromMultiSelectCopy() {
        val noTokens = MarkdownDocumentFfi(truncated = false, blocks = emptyList(), blankLinesBefore = ByteArray(0))
        val poll =
            AppMessageRecordFfi(
                messageIdHex = "poll",
                direction = "received",
                groupIdHex = "group",
                sender = "alice",
                plaintext = "[\"poll\",\"Lunch?\"]",
                contentTokens = noTokens,
                kind = 1068uL,
                tags = emptyList(),
                sourceEpoch = null,
                retentionSeconds = null,
                retentionExpiresAt = null,
                recordedAt = 1uL,
                receivedAt = 1uL,
            )

        val selectable =
            isBatchSelectableMessage(
                messageId = poll.messageIdHex,
                userVisibleMessage = MessageProjector.isChatKind(poll.kind),
                committedMessage = true,
                projectedDeleted = false,
                deletedMessageIds = emptySet(),
            )

        assertFalse(selectable)
        assertNull(MessageProjector.copyableText(poll))
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
