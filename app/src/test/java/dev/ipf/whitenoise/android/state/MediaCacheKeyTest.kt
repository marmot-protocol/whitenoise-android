package dev.ipf.whitenoise.android.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Covers [mediaCacheKey], the composite key for the decrypted media
 * plaintext/thumbnail/disk caches. Every component must contribute to the
 * key: dropping any one of them would let attachments from another account,
 * group, message, or tile index alias each other's decrypted bytes.
 */
class MediaCacheKeyTest {
    @Test
    fun keyJoinsAccountGroupMessageAndAttachmentIndex() {
        assertEquals(
            "alice|group-a|msg-1|2",
            mediaCacheKey("alice", "group-a", "msg-1", 2),
        )
    }

    @Test
    fun everyComponentDisambiguatesTheKey() {
        val base = mediaCacheKey("alice", "group-a", "msg-1", 0)

        assertNotEquals(base, mediaCacheKey("bob", "group-a", "msg-1", 0))
        assertNotEquals(base, mediaCacheKey("alice", "group-b", "msg-1", 0))
        assertNotEquals(base, mediaCacheKey("alice", "group-a", "msg-2", 0))
        assertNotEquals(base, mediaCacheKey("alice", "group-a", "msg-1", 1))
    }

    @Test
    fun caseDifferencesProduceDistinctKeys() {
        // The key is an exact join — a casing drift upstream surfaces as a
        // cache miss, never as a cross-entry collision.
        assertNotEquals(
            mediaCacheKey("alice", "GROUP-A", "msg-1", 0),
            mediaCacheKey("alice", "group-a", "msg-1", 0),
        )
    }
}
