package dev.ipf.whitenoise.android.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OutgoingMessageLimitTest {
    /** Text of exactly the limit is sent, one character more is not. */
    @Test
    fun theLimitItselfIsAllowed() {
        assertFalse(exceedsOutgoingMessageLimit("a".repeat(MAX_OUTGOING_MESSAGE_CHARACTERS)))
        assertTrue(exceedsOutgoingMessageLimit("a".repeat(MAX_OUTGOING_MESSAGE_CHARACTERS + 1)))
    }

    /** An emoji with a skin tone spans four UTF-16 units but counts as one character. */
    @Test
    fun emojiSequencesCountOnce() {
        val emoji = "👍🏽"
        assertFalse(exceedsOutgoingMessageLimit(emoji.repeat(MAX_OUTGOING_MESSAGE_CHARACTERS)))
        assertTrue(exceedsOutgoingMessageLimit(emoji.repeat(MAX_OUTGOING_MESSAGE_CHARACTERS + 1)))
    }
}
