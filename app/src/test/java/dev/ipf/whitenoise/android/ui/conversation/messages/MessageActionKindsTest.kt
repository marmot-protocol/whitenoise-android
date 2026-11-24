package dev.ipf.whitenoise.android.ui.conversation.messages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageActionKindsTest {
    /** Collapse is opt-in and remains beside the other reading actions without removing Copy or Reply. */
    @Test
    fun showLessIsPresentOnlyForAnEligibleExpandedBody() {
        fun actions(eligible: Boolean) =
            messageActionKinds(
                canReply = true,
                canEdit = false,
                canSelect = false,
                canSelectText = false,
                canCopyText = true,
                canSpeak = false,
                canForward = false,
                canSave = false,
                canShowLess = eligible,
                canTranscribe = eligible,
            )
        assertTrue(MessageActionKind.ShowLess !in actions(false))
        assertTrue(MessageActionKind.ShowLess in actions(true))
        assertTrue(MessageActionKind.CopyText in actions(true))
        assertTrue(MessageActionKind.Reply in actions(true))
        assertTrue(MessageActionKind.Transcribe !in actions(false))
        assertTrue(MessageActionKind.Transcribe in actions(true))
    }

    /** The first level leads with the common actions; the rest keep their order behind More actions (#4077). */
    @Test
    fun commonActionsLeadAndTheRestWaitBehindMoreActions() {
        val sentLongText =
            messageActionMenuOrder(
                messageActionKinds(
                    canReply = true,
                    canEdit = true,
                    canSelect = true,
                    canSelectText = true,
                    canCopyText = true,
                    canSpeak = true,
                    canTranslate = true,
                    canForward = true,
                    canKeepOnScreen = true,
                    canShowLess = true,
                    canSave = false,
                ),
            )
        assertEquals(
            listOf(
                MessageActionKind.KeepOnScreen,
                MessageActionKind.ShowLess,
                MessageActionKind.Reply,
                MessageActionKind.Edit,
                MessageActionKind.CopyText,
                MessageActionKind.Forward,
            ),
            sentLongText.filter(::isFirstLevelMessageAction),
        )
        assertEquals(
            listOf(
                MessageActionKind.SelectText,
                MessageActionKind.Translate,
                MessageActionKind.Speak,
                MessageActionKind.Select,
                MessageActionKind.Info,
            ),
            sentLongText.filterNot(::isFirstLevelMessageAction),
        )
        assertFalse("Delete waits behind More actions", isFirstLevelMessageAction(null))
    }

    /** Received media keeps Reply, Forward and Save on the first level and its sharing behind More actions. */
    @Test
    fun receivedMediaKeepsForwardAndSaveOnTheFirstLevel() {
        val receivedMedia =
            messageActionMenuOrder(
                messageActionKinds(
                    canReply = true,
                    canEdit = false,
                    canSelect = true,
                    canSelectText = false,
                    canCopyText = false,
                    canSpeak = false,
                    canForward = true,
                    canShare = true,
                    canSave = true,
                    canReport = true,
                ),
            )
        assertEquals(
            listOf(MessageActionKind.Reply, MessageActionKind.Forward, MessageActionKind.Save),
            receivedMedia.filter(::isFirstLevelMessageAction),
        )
        assertTrue(hasMoreMessageActions(receivedMedia + null, ::isFirstLevelMessageAction))
        assertEquals(
            receivedMedia.filter(::isFirstLevelMessageAction),
            visibleMessageActions(receivedMedia + null, ::isFirstLevelMessageAction, showAll = false),
        )
        assertEquals(
            receivedMedia + null,
            visibleMessageActions(receivedMedia + null, ::isFirstLevelMessageAction, showAll = true),
        )
    }
}
