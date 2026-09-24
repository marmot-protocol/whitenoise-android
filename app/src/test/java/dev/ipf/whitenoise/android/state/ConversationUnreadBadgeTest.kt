package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.whitenoise.android.state.ConversationUnreadBadge.Source
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The jump badge's count must not move because a history page loaded or evicted rows (#2726): it
 * follows the loaded rows while the read anchor is among them, holds while paging moves the anchor
 * off screen, and rises while holding only when the projection reports an arrival.
 */
class ConversationUnreadBadgeTest {
    /** With the anchor on screen, the badge counts the received rows after it, edits and system rows excluded. */
    @Test
    fun loadedAnchorCountsTheReceivedRowsAfterIt() {
        val badge =
            ConversationUnreadBadge().reconcile(
                timeline = listOf(received("r1"), received("r2"), sent("s1"), groupSystem("g1"), received("r3")),
                readAnchorMessageId = "r2",
                projectionUnread = 40,
                windowReachesTail = true,
            )

        assertEquals(1, badge.count)
        assertEquals(Source.LOADED, badge.source)
        assertEquals("r2", badge.anchorMessageId)
    }

    /** Older pages that push the anchor off screen leave the number exactly where it was, page after page. */
    @Test
    fun pagingTheAnchorOffScreenHoldsTheCount() {
        val atBottom =
            ConversationUnreadBadge().reconcile(
                receivedRange(150, 200),
                "r199",
                projectionUnread = 0,
                windowReachesTail = true,
            )
        assertEquals(0, atBottom.count)

        var badge = atBottom
        val counts = mutableListOf<Int>()
        // Three older pages: the anchor r199 is evicted on the first, the loaded rows grow each time.
        listOf(receivedRange(100, 150), receivedRange(50, 150), receivedRange(0, 150)).forEach { page ->
            badge = badge.reconcile(page, "r199", projectionUnread = 0, windowReachesTail = true)
            counts += badge.count
        }

        assertEquals(listOf(0, 0, 0), counts)
        assertEquals(Source.HELD, badge.source)
    }

    /** Without a projection the held count still ignores how many rows happen to be loaded. */
    @Test
    fun heldCountIgnoresTheLoadedRowsWithoutAProjection() {
        val counted =
            ConversationUnreadBadge().reconcile(
                receivedRange(190, 200),
                "r197",
                projectionUnread = null,
                windowReachesTail = true,
            )
        assertEquals(2, counted.count)

        val paged = counted.reconcile(receivedRange(0, 150), "r197", projectionUnread = null, windowReachesTail = true)

        assertEquals(2, paged.count)
        assertEquals(Source.HELD, paged.source)
    }

    /**
     * While holding, a projection that grew is an arrival. One that fell below the held number is read
     * as lagging it, so the rise back to that number adds nothing; only growth past it does.
     */
    @Test
    fun heldCountRisesOnlyWhenTheProjectionGrows() {
        val counted =
            ConversationUnreadBadge().reconcile(
                receivedRange(190, 200),
                "r199",
                projectionUnread = 3,
                windowReachesTail = true,
            )
        val history = receivedRange(0, 150)

        val evicted = counted.reconcile(history, "r199", projectionUnread = 3, windowReachesTail = true)
        val arrival = evicted.reconcile(history, "r199", projectionUnread = 4, windowReachesTail = true)
        val markReadCommitted = arrival.reconcile(history, "r199", projectionUnread = 0, windowReachesTail = true)
        val secondArrival = markReadCommitted.reconcile(history, "r199", projectionUnread = 1, windowReachesTail = true)

        val thirdArrival = secondArrival.reconcile(history, "r199", projectionUnread = 2, windowReachesTail = false)

        assertEquals(
            listOf(0, 1, 1, 1, 2),
            listOf(evicted, arrival, markReadCommitted, secondArrival, thirdArrival).map { it.count },
        )
    }

