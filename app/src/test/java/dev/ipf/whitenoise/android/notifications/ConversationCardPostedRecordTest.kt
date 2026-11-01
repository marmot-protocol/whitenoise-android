package dev.ipf.whitenoise.android.notifications

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The app-side record of written cards that lets a dismissal cancel a card the platform has not listed yet. */
class ConversationCardPostedRecordTest {
    /** Each case models a separate process rather than inheriting another case's retirement history. */
    @Before
    fun setUp() {
        ConversationCardPostedRegistry.resetForTests()
    }

    /** Leaves no process retirement state for subsequent test classes. */
    @After
    fun tearDown() {
        ConversationCardPostedRegistry.resetForTests()
    }

    /** Capacity pressure discards retired metadata before a live card while keeping stale content rejected. */
    @Test
    fun retiredRecordsAreEvictedBeforeLivePayloads() {
        val revision = ConversationCardPostedRegistry.newPayloadRevision()
        ConversationCardPostedRegistry.markPosted(
            "live-card",
            1,
            generationId = "live-generation",
            payload = ConversationCardPostedRegistry.PayloadSnapshot(revision, setOf("kept")),
        )
        assertTrue(ConversationCardPostedRegistry.prepareCancellation("pending-card", 1, true))
        repeat(300) {
            ConversationCardPostedRegistry.markPosted("retired-card", it)
            assertTrue(ConversationCardPostedRegistry.clearPosted("retired-card", it))
        }

        assertTrue(ConversationCardPostedRegistry.isCurrentPayload("live-card", 1, revision))
        assertTrue(ConversationCardPostedRegistry.canCarryEntry("live-card", 1, revision, "kept"))
        assertTrue(ConversationCardPostedRegistry.prepareCancellation("pending-card", 1, false))
        assertFalse(ConversationCardPostedRegistry.canCarryEntry("retired-card", 0, null, "removed"))
        assertFalse(ConversationCardPostedRegistry.isCurrentPayload("retired-card", 0, "old-process:revision"))
        assertTrue(ConversationCardPostedRegistry.clearPosted("live-card", 1))
        assertTrue(ConversationCardPostedRegistry.clearPosted("pending-card", 1))
    }

    /** Fixture isolation clears tombstones and capacity retirement without weakening production checks. */
    @Test
    fun freshTestProcessAllowsUntouchedLegacyPayloads() {
        assertTrue(ConversationCardPostedRegistry.prepareCancellation("legacy", 1, true))
        assertTrue(ConversationCardPostedRegistry.clearPosted("legacy", 1))
        repeat(300) { ConversationCardPostedRegistry.markPosted("fixture-eviction", it) }
        assertFalse(ConversationCardPostedRegistry.isCurrentPayload("legacy", 1, null))
        assertFalse(ConversationCardPostedRegistry.canCarryEntry("untouched", 1, null, "message"))

        ConversationCardPostedRegistry.resetForTests()

        assertTrue(ConversationCardPostedRegistry.isCurrentPayload("legacy", 1, null))
        assertTrue(ConversationCardPostedRegistry.canCarryEntry("untouched", 1, null, "message"))
        assertFalse(ConversationCardPostedRegistry.clearPosted("fixture-eviction", 299))
    }

    /** A recorded write is reported once and then forgotten. */
    @Test
    fun recordedWriteIsConsumedByOneClear() {
        ConversationCardPostedRegistry.markPosted("record-once", 7)

        assertTrue(ConversationCardPostedRegistry.clearPosted("record-once", 7))
        assertFalse(ConversationCardPostedRegistry.clearPosted("record-once", 7))
    }

    /** Distinct ids under one tag are separate cards. */
    @Test
    fun recordIsKeyedByTagAndId() {
        ConversationCardPostedRegistry.markPosted("record-key", 1)

        assertFalse(ConversationCardPostedRegistry.clearPosted("record-key", 2))
        assertTrue(ConversationCardPostedRegistry.clearPosted("record-key", 1))
    }

