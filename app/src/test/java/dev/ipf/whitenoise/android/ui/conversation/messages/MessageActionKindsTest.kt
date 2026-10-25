package dev.ipf.whitenoise.android.ui.conversation.messages

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
            )
        assertTrue(MessageActionKind.ShowLess !in actions(false))
        assertTrue(MessageActionKind.ShowLess in actions(true))
        assertTrue(MessageActionKind.CopyText in actions(true))
        assertTrue(MessageActionKind.Reply in actions(true))
    }
}