    /** Reading past unread rows moves the anchor to a loaded row, and the badge recounts from the rows. */
    @Test
    fun anchorAdvancingRecountsFromTheLoadedRows() {
        val rows = receivedRange(0, 10)
        val held =
            ConversationUnreadBadge()
                .reconcile(rows, "r5", projectionUnread = null, windowReachesTail = true)
                .reconcile(receivedRange(20, 30), "r5", projectionUnread = null, windowReachesTail = true)
        assertEquals(4, held.count)

        val advanced = held.reconcile(rows, "r8", projectionUnread = null, windowReachesTail = true)

        assertEquals(1, advanced.count)
        assertEquals(Source.LOADED, advanced.source)
    }

    /** Coming back to the anchor after new rows landed recounts exactly rather than trusting the held figure. */
    @Test
    fun returningToTheLoadedAnchorRecountsExactly() {
        val held =
            ConversationUnreadBadge()
                .reconcile(receivedRange(0, 10), "r9", projectionUnread = 0, windowReachesTail = true)
                .reconcile(receivedRange(20, 30), "r9", projectionUnread = 2, windowReachesTail = true)
        assertEquals(2, held.count)

        val back = held.reconcile(receivedRange(0, 13), "r9", projectionUnread = 2, windowReachesTail = true)

        assertEquals(3, back.count)
        assertEquals(Source.LOADED, back.source)
    }

    /** An anchor restored off screen was never counted, so the projection stands in and the loaded rows never do. */
    @Test
    fun offScreenAnchorNeverSeenLoadedUsesTheProjectionNotTheRows() {
        val history = receivedRange(0, 150)

        val withProjection =
            ConversationUnreadBadge().reconcile(
                history,
                "r400",
                projectionUnread = 7,
                windowReachesTail = true,
            )
        val withoutProjection =
            ConversationUnreadBadge().reconcile(
                history,
                "r400",
                projectionUnread = null,
                windowReachesTail = true,
            )

        assertEquals(7, withProjection.count)
        assertEquals(Source.PROJECTION, withProjection.source)
        assertEquals(0, withoutProjection.count)
        assertEquals(Source.UNKNOWN, withoutProjection.source)
    }

    /**
     * Nothing read yet: at the tail every received row is unread; short of it the projection decides,
     * and without one the rows are counted once, then held.
     */
    @Test
    fun noAnchorUsesTheProjectionOrCountsOnceThenHolds() {
        val atTail =
            ConversationUnreadBadge().reconcile(
                receivedRange(0, 150),
                null,
                projectionUnread = 12,
                windowReachesTail = true,
            )
        assertEquals(150, atTail.count)
        assertEquals(Source.LOADED, atTail.source)

        val projected =
            ConversationUnreadBadge().reconcile(
                receivedRange(0, 150),
                null,
                projectionUnread = 12,
                windowReachesTail = false,
            )
        assertEquals(12, projected.count)
        assertEquals(Source.PROJECTION, projected.source)

        // Short of the tail with no projection, the rows are counted once and then held.
        val counted =
            ConversationUnreadBadge().reconcile(
                receivedRange(0, 50),
                null,
                projectionUnread = null,
                windowReachesTail = false,
            )
        val paged = counted.reconcile(receivedRange(0, 150), null, projectionUnread = null, windowReachesTail = false)

        assertEquals(50, counted.count)
        assertEquals(50, paged.count)
    }

    /** A backward page trims the newest rows but keeps the anchor: the count must not follow the trimmed window. */
    @Test
    fun trimmingTheNewestRowsWhileTheAnchorStaysLoadedHoldsTheCount() {
        val atTail =
            ConversationUnreadBadge().reconcile(
                receivedRange(0, 200),
                "r99",
                projectionUnread = 100,
                windowReachesTail = true,
            )
        assertEquals(100, atTail.count)

        val trimmed = atTail.reconcile(receivedRange(0, 150), "r99", projectionUnread = 100, windowReachesTail = false)
        val trimmedAgain =
            trimmed.reconcile(
                receivedRange(0, 120),
                "r99",
                projectionUnread = 100,
                windowReachesTail = false,
            )

        assertEquals(listOf(100, 100), listOf(trimmed.count, trimmedAgain.count))
        assertEquals(Source.HELD, trimmedAgain.source)
    }

