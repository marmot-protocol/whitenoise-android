package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.ConversationWindowRevisionFfi
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Recovery is driven by new native revisions or touch intent, never by delayed echoes or layout. */
class ConversationOlderRecoveryGuardTest {
    /** An already queued anchor/page echo cannot restart the same failed automatic request. */
    @Test
    fun equalOlderAndForeignRevisionsDoNotReleaseTheGuard() {
        val guard = AutomaticPagingGuard(CONVERSATION_AUTOMATIC_OLDER_PAGE_ATTEMPTS)
        guard.recordFailure(ConversationWindowRevisionFfi("active", 8uL))

        listOf(
            ConversationWindowRevisionFfi("active", 8uL),
            ConversationWindowRevisionFfi("active", 7uL),
            ConversationWindowRevisionFfi("retired", 99uL),
        ).forEach {
            guard.onWindowApplied(it)
            assertTrue(guard.blocked)
        }
        guard.onWindowApplied(ConversationWindowRevisionFfi("active", 9uL))
        assertFalse(guard.blocked)
    }

    /** A replacement received before the error reply counts as recovery too. */
    @Test
    fun alreadyCommittedRecoveryReleasesTheRequestedRevision() {
        val guard = AutomaticPagingGuard(CONVERSATION_AUTOMATIC_OLDER_PAGE_ATTEMPTS)
        guard.recordFailure(ConversationWindowRevisionFfi("active", 8uL), waitForReplacement = true)
        guard.onWindowApplied(ConversationWindowRevisionFfi("active", 9uL))
        assertFalse(guard.blocked)
    }

    /** MDK not-ready/deadline work may still complete, so dragging cannot duplicate the request. */
    @Test
    fun gestureWaitsForNativeRecoveryButCanRetryAnAnsweredNoProgressPage() {
        val guard = AutomaticPagingGuard(CONVERSATION_AUTOMATIC_OLDER_PAGE_ATTEMPTS)
        val revision = ConversationWindowRevisionFfi("active", 8uL)
        guard.recordFailure(revision, waitForReplacement = true)
        repeat(5) { guard.onUserGestureStarted() }
        assertTrue(guard.blocked)
        guard.onWindowApplied(revision.copy(sequence = 9uL))
        assertFalse(guard.blocked)

        guard.recordFailure(revision.copy(sequence = 9uL))
        guard.onUserGestureStarted()
        assertFalse(guard.blocked)
    }
}
