package dev.ipf.whitenoise.android.notifications

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The app-side record of written cards that lets a dismissal cancel a card the platform has not listed yet. */
class ConversationCardPostedRecordTest {
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