    /** Reading past unread rows inside a window that stops short of the tail subtracts exactly the rows read. */
    @Test
    fun anchorAdvancingInsideAPartialWindowSubtractsTheRowsRead() {
        val held =
            ConversationUnreadBadge()
                .reconcile(receivedRange(0, 200), "r99", projectionUnread = 100, windowReachesTail = true)
                .reconcile(receivedRange(0, 150), "r99", projectionUnread = 100, windowReachesTail = false)

        val advanced = held.reconcile(receivedRange(0, 150), "r109", projectionUnread = 100, windowReachesTail = false)
        val backAtTail =
            advanced.reconcile(
                receivedRange(0, 200),
                "r109",
                projectionUnread = 100,
                windowReachesTail = true,
            )

        assertEquals(90, advanced.count)
        assertEquals(Source.HELD, advanced.source)
        assertEquals(90, backAtTail.count)
        assertEquals(Source.LOADED, backAtTail.source)
    }

    /** Opened mid-history the rows stop at the window's edge: the projection stands in, else a lower bound grows. */
    @Test
    fun openingMidHistoryUsesTheProjectionOrHoldsAPartialCount() {
        val projected =
            ConversationUnreadBadge().reconcile(
                receivedRange(0, 50),
                "r10",
                projectionUnread = 300,
                windowReachesTail = false,
            )
        assertEquals(300, projected.count)
        assertEquals(Source.PROJECTION, projected.source)

        val partial =
            ConversationUnreadBadge().reconcile(
                receivedRange(0, 50),
                "r10",
                projectionUnread = null,
                windowReachesTail = false,
            )
        val pagedForward =
            partial.reconcile(
                receivedRange(0, 100),
                "r10",
                projectionUnread = null,
                windowReachesTail = false,
            )

        assertEquals(39, partial.count)
        assertEquals(Source.PARTIAL, partial.source)
        // A lower bound grows with the rows a forward page adds after the anchor.
        assertEquals(89, pagedForward.count)
        assertEquals(Source.HELD, pagedForward.source)
    }

    /** A lower bound taken without a projection never falls below the unread rows still loaded after the anchor. */
    @Test
    fun partialCountGrowsTowardsTheTruthAndNeverBelowTheLoadedRows() {
        val partial =
            ConversationUnreadBadge().reconcile(
                receivedRange(0, 50),
                "r10",
                projectionUnread = null,
                windowReachesTail = false,
            )
        assertEquals(39, partial.count)

        val readAll = partial.reconcile(receivedRange(0, 50), "r49", projectionUnread = null, windowReachesTail = false)
        val pagedForward =
            readAll.reconcile(
                receivedRange(0, 100),
                "r49",
                projectionUnread = null,
                windowReachesTail = false,
            )
        val atTail =
            pagedForward.reconcile(
                receivedRange(0, 300),
                "r49",
                projectionUnread = null,
                windowReachesTail = true,
            )

        assertEquals(0, readAll.count)
        assertEquals(50, pagedForward.count)
        assertEquals(Source.HELD, pagedForward.source)
        assertEquals(250, atTail.count)
        assertEquals(Source.LOADED, atTail.source)
    }

    /** A projection that lags the rows and then catches up while held is not a second set of arrivals. */
    @Test
    fun aProjectionCatchingUpWhileHeldIsNotCountedTwice() {
        val counted =
            ConversationUnreadBadge().reconcile(
                receivedRange(0, 30),
                "r9",
                projectionUnread = 0,
                windowReachesTail = true,
            )
        assertEquals(20, counted.count)

        val held = counted.reconcile(receivedRange(0, 20), "r9", projectionUnread = 0, windowReachesTail = false)
        val caughtUp = held.reconcile(receivedRange(0, 20), "r9", projectionUnread = 20, windowReachesTail = false)
        val arrival = caughtUp.reconcile(receivedRange(0, 20), "r9", projectionUnread = 21, windowReachesTail = false)

        assertEquals(listOf(20, 20, 21), listOf(held, caughtUp, arrival).map { it.count })
    }

