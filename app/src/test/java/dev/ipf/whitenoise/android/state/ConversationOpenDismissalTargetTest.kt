package dev.ipf.whitenoise.android.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConversationOpenDismissalTargetTest {
    @Test
    fun returnsTargetWhenOpeningNonblankGroupUnderNonblankAccount() {
        assertEquals(
            ConversationNotificationTarget("acct-a", "group-1"),
            conversationOpenDismissalTarget("acct-a", "group-1"),
        )
    }

    @Test
    fun noTargetWhenGroupIsNullBlankOrAccountMissing() {
        assertNull(conversationOpenDismissalTarget("acct-a", null))
        assertNull(conversationOpenDismissalTarget("acct-a", "   "))
        assertNull(conversationOpenDismissalTarget(null, "group-1"))
        assertNull(conversationOpenDismissalTarget("   ", "group-1"))
    }
}
