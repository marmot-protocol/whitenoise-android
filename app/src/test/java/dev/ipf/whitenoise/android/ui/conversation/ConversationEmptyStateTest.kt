package dev.ipf.whitenoise.android.ui.conversation

import org.junit.Assert.assertEquals
import org.junit.Test

/** What an empty conversation is allowed to say about itself (#2674). */
class ConversationEmptyStateTest {
    /** Without a retention policy the ordinary wording stands. */
    @Test
    fun noRetentionKeepsTheOrdinaryEmptyState() {
        assertEquals(ConversationEmptyState.NoMessages, conversationEmptyState(null))
    }

    /** A live policy explains itself, carrying the duration the conversation actually uses. */
    @Test
    fun aLivePolicyCarriesItsOwnDuration() {
        assertEquals(
            ConversationEmptyState.DisappearingMessages(retentionSeconds = 3_600L),
            conversationEmptyState(3_600uL),
        )
    }

    /**
     * The engine writes zero for "retention off". Reading that as a policy would tell the reader
     * their messages vanish instantly, which is both wrong and alarming.
     */
    @Test
    fun zeroIsRetentionOffRatherThanAnInstantTimer() {
        assertEquals(ConversationEmptyState.NoMessages, conversationEmptyState(0uL))
    }

    /** Turning a policy on or off changes the wording with it, without any other input. */
    @Test
    fun theWordingFollowsThePolicyAsItChanges() {
        assertEquals(ConversationEmptyState.NoMessages, conversationEmptyState(0uL))
        assertEquals(
            ConversationEmptyState.DisappearingMessages(retentionSeconds = 86_400L),
            conversationEmptyState(86_400uL),
        )
        assertEquals(ConversationEmptyState.NoMessages, conversationEmptyState(0uL))
    }
}