    /** The window reaching the tail, leaving it and reaching it again with the same rows changes nothing. */
    @Test
    fun windowReachingTheTailAndLeavingIsIdempotent() {
        val rows = receivedRange(0, 40)
        val loaded = ConversationUnreadBadge().reconcile(rows, "r34", projectionUnread = 5, windowReachesTail = true)
        val held = loaded.reconcile(rows, "r34", projectionUnread = 5, windowReachesTail = false)
        val loadedAgain = held.reconcile(rows, "r34", projectionUnread = 5, windowReachesTail = true)

        assertEquals(listOf(5, 5, 5), listOf(loaded, held, loadedAgain).map { it.count })
        assertEquals(
            listOf(Source.LOADED, Source.HELD, Source.LOADED),
            listOf(loaded, held, loadedAgain).map { it.source },
        )
    }

    /** Nothing read yet in a window short of the tail counts once and holds, then counts everything at the tail. */
    @Test
    fun noAnchorInAPartialWindowHoldsUntilTheTail() {
        val partial =
            ConversationUnreadBadge().reconcile(
                receivedRange(0, 50),
                null,
                projectionUnread = null,
                windowReachesTail = false,
            )
        val paged = partial.reconcile(receivedRange(0, 100), null, projectionUnread = null, windowReachesTail = false)
        val atTail = paged.reconcile(receivedRange(0, 120), null, projectionUnread = null, windowReachesTail = true)

        assertEquals(Source.PARTIAL, partial.source)
        assertEquals(listOf(50, 50, 120), listOf(partial, paged, atTail).map { it.count })
        assertEquals(Source.LOADED, atTail.source)
    }

    /** A number taken from the projection holds through a later decrease and follows only real growth. */
    @Test
    fun aProjectionDecreaseWithNothingReadHoldsTheCount() {
        val rows = receivedRange(0, 50)
        val opened = ConversationUnreadBadge().reconcile(rows, "r10", projectionUnread = 300, windowReachesTail = false)
        val commitLanded = opened.reconcile(rows, "r10", projectionUnread = 250, windowReachesTail = false)
        val partialRecovery = commitLanded.reconcile(rows, "r10", projectionUnread = 260, windowReachesTail = false)
        val arrivals = partialRecovery.reconcile(rows, "r10", projectionUnread = 310, windowReachesTail = false)
        val readOn = arrivals.reconcile(rows, "r20", projectionUnread = 310, windowReachesTail = false)

        assertEquals(
            listOf(300, 300, 300, 310),
            listOf(opened, commitLanded, partialRecovery, arrivals).map { it.count },
        )
        assertEquals(Source.PROJECTION, arrivals.source)
        assertEquals(300, readOn.count)
        assertEquals(Source.HELD, readOn.source)
    }

    /** Received rows `r<from>` up to `r<until - 1>`, one loaded window of ordinary messages. */
    private fun receivedRange(
        from: Int,
        until: Int,
    ): List<TimelineMessage> = (from until until).map { received("r$it") }

    /** An ordinary received message. */
    private fun received(id: String): TimelineMessage = message(id, direction = "received")

    /** A message the reader sent, never unread. */
    private fun sent(id: String): TimelineMessage = message(id, direction = "sent")

    /** A group system row: received, but derived state that never counts as unread. */
    private fun groupSystem(id: String): TimelineMessage = message(id, direction = "received", kind = 1210uL)

    /** A minimal timeline row with the fields the unread count reads. */
    private fun message(
        id: String,
        direction: String,
        kind: ULong = 9uL,
    ): TimelineMessage =
        TimelineMessage(
            id = "msg:$id",
            record =
                AppMessageRecordFfi(
                    messageIdHex = id,
                    direction = direction,
                    groupIdHex = "group",
                    sender = if (direction == "received") "bob" else "alice",
                    plaintext = "text-$id",
                    contentTokens =
                        MarkdownDocumentFfi(truncated = false, blocks = emptyList(), blankLinesBefore = ByteArray(0)),
                    kind = kind,
                    tags = emptyList(),
                    sourceEpoch = null,
                    retentionSeconds = null,
                    retentionExpiresAt = null,
                    recordedAt = 1uL,
                    receivedAt = 1uL,
                ),
            status = if (direction == "received") MessageStatus.Received else MessageStatus.Sent,
        )
}
