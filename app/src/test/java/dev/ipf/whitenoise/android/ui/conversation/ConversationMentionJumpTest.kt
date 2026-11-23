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
        val seed = ConversationMentionJumpSeed(entryCount = 3, entryLastReadMessageId = "r")
        val rows = rows("r", "u1")

        // The read anchor already passed the mentions: the full unread range is loaded and holds none.
        assertEquals(0, seed.count(emptyList(), ConversationMentionCoverage.COMPLETE))
        seed.observe(rows, "r", ConversationMentionCoverage.COMPLETE, ::mentionsTestAccount)
        assertFalse("a covered range retires the entry count", seed.active)
        // A later window that no longer reaches the newest row must not bring the stale count back.
        assertEquals(0, seed.count(emptyList(), ConversationMentionCoverage.PARTIAL))
    }

    @Test
    fun aMentionTheReadAnchorPassesLeavesTheCount() {
        val seed = ConversationMentionJumpSeed(entryCount = 3, entryLastReadMessageId = "r")
        val rows = rows("r", "m1@", "u1")

        seed.observe(rows, "r", ConversationMentionCoverage.PARTIAL, ::mentionsTestAccount)
        assertEquals(3, seed.count(listOf("m1@"), ConversationMentionCoverage.PARTIAL))
        // The anchor moved past m1: it was read, two mentions remain beyond the window.
        seed.observe(rows, "u1", ConversationMentionCoverage.PARTIAL, ::mentionsTestAccount)
        assertEquals(2, seed.count(emptyList(), ConversationMentionCoverage.PARTIAL))
        seed.visited("m2@")
        assertEquals(1, seed.count(emptyList(), ConversationMentionCoverage.PARTIAL))
    }

    @Test
    fun aMentionBeforeTheEntryBoundaryIsNotCountedAsReadNow() {
        val seed = ConversationMentionJumpSeed(entryCount = 1, entryLastReadMessageId = "r")

        seed.observe(rows("old@", "r", "u1"), "u1", ConversationMentionCoverage.PARTIAL, ::mentionsTestAccount)
        assertEquals(1, seed.count(emptyList(), ConversationMentionCoverage.PARTIAL))
    }

    @Test
    fun aNeverReadChatCountsMentionsTheFirstReadAnchorPasses() {
        val seed = ConversationMentionJumpSeed(entryCount = 2, entryFirstUnreadMessageId = "f")

        // The first settled viewport reads past m1 before any unread list existed.
        seed.observe(rows("f", "m1@", "u1"), "u1", ConversationMentionCoverage.PARTIAL, ::mentionsTestAccount)
        assertEquals(1, seed.count(emptyList(), ConversationMentionCoverage.PARTIAL))
    }

    @Test
    fun pagingTheAnchorOrAMentionOutOfTheWindowIsNotReading() {
        val seed = ConversationMentionJumpSeed(entryCount = 1, entryLastReadMessageId = "r")

        seed.observe(rows("r", "m1@"), "r", ConversationMentionCoverage.PARTIAL, ::mentionsTestAccount)
        // Paging evicted the anchor: no loaded targets, the unread mention still counts.
        seed.observe(rows("u5", "u6"), "r", ConversationMentionCoverage.PARTIAL, ::mentionsTestAccount)
        assertEquals(1, seed.count(emptyList(), ConversationMentionCoverage.PARTIAL))
        // The anchor is back but m1 itself is paged out: still unread.
        seed.observe(rows("x", "r"), "r", ConversationMentionCoverage.PARTIAL, ::mentionsTestAccount)
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
                        ConversationMentionSearch.Found("far")
                    }
                }
            assertFalse("the tap is held, not dropped, until entry positioning", tap.isCompleted)
            loaded = "m1"
            anchored.complete(Unit)
            assertEquals(ConversationMentionSearch.Found("m1"), tap.await())
            assertEquals(0, resolved)
        }

    @Test
    fun aTapWithoutALoadedMentionSearchesOnlyWhileTheSeedIsActive() =
        runBlocking {
            val seed = ConversationMentionJumpSeed(entryCount = 1)
            assertEquals(ConversationMentionSearch.Found("far"), seed.jumpTarget({}, { null }) { found("far") })
            seed.retire()
            assertEquals(
                ConversationMentionSearch.Exhausted,
                seed.jumpTarget({}, { null }) { error("a retired seed must not search") },
            )
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

        assertEquals(found("m1@"), history.resolve(readAnchor = "m0@"))
        assertEquals(0, history.newerLoads)
    }

    @Test
    fun pagesNewerToAMentionOutsideTheFirstPageAsTheWindowSlides() {
        val ids = listOf("r") + (1..10).map { "u$it" } + "m@" + listOf("u11")
        val history = PagedHistory(ids, window = 4, start = 0)

        assertEquals(found("m@"), history.resolve(readAnchor = "r"))
        assertTrue("the anchor slid out of the window", history.window().none { it.record.messageIdHex == "r" })
        assertEquals(4, history.newerLoads)
    }

    @Test
    fun loadsTheAnchorWhenTheWindowStartsAfterIt() {
        val history = PagedHistory(listOf("old@", "r", "u1", "m@", "u2", "u3", "u4"), window = 3, start = 4)

        assertEquals(found("m@"), history.resolve(readAnchor = "r"))
        assertEquals(listOf("r"), history.loadedMessages)
    }

    @Test
    fun withoutAReadAnchorStartsAtTheEntryFirstUnreadInclusive() {
        val history = PagedHistory(listOf("old@", "f@", "u1"), window = 3, start = 0)

        assertEquals(found("f@"), history.resolve(readAnchor = null, firstUnread = "f@"))
    }

    @Test
    fun stopsAtThePageBoundWhenNoMentionExists() {
        val ids = listOf("r") + (1..100).map { "u$it" }
        val history = PagedHistory(ids, window = 4, start = 0)

        assertEquals(ConversationMentionSearch.Exhausted, history.resolve(readAnchor = "r", maxNewerPages = 5))
        assertEquals(5, history.newerLoads)
    }

    @Test
    fun noStartRowIsExhaustedAndAnUnloadableAnchorIsUnavailable() {
        val history = PagedHistory(listOf("a", "m@"), window = 2, start = 0)

        assertEquals(ConversationMentionSearch.Exhausted, history.resolve(readAnchor = null, firstUnread = null))
        assertEquals(ConversationMentionSearch.Unavailable, history.resolve(readAnchor = "gone"))
    }

    @Test
    fun aPageAnotherLoadAdvancedKeepsTheSearchGoing() {
        val ids = listOf("r") + (1..6).map { "u$it" } + "m@"
        val history = PagedHistory(ids, window = 4, start = 0, busyLoads = 1)

        assertEquals(found("m@"), history.resolve(readAnchor = "r"))
    }

    @Test
    fun aPageThatCannotLoadLeavesTheSearchUnavailable() {
        val ids = listOf("r") + (1..10).map { "u$it" } + "m@"
        val history = PagedHistory(ids, window = 4, start = 0, stuck = true)

        assertEquals(ConversationMentionSearch.Unavailable, history.resolve(readAnchor = "r"))
    }

    /** A sliding window over [ids]; ids ending in "@" mention the active account. */
    private class PagedHistory(
        private val ids: List<String>,
        private val window: Int,
        private var start: Int,
        private var busyLoads: Int = 0,
        private val stuck: Boolean = false,
    ) {
        var newerLoads = 0
        val loadedMessages = mutableListOf<String>()

        fun window(): List<TimelineMessage> = ids.drop(start).take(window).map(::message)

        fun resolve(
            readAnchor: String?,
            firstUnread: String? = null,
            maxNewerPages: Int = MENTION_RESOLUTION_MAX_NEWER_PAGES,
        ): ConversationMentionSearch =
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
                        when {
                            stuck -> false
                            // Another page was in flight: it moved the window, this request reports no progress.
                            busyLoads-- > 0 -> {
                                start += window / 2
                                false
                            }
                            else -> {
                                start += window / 2
                                true
                            }
                        }
                    },
                    maxNewerPages = maxNewerPages,
                )
            }
    }

    private companion object {
        fun found(id: String) = ConversationMentionSearch.Found(id)

        fun rows(vararg ids: String): List<TimelineMessage> = ids.map(::message)

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

/** Ids ending in "@" mention the active account. */
private fun mentionsTestAccount(message: TimelineMessage): Boolean = message.record.messageIdHex.endsWith("@")
