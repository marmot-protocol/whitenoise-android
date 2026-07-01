package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers the pure unread/read-anchor logic behind the conversation screen's
 * "jump to newest" badge, the first-unread anchor on chat open, and the
 * scroll-driven mark-read pointer:
 *
 *  - [firstUnreadReceivedIndex] — where to drop the user on chat open.
 *  - [countUnreadIncoming]      — the FAB badge count.
 *  - [nextReadAnchor]           — the monotonic read pointer.
 *
 * These guard the regressions surfaced during review: scroll-up must not
 * resurrect the count or move the read pointer backwards, and load-older
 * prepends must not over-count (id-anchored, not index-anchored).
 */
class ConversationUnreadTest {
    // ---- firstUnreadReceivedIndex -------------------------------------------

    @Test
    fun firstUnread_returnsMinusOne_whenNothingUnread() {
        val timeline = listOf(received("r1"), sent("s1"))
        assertEquals(-1, firstUnreadReceivedIndex(timeline, unreadCount = 0))
    }

    @Test
    fun firstUnread_returnsMinusOne_whenTimelineEmpty() {
        assertEquals(-1, firstUnreadReceivedIndex(emptyList(), unreadCount = 3))
    }

    @Test
    fun firstUnread_singleUnread_returnsLastReceivedIndex() {
        val timeline = listOf(received("r1"), received("r2"))
        // 1 unread => the newest received row.
        assertEquals(1, firstUnreadReceivedIndex(timeline, unreadCount = 1))
    }

    @Test
    fun firstUnread_countsBackwardOverInterleavedSends() {
        // index: 0:r1 1:s1 2:r2 3:s2 4:r3   (3 received total)
        val timeline = listOf(received("r1"), sent("s1"), received("r2"), sent("s2"), received("r3"))
        // 2 unread => second-from-newest received => r2 at index 2.
        assertEquals(2, firstUnreadReceivedIndex(timeline, unreadCount = 2))
        // 3 unread => oldest received => r1 at index 0.
        assertEquals(0, firstUnreadReceivedIndex(timeline, unreadCount = 3))
    }

    @Test
    fun firstUnread_returnsMinusOne_whenUnreadExceedsLoadedReceived() {
        // Only 2 received in the window but projection says 5 unread.
        val timeline = listOf(sent("s1"), received("r1"), received("r2"))
        assertEquals(-1, firstUnreadReceivedIndex(timeline, unreadCount = 5))
    }

    @Test
    fun firstUnread_returnsMinusOne_whenAllSent() {
        val timeline = listOf(sent("s1"), sent("s2"))
        assertEquals(-1, firstUnreadReceivedIndex(timeline, unreadCount = 1))
    }

    @Test
    fun firstUnread_skipsTrailingSentRow() {
        // Pinning the "reverse walk skips sent" branch in isolation. A naive
        // impl returning `timeline.lastIndex - (unreadCount - 1)` would pick
        // the trailing sent row (index 1); the contract is the newest
        // *received* (index 0).
        val timeline = listOf(received("r1"), sent("s1"))
        assertEquals(0, firstUnreadReceivedIndex(timeline, unreadCount = 1))
    }

    // ---- countUnreadIncoming ------------------------------------------------

    @Test
    fun unreadCount_nullAnchor_countsAllReceived() {
        val timeline = listOf(received("r1"), sent("s1"), received("r2"))
        assertEquals(2, countUnreadIncoming(timeline, readAnchorMessageId = null))
    }

    @Test
    fun unreadCount_skipsGroupSystemRows() {
        // Kind-1210 rows arrive direction=received but are derived state
        // facts, not messages — they must not inflate the FAB badge.
        val timeline = listOf(received("r1"), groupSystem("g1"), received("r2"), groupSystem("g2"))
        assertEquals(1, countUnreadIncoming(timeline, readAnchorMessageId = "r1"))
        assertEquals(2, countUnreadIncoming(timeline, readAnchorMessageId = null))
    }

    @Test
    fun unreadCount_emptyTimeline_isZero() {
        assertEquals(0, countUnreadIncoming(emptyList(), readAnchorMessageId = "anything"))
    }

    @Test
    fun unreadCount_anchorAtLastRow_isZero() {
        val timeline = listOf(received("r1"), received("r2"))
        assertEquals(0, countUnreadIncoming(timeline, readAnchorMessageId = "r2"))
    }

    @Test
    fun unreadCount_anchorMidList_countsOnlyReceivedAfter() {
        // 0:r1 1:s1 2:r2 3:r3 ; anchor at s1 => received after = r2, r3.
        val timeline = listOf(received("r1"), sent("s1"), received("r2"), received("r3"))
        assertEquals(2, countUnreadIncoming(timeline, readAnchorMessageId = "s1"))
    }

