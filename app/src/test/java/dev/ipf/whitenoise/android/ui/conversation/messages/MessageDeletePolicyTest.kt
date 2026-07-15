package dev.ipf.whitenoise.android.ui.conversation.messages

import dev.ipf.whitenoise.android.state.MessageDeleteCapability
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Copy-selection policy for the unified delete dialog: moderation is
 * represented purely by "Delete for everyone" being available on someone
 * else's message plus explanatory copy — never by an admin-branded action.
 */
class MessageDeletePolicyTest {
    @Test
    fun moderatorCopyExplainsRemovingAnotherMembersMessage() {
        assertEquals(
            MessageDeleteSupportingCopy.MODERATOR_REMOVAL,
            messageDeleteSupportingCopy(
                capability = MessageDeleteCapability(canDeleteForMe = true, canDeleteForEveryone = true),
                mine = false,
            ),
        )
    }

    @Test
    fun ownMessageWithBothScopesExplainsTheChoice() {
        assertEquals(
            MessageDeleteSupportingCopy.SCOPE_CHOICE,
            messageDeleteSupportingCopy(
                capability = MessageDeleteCapability(canDeleteForMe = true, canDeleteForEveryone = true),
                mine = true,
            ),
        )
    }

    @Test
    fun localOnlyCapabilityExplainsOthersStillSeeIt() {
        assertEquals(
            MessageDeleteSupportingCopy.LOCAL_ONLY,
            messageDeleteSupportingCopy(
                capability = MessageDeleteCapability(canDeleteForMe = true, canDeleteForEveryone = false),
                mine = false,
            ),
        )
    }
}
