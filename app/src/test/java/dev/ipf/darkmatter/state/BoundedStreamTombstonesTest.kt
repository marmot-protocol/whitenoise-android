package dev.ipf.darkmatter.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedStreamTombstonesTest {
    @Test
    fun marksAndSuppressesRecentlyRemovedStreams() {
        val tombstones = BoundedStreamTombstones(maxEntries = 4)
        tombstones.add("a")
        tombstones.add("b")

        assertTrue("a" in tombstones)
        assertTrue("b" in tombstones)
        assertFalse("c" in tombstones)
    }

    @Test
    fun removeDropsSingleTombstoneForReAddEdgeCase() {
        val tombstones = BoundedStreamTombstones(maxEntries = 4)
        tombstones.add("stream")
        assertTrue("stream" in tombstones)

        tombstones.remove("stream")
        assertFalse("stream" in tombstones)
    }

    @Test
    fun openingManyStreamsKeepsSetBounded() {
        val cap = 8
        val tombstones = BoundedStreamTombstones(maxEntries = cap)

        // Simulate a long agent-heavy session: far more finalized streams than
        // the cap. The set must never exceed the cap.
        repeat(10_000) { i ->
            tombstones.add("stream-$i")
            assertTrue("set must never exceed cap", tombstones.size() <= cap)
        }

        assertEquals(cap, tombstones.size())
    }

    @Test
    fun evictsOldestTombstonesPastCapWhileKeepingRecentOnes() {
        val cap = 3
        val tombstones = BoundedStreamTombstones(maxEntries = cap)

        tombstones.add("s1")
        tombstones.add("s2")
        tombstones.add("s3")
        // s4 evicts the least-recently-used tombstone (s1).
        tombstones.add("s4")

        assertFalse("oldest tombstone should be evicted", "s1" in tombstones)
        assertTrue("s2" in tombstones)
        assertTrue("s3" in tombstones)
        assertTrue("recently removed stream still suppressed", "s4" in tombstones)
        assertEquals(cap, tombstones.size())
    }

    @Test
    fun lookupPromotesTombstoneSoActivelyCheckedStreamSurvivesEviction() {
        val cap = 3
        val tombstones = BoundedStreamTombstones(maxEntries = cap)

        tombstones.add("s1")
        tombstones.add("s2")
        tombstones.add("s3")

        // A late event for s1 checks the tombstone, promoting it to MRU.
        assertTrue("s1" in tombstones)

        // Next insertion now evicts s2 (the LRU), not the freshly-checked s1.
        tombstones.add("s4")

        assertTrue("checked tombstone survives eviction", "s1" in tombstones)
        assertFalse("s2" in tombstones)
        assertTrue("s3" in tombstones)
        assertTrue("s4" in tombstones)
    }

    @Test
    fun reAddPromotesExistingTombstoneWithoutGrowing() {
        val cap = 3
        val tombstones = BoundedStreamTombstones(maxEntries = cap)

        tombstones.add("s1")
        tombstones.add("s2")
        tombstones.add("s3")
        // Re-marking s1 must not grow the set; it promotes the existing entry.
        tombstones.add("s1")

        assertEquals(cap, tombstones.size())

        // s2 is now the LRU and is evicted next.
        tombstones.add("s4")
        assertTrue("s1" in tombstones)
        assertFalse("s2" in tombstones)
    }

    @Test
    fun clearDropsAllTombstones() {
        val tombstones = BoundedStreamTombstones(maxEntries = 4)
        tombstones.add("a")
        tombstones.add("b")

        tombstones.clear()

        assertEquals(0, tombstones.size())
        assertFalse("a" in tombstones)
    }

    @Test
    fun rejectsNonPositiveCap() {
        assertThrows(IllegalArgumentException::class.java) {
            BoundedStreamTombstones(maxEntries = 0)
        }
    }
}