    @Test
    fun unreadCount_ownSendsAfterAnchorAreNotCounted() {
        // Outgoing messages must never bump the unread badge.
        val timeline = listOf(received("r1"), sent("s1"), sent("s2"))
        assertEquals(0, countUnreadIncoming(timeline, readAnchorMessageId = "r1"))
    }

    @Test
    fun unreadCount_missingAnchor_fallsBackToCountingAll() {
        // Documented fallback: an anchor that has dropped out of the loaded
        // window is treated as "nothing read yet".
        val timeline = listOf(received("r1"), received("r2"))
        assertEquals(2, countUnreadIncoming(timeline, readAnchorMessageId = "evicted"))
    }

    @Test
    fun unreadCount_missingAnchor_treatsAsNothingRead_withInterleavedSends() {
        // Strengthened version of the missing-anchor case. A buggy
        // fallback that silently mapped "not found" to index 0 would compute
        // drop(1) here = [sent, received] → 1 received, mismatching the
        // intended "count all received in window" → 2.
        val timeline = listOf(received("r1"), sent("s1"), received("r2"))
        assertEquals(2, countUnreadIncoming(timeline, readAnchorMessageId = "evicted"))
    }

    @Test
    fun unreadCount_duplicateAnchorId_bindsToFirstOccurrence() {
        // Locks `indexOfFirst` semantics. If two rows share a messageIdHex
        // (optimistic-then-confirmed window during retry), the anchor binds
        // to the earlier position — so `drop(anchorIdx + 1)` includes the
        // later duplicate as still-unread.
        val timeline = listOf(received("r1"), received("r1"), received("r2"))
        assertEquals(2, countUnreadIncoming(timeline, readAnchorMessageId = "r1"))
    }

    @Test
    fun unreadCount_isStableWhenOlderMessagesArePrepended() {
        // Regression: badge must not over-count when "load older" prepends
        // history. Anchor id stays valid; only its index shifts.
        val before = listOf(received("r1"), received("r2"), received("r3"))
        val afterPrepend = listOf(received("old1"), received("old2")) + before

        // Anchor on r2: one received after it (r3) in both layouts.
        assertEquals(1, countUnreadIncoming(before, readAnchorMessageId = "r2"))
        assertEquals(1, countUnreadIncoming(afterPrepend, readAnchorMessageId = "r2"))
    }

    // ---- nextReadAnchor -----------------------------------------------------

    @Test
    fun nextAnchor_nullCurrent_adoptsCandidate() {
        val timeline = listOf(received("r1"), received("r2"))
        assertEquals("r2", nextReadAnchor(timeline, currentAnchorId = null, candidateIndex = 1))
    }

    @Test
    fun nextAnchor_advancesWhenCandidateDeeper() {
        val timeline = listOf(received("r1"), received("r2"), received("r3"))
        assertEquals("r3", nextReadAnchor(timeline, currentAnchorId = "r1", candidateIndex = 2))
    }

    @Test
    fun nextAnchor_scrollUp_keepsCurrentAnchor_noRegression() {
        // Regression: settling on a shallower row (scroll up) must NOT move
        // the read pointer backwards.
        val timeline = listOf(received("r1"), received("r2"), received("r3"))
        assertEquals("r3", nextReadAnchor(timeline, currentAnchorId = "r3", candidateIndex = 0))
    }

    @Test
    fun nextAnchor_sameRow_keepsCurrentAnchor() {
        val timeline = listOf(received("r1"), received("r2"))
        // Candidate equals current position (not strictly deeper) => unchanged.
        assertEquals("r2", nextReadAnchor(timeline, currentAnchorId = "r2", candidateIndex = 1))
    }

    @Test
    fun nextAnchor_missingCandidateRow_keepsCurrentAnchor() {
        val timeline = listOf(received("r1"))
        // candidateIndex out of range -> getOrNull null -> keep current.
        assertEquals("r1", nextReadAnchor(timeline, currentAnchorId = "r1", candidateIndex = 9))
    }

    @Test
    fun nextAnchor_blankCandidateId_keepsCurrentAnchor() {
        // Direct cover of the `candidateId.isNullOrBlank()` branch — a row
        // with empty messageIdHex (e.g., an optimistic write pre-confirmation
        // that never received an FFI id) must not become the read anchor.
        val timeline = listOf(received("r1"), message(id = "", direction = "received"))
        assertEquals("r1", nextReadAnchor(timeline, currentAnchorId = "r1", candidateIndex = 1))
    }

    @Test
    fun nextAnchor_streamDebugRowCandidate_keepsCurrentAnchor() {
        // Synthetic streaming-debug rows carry a non-hex `dbg:stream:` id and
        // never mark read. Settling on one must not move the read pointer off
        // the last real message.
        val debugId = "${ConversationController.STREAM_DEBUG_ID_PREFIX}abc:1:00000000000000000001"
        val timeline = listOf(received("r1"), message(id = debugId, direction = "debug"))
        assertEquals("r1", nextReadAnchor(timeline, currentAnchorId = "r1", candidateIndex = 1))
        // Even with no prior anchor, a debug row can't become the first anchor.
        assertEquals(null, nextReadAnchor(timeline, currentAnchorId = null, candidateIndex = 1))
    }