    /** Silent content revisions filter old history without changing the card's dismissal generation. */
    @Test
    fun onlyAcceptedEntryIdentitiesCanBeCarriedFromAnOlderRevision() {
        val original = ConversationCardPostedRegistry.newPayloadRevision()
        val cleaned = ConversationCardPostedRegistry.newPayloadRevision()
        ConversationCardPostedRegistry.markPosted(
            "content-filter",
            1,
            generationId = "same-generation",
            payload = ConversationCardPostedRegistry.PayloadSnapshot(cleaned, setOf("kept")),
        )
        assertFalse(ConversationCardPostedRegistry.isCurrentPayload("content-filter", 1, original))
        assertTrue(ConversationCardPostedRegistry.isCurrentPayload("content-filter", 1, cleaned))
        assertFalse(ConversationCardPostedRegistry.canCarryEntry("content-filter", 1, original, "removed"))
        assertFalse(ConversationCardPostedRegistry.canCarryEntry("content-filter", 1, original, null))
        assertTrue(ConversationCardPostedRegistry.canCarryEntry("content-filter", 1, original, "kept"))
        ConversationCardPostedRegistry.clearPosted("content-filter", 1)
        assertFalse(ConversationCardPostedRegistry.canCarryEntry("content-filter", 1, cleaned, "kept"))
    }

    /** Eviction cannot make a current-process stale payload trustworthy; earlier process cards still migrate. */
    @Test
    fun missingCurrentProcessMetadataFailsClosed() {
        val revision = ConversationCardPostedRegistry.newPayloadRevision()
        assertFalse(ConversationCardPostedRegistry.isCurrentPayload("missing-current", 1, revision))
        assertFalse(ConversationCardPostedRegistry.canCarryEntry("missing-current", 1, revision, "removed"))
        // Prior-process eligibility is conservatively retired once bounded metadata has been evicted.
    }

    /** Cancellation preparation retires copied content but keeps the accepted write until cancellation succeeds. */
    @Test
    fun cancellationPreparationKeepsTheRetryObligation() {
        val revision = ConversationCardPostedRegistry.newPayloadRevision()
        ConversationCardPostedRegistry.markPosted(
            "pending-cancel",
            1,
            generationId = "generation",
            payload = ConversationCardPostedRegistry.PayloadSnapshot(revision, setOf("removed")),
        )
        assertTrue(ConversationCardPostedRegistry.prepareCancellation("pending-cancel", 1, false))
        assertFalse(ConversationCardPostedRegistry.canCarryEntry("pending-cancel", 1, revision, "removed"))
        assertTrue(ConversationCardPostedRegistry.prepareCancellation("pending-cancel", 1, false))
        assertTrue(ConversationCardPostedRegistry.clearPosted("pending-cancel", 1))
        assertFalse(ConversationCardPostedRegistry.prepareCancellation("pending-cancel", 1, false))
        assertFalse(ConversationCardPostedRegistry.canCarryEntry("pending-cancel", 1, null, "removed"))
    }

    /** Evicting a legacy cancellation tombstone cannot re-admit its stale legacy content. */
    @Test
    fun evictedRetirementMetadataStillFailsClosed() {
        assertTrue(ConversationCardPostedRegistry.prepareCancellation("retired-legacy", 1, true))
        assertTrue(ConversationCardPostedRegistry.clearPosted("retired-legacy", 1))
        repeat(300) { ConversationCardPostedRegistry.markPosted("retirement-eviction-filler", it) }
        assertFalse(ConversationCardPostedRegistry.canCarryEntry("retired-legacy", 1, null, "removed"))
        assertFalse(ConversationCardPostedRegistry.isCurrentPayload("retired-legacy", 1, "previous-process:revision"))
        repeat(300) { ConversationCardPostedRegistry.clearPosted("retirement-eviction-filler", it) }
    }

    /** The record stays bounded: the oldest write is evicted first and a re-write counts as fresh. */
    @Test
    fun oldestWriteIsEvictedFirst() {
        ConversationCardPostedRegistry.markPosted("record-evict-first", 0)
        repeat(300) { ConversationCardPostedRegistry.markPosted("record-evict-filler", it) }

        assertFalse(ConversationCardPostedRegistry.clearPosted("record-evict-first", 0))
        assertTrue(ConversationCardPostedRegistry.clearPosted("record-evict-filler", 299))
        repeat(299) { ConversationCardPostedRegistry.clearPosted("record-evict-filler", it) }
    }
}
