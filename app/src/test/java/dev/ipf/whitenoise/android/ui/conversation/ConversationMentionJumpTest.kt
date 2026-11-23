package dev.ipf.whitenoise.android.ui.conversation

import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.whitenoise.android.state.MessageStatus
import dev.ipf.whitenoise.android.state.TimelineMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Entry-seeded mention availability (#4030) and the bounded search for a mention outside the loaded window. */
class ConversationMentionJumpTest {
    @Test
    fun entryCountShowsBeforeTheTimelineAnchors() {
        val seed = ConversationMentionJumpSeed(entryCount = 2)

        assertEquals(2, seed.count(emptyList(), ConversationMentionCoverage.PENDING))
        assertTrue(seed.active)
    }

    @Test
    fun noEntryMentionsShowNothingAndNeverResolve() {
        val seed = ConversationMentionJumpSeed(entryCount = 0)

        assertEquals(0, seed.count(emptyList(), ConversationMentionCoverage.PENDING))
        assertEquals(0, seed.count(emptyList(), ConversationMentionCoverage.PARTIAL))
        assertFalse(seed.active)
    }

    @Test
    fun loadedRowsReachingTheNewestRowReplaceTheEntryCount() {
        val seed = ConversationMentionJumpSeed(entryCount = 3)

        // The read anchor already passed the mentions: the full unread range is loaded and holds none.
        assertEquals(0, seed.count(emptyList(), ConversationMentionCoverage.COMPLETE))
        seed.observe(emptyList(), ConversationMentionCoverage.COMPLETE, anchorLoaded = true) { true }
        assertFalse("a covered range retires the entry count", seed.active)
        // A later window that no longer reaches the newest row must not bring the stale count back.
        assertEquals(0, seed.count(emptyList(), ConversationMentionCoverage.PARTIAL))
    }

    @Test
    fun aMentionReadBehindTheAnchorLeavesTheCount() {
        val seed = ConversationMentionJumpSeed(entryCount = 3)
        val loaded = setOf("anchor", "m1")

        seed.observe(listOf("m1"), ConversationMentionCoverage.PARTIAL, anchorLoaded = true) { it in loaded }
        assertEquals(3, seed.count(listOf("m1"), ConversationMentionCoverage.PARTIAL))
        // The anchor moved past m1 while it stayed loaded: m1 was read, two mentions remain beyond the window.
        seed.observe(emptyList(), ConversationMentionCoverage.PARTIAL, anchorLoaded = true) { it in loaded }
        assertEquals(2, seed.count(emptyList(), ConversationMentionCoverage.PARTIAL))
        seed.visited("m2")
        assertEquals(1, seed.count(emptyList(), ConversationMentionCoverage.PARTIAL))
    }

    @Test
    fun pagingTheAnchorOrAMentionOutOfTheWindowIsNotReading() {
        val seed = ConversationMentionJumpSeed(entryCount = 1)

        seed.observe(listOf("m1"), ConversationMentionCoverage.PARTIAL, anchorLoaded = true) { true }
        // Paging evicted the anchor: no loaded targets, the unread mention still counts.
        seed.observe(emptyList(), ConversationMentionCoverage.PARTIAL, anchorLoaded = false) { false }
        assertEquals(1, seed.count(emptyList(), ConversationMentionCoverage.PARTIAL))
        // The anchor is back but m1 itself is paged out: still unread.
        seed.observe(emptyList(), ConversationMentionCoverage.PARTIAL, anchorLoaded = true) { it == "anchor" }
        assertEquals(1, seed.count(emptyList(), ConversationMentionCoverage.PARTIAL))
        assertTrue(seed.active)
    }

    @Test
    fun retiredSeedFollowsTheLoadedRowsOnly() {
        val seed = ConversationMentionJumpSeed(entryCount = 4)
        seed.retire()

        assertEquals(1, seed.count(listOf("m1"), ConversationMentionCoverage.PENDING))
        assertEquals(0, seed.count(emptyList(), ConversationMentionCoverage.PARTIAL))
    }

    @Test
    fun aFirstFrameTapWaitsForAnchoringThenUsesTheLoadedMention() =
        runBlocking {
            val seed = ConversationMentionJumpSeed(entryCount = 2)
            val anchored = CompletableDeferred<Unit>()
            var loaded: String? = null
            var resolved = 0
            val tap =
                async(start = CoroutineStart.UNDISPATCHED) {
                    seed.jumpTarget({ anchored.await() }, { loaded }) {
                        resolved++
                        "far"
                    }
                }
            assertFalse("the tap is held, not dropped, until entry positioning", tap.isCompleted)
            loaded = "m1"
            anchored.complete(Unit)
            assertEquals("m1", tap.await())
            assertEquals(0, resolved)
        }

    @Test
    fun aTapWithoutALoadedMentionSearchesOnlyWhileTheSeedIsActive() =
        runBlocking {
            val seed = ConversationMentionJumpSeed(entryCount = 1)
            assertEquals("far", seed.jumpTarget({}, { null }) { "far" })
            seed.retire()
            assertNull(seed.jumpTarget({}, { null }) { error("a retired seed must not search") })
        }

    @Test
    fun coverageNeedsTheAnchorLoadedAndTheNewestEdge() {
        val rows = listOf(message("a"), message("b"))

        assertEquals(
            ConversationMentionCoverage.PENDING,
            conversationMentionCoverage(anchored = false, "a", rows, hasMoreAfter = false),
        )
        assertEquals(
            ConversationMentionCoverage.PENDING,
            conversationMentionCoverage(anchored = true, null, rows, hasMoreAfter = false),
        )
        assertEquals(
            ConversationMentionCoverage.PARTIAL,
            conversationMentionCoverage(anchored = true, "a", rows, hasMoreAfter = true),
        )
        assertEquals(
            ConversationMentionCoverage.PARTIAL,
            conversationMentionCoverage(anchored = true, "z", rows, hasMoreAfter = false),
        )
        assertEquals(
            ConversationMentionCoverage.COMPLETE,
            conversationMentionCoverage(anchored = true, "a", rows, hasMoreAfter = false),
        )
    }

    @Test
    fun resolvesALoadedMentionAfterTheAnchorWithoutPaging() {
        val history = PagedHistory(listOf("r", "m0@", "u1", "m1@"), window = 4, start = 0)

        assertEquals("m1@", history.resolve(readAnchor = "m0@"))
        assertEquals(0, history.newerLoads)
    }

    @Test
    fun pagesNewerToAMentionOutsideTheFirstPageAsTheWindowSlides() {
        val ids = listOf("r") + (1..10).map { "u$it" } + "m@" + listOf("u11")
        val history = PagedHistory(ids, window = 4, start = 0)

        assertEquals("m@", history.resolve(readAnchor = "r"))
        assertTrue("the anchor slid out of the window", history.window().none { it.record.messageIdHex == "r" })
        assertEquals(4, history.newerLoads)
    }

    @Test
    fun loadsTheAnchorWhenTheWindowStartsAfterIt() {
        val history = PagedHistory(listOf("old@", "r", "u1", "m@", "u2", "u3", "u4"), window = 3, start = 4)

        assertEquals("m@", history.resolve(readAnchor = "r"))
        assertEquals(listOf("r"), history.loadedMessages)
    }

    @Test
    fun withoutAReadAnchorStartsAtTheEntryFirstUnreadInclusive() {
        val history = PagedHistory(listOf("old@", "f@", "u1"), window = 3, start = 0)

        assertEquals("f@", history.resolve(readAnchor = null, firstUnread = "f@"))
    }

    @Test
    fun stopsAtThePageBoundWhenNoMentionExists() {
        val ids = listOf("r") + (1..100).map { "u$it" }
        val history = PagedHistory(ids, window = 4, start = 0)

        assertNull(history.resolve(readAnchor = "r", maxNewerPages = 5))
        assertEquals(5, history.newerLoads)
    }

    @Test
    fun noStartRowOrUnloadableAnchorResolvesNothing() {
        val history = PagedHistory(listOf("a", "m@"), window = 2, start = 0)

        assertNull(history.resolve(readAnchor = null, firstUnread = null))
        assertNull(history.resolve(readAnchor = "gone"))
    }

    /** A sliding window over [ids]; ids ending in "@" mention the active account. */
    private class PagedHistory(
        private val ids: List<String>,
        private val window: Int,
        private var start: Int,
    ) {
        var newerLoads = 0
        val loadedMessages = mutableListOf<String>()

        fun window(): List<TimelineMessage> = ids.drop(start).take(window).map(::message)

        fun resolve(
            readAnchor: String?,
            firstUnread: String? = null,
            maxNewerPages: Int = MENTION_RESOLUTION_MAX_NEWER_PAGES,
        ): String? =
            runBlocking {
                resolveFirstUnreadMention(
                    readAnchorMessageId = readAnchor,
                    firstUnreadMessageId = firstUnread,
                    timeline = ::window,
                    mentionsActiveAccount = { it.record.messageIdHex.endsWith("@") },
                    loadMessage = { id ->
                        loadedMessages += id
                        val index = ids.indexOf(id)
                        if (index >= 0) start = index
                        index >= 0
                    },
                    hasMoreAfter = { start + window < ids.size },
                    loadNewer = {
                        newerLoads++
                        start += window / 2
                        true
                    },
                    maxNewerPages = maxNewerPages,
                )
            }
    }

    private companion object {
        fun message(id: String): TimelineMessage =
            TimelineMessage(
                id = "msg:$id",
                record =
                    AppMessageRecordFfi(
                        invalidated = false,
                        messageIdHex = id,
                        direction = "received",
                        groupIdHex = "group",
                        sender = "peer",
                        plaintext = "body-$id",
                        contentTokens =
                            MarkdownDocumentFfi(
                                truncated = false,
                                blocks = emptyList(),
                                blankLinesBefore = ByteArray(0),
                            ),
                        kind = 9uL,
                        tags = emptyList(),
                        sourceEpoch = null,
                        retentionSeconds = null,
                        retentionExpiresAt = null,
                        recordedAt = 1uL,
                        receivedAt = 1uL,
                        sticker = null,
                    ),
                status = MessageStatus.Received,
            )
    }
}