    @Test
    fun nextAnchor_currentAnchorEvicted_resetsToCandidate() {
        val timeline = listOf(received("r2"), received("r3"))
        // "r1" trimmed out of the window -> anchorIdx < 0 -> adopt candidate.
        assertEquals("r3", nextReadAnchor(timeline, currentAnchorId = "r1", candidateIndex = 1))
    }

    @Test
    fun nextAnchor_scrollUpAfterPrepend_stillNoRegression() {
        // Combined: load-older prepended two rows (every index +2), then the
        // user scrolls up. Position comparison stays valid via the id lookup,
        // so the anchor holds.
        val timeline = listOf(received("old1"), received("old2"), received("r1"), received("r2"), received("r3"))
        // Anchor on r3 (now index 4); user scrolls up to old2 (index 1).
        assertEquals("r3", nextReadAnchor(timeline, currentAnchorId = "r3", candidateIndex = 1))
    }

    // ---- end-to-end: scroll down then up ------------------------------------

    @Test
    fun readingForwardThenScrollingUp_decrementsThenHolds() {
        val timeline = listOf(received("r1"), received("r2"), received("r3"))
        var anchor: String? = null

        // Land at bottom on open (index 2).
        anchor = nextReadAnchor(timeline, anchor, candidateIndex = 2)
        assertEquals("r3", anchor)
        assertEquals(0, countUnreadIncoming(timeline, anchor))

        // New incoming arrives -> still unread until seen.
        val grown = timeline + received("r4")
        assertEquals(1, countUnreadIncoming(grown, anchor))

        // User scrolls back up to re-read; anchor must hold and the freshly
        // arrived r4 must stay counted as unread.
        anchor = nextReadAnchor(grown, anchor, candidateIndex = 0)
        assertEquals("r3", anchor)
        assertEquals(1, countUnreadIncoming(grown, anchor))

        // User scrolls down to r4 -> read pointer advances, badge clears.
        anchor = nextReadAnchor(grown, anchor, candidateIndex = 3)
        assertEquals("r4", anchor)
        assertEquals(0, countUnreadIncoming(grown, anchor))
    }

    // ---- unreadReceivedMentionIds (jump-to-mention chip) --------------------

    private val isMention: (TimelineMessage) -> Boolean = { it.record.messageIdHex.startsWith("m") }

    @Test
    fun mentions_nullAnchor_countsAllReceivedMentions() {
        val timeline = listOf(received("m1"), received("r2"), received("m3"))
        assertEquals(listOf("m1", "m3"), unreadReceivedMentionIds(timeline, null, isMention))
    }

    @Test
    fun mentions_missingAnchorHidesChip() {
        // A non-null watermark outside the loaded window means the mention may
        // already be read. Hide the in-chat chip rather than resurrecting a stale
        // mention after recreating the conversation.
        val timeline = listOf(received("m1"), received("m2"))
        assertEquals(emptyList<String>(), unreadReceivedMentionIds(timeline, "not-in-window", isMention))
    }

    @Test
    fun mentions_anchorSet_countsOnlyAfterAnchor() {
        val timeline = listOf(received("m1"), received("r2"), received("m3"))
        assertEquals(listOf("m3"), unreadReceivedMentionIds(timeline, "r2", isMention))
    }

    @Test
    fun mentions_excludeSentNonMentionAndNonChatKinds() {
        val timeline = listOf(received("m1"), sent("m2"), received("r3"), groupSystem("m4"))
        assertEquals(listOf("m1"), unreadReceivedMentionIds(timeline, null, isMention))
    }

    // ---- helpers ------------------------------------------------------------

    private fun received(
        id: String,
        recordedAt: ULong = 1uL,
    ): TimelineMessage = message(id, direction = "received", recordedAt = recordedAt)

    private fun sent(
        id: String,
        recordedAt: ULong = 1uL,
    ): TimelineMessage = message(id, direction = "sent", recordedAt = recordedAt)

    private fun groupSystem(
        id: String,
        recordedAt: ULong = 1uL,
    ): TimelineMessage = message(id, direction = "received", recordedAt = recordedAt, kind = 1210uL)

    private fun message(
        id: String,
        direction: String,
        recordedAt: ULong = 1uL,
        status: MessageStatus = if (direction == "received") MessageStatus.Received else MessageStatus.Sent,
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
                    contentTokens = MarkdownDocumentFfi(truncated = false, blocks = emptyList()),
                    kind = kind,
                    tags = emptyList(),
                    recordedAt = recordedAt,
                    receivedAt = recordedAt,
                ),
            status = status,
        )
}
