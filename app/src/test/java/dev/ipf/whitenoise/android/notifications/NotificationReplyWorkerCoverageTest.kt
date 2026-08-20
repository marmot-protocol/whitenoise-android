package dev.ipf.whitenoise.android.notifications

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationReplyWorkerCoverageTest {
    @Test
    fun failedCardRestoreSucceedsOnceTheLifetimeExtendedCardAppears() =
        runTest {
            var attempts = 0

            val restored = retryReplyCardRestore { ++attempts >= 3 }

            assertTrue(restored)
            assertEquals(3, attempts)
        }

    @Test
    fun failedCardRestoreGivesUpAfterBoundedAttempts() =
        runTest {
            var attempts = 0

            val restored =
                retryReplyCardRestore {
                    attempts++
                    false
                }

            assertFalse(restored)
            assertEquals(REPLY_DISMISS_RETRIES, attempts)
        }

    @Test
    fun acceptedPendingMessageIdIsDurableProofWithoutTimelineLookup() =
        runTest {
            var pageLoads = 0

            val outcome =
                notificationReplyCommitProbe(
                    recoveryState = state(committedMessageIdHex = "a".repeat(64)),
                    nextAttemptBoundary = cursor(20u, "later"),
                    text = "same reply",
                ) { _, _ ->
                    pageLoads += 1
                    page()
                }

            assertEquals(NotificationReplyCommitProbe.Committed, outcome)
            assertEquals(0, pageLoads)
        }

    @Test
    fun recoveryPaginatesUntilMatchingSentReply() =
        runTest {
            val pages =
                ArrayDeque(
                    listOf(
                        page(record(11u, "received", "received", "same reply"), hasMore = true),
                        page(record(12u, "reply-id", "sent", "same reply")),
                    ),
                )
            val seenCursors = mutableListOf<NotificationReplyRecoveryBoundary>()

            val outcome =
                notificationReplyCommitProbe(
                    recoveryState = state(),
                    nextAttemptBoundary = null,
                    text = "same reply",
                ) { after, _ ->
                    seenCursors += after
                    pages.removeFirst()
                }

            assertEquals(NotificationReplyCommitProbe.Committed, outcome)
            assertEquals(listOf(cursor(10u, "baseline"), cursor(11u, "received")), seenCursors)
        }

    @Test
    fun laterInvocationBoundaryPreventsClaimingItsIdenticalReply() =
        runTest {
            val outcome =
                notificationReplyCommitProbe(
                    recoveryState = state(),
                    nextAttemptBoundary = cursor(20u, "later-baseline"),
                    text = "same reply",
                ) { _, _ ->
                    page(
                        record(20u, "later-baseline", "received", "noise"),
                        record(21u, "later-reply", "sent", "same reply"),
                    )
                }

            assertEquals(NotificationReplyCommitProbe.NotCommitted, outcome)
        }

    @Test
    fun matchingReplyAtUpperBoundaryBelongsToEarlierInvocation() =
        runTest {
            val outcome =
                notificationReplyCommitProbe(
                    recoveryState = state(),
                    nextAttemptBoundary = cursor(20u, "later-baseline"),
                    text = "same reply",
                ) { _, _ ->
                    page(record(20u, "later-baseline", "sent", "same reply"))
                }

            assertEquals(NotificationReplyCommitProbe.Committed, outcome)
        }

    @Test
    fun unconfirmedLocalProjectionIsIndeterminate() =
        runTest {
            val outcome =
                notificationReplyCommitProbe(
                    recoveryState = state(),
                    nextAttemptBoundary = null,
                    text = "same reply",
                ) { _, _ ->
                    page(record(11u, "pending", "sent", "same reply", sourceMessageIdHex = null))
                }

            assertEquals(NotificationReplyCommitProbe.Indeterminate, outcome)
        }

    @Test
    fun exhaustedTimelineIsDefinitiveMiss() =
        runTest {
            val outcome =
                notificationReplyCommitProbe(
                    recoveryState = state(),
                    nextAttemptBoundary = null,
                    text = "same reply",
                ) { _, _ -> page(record(11u, "received", "received", "same reply")) }

            assertEquals(NotificationReplyCommitProbe.NotCommitted, outcome)
        }

    @Test
    fun stalledPaginationIsIndeterminateInsteadOfPermissionToResend() =
        runTest {
            val outcome =
                notificationReplyCommitProbe(
                    recoveryState = state(),
                    nextAttemptBoundary = null,
                    text = "same reply",
                ) { after, _ ->
                    page(
                        record(after.timelineAt, after.messageIdHex, "received", "noise"),
                        hasMore = true,
                    )
                }

            assertEquals(NotificationReplyCommitProbe.Indeterminate, outcome)
        }

    @Test
    fun blankReplyIsIndeterminate() =
        runTest {
            val outcome = notificationReplyCommitProbe(state(), null, "   ") { _, _ -> page() }

            assertEquals(NotificationReplyCommitProbe.Indeterminate, outcome)
        }

    @Test
    fun recoveryBoundaryMovesSendIntoStrictlyLaterTimelineSecond() {
        val boundary = notificationReplyRecoveryBoundary(nowMillis = 1_234L)

        assertEquals(1uL, boundary.timelineAt)
        assertEquals("f".repeat(64), boundary.messageIdHex)
        assertFalse(notificationReplySendWindowReady(boundary, nowMillis = 1_999L))
        assertTrue(notificationReplySendWindowReady(boundary, nowMillis = 2_000L))
    }

    @Test
    fun recoveryScopeIsStableAndDoesNotExposeIdentifiers() {
        val first = NotificationReplyWorker.notificationReplyRecoveryScope("account-secret", "group-secret")
        val same = NotificationReplyWorker.notificationReplyRecoveryScope("account-secret", "group-secret")
        val different = NotificationReplyWorker.notificationReplyRecoveryScope("account-secret", "other-group")

        assertEquals(first, same)
        assertFalse(first.contains("account-secret"))
        assertFalse(first.contains("group-secret"))
        assertTrue(first.matches(Regex("[0-9a-f]{64}")))
        assertTrue(first != different)
    }

    @Test
    fun retriesRemainBounded() {
        assertTrue(NotificationReplyWorker.shouldRetryAfterFailure(runAttemptCount = 0))
        assertTrue(NotificationReplyWorker.shouldRetryAfterFailure(runAttemptCount = 1))
        assertFalse(NotificationReplyWorker.shouldRetryAfterFailure(runAttemptCount = 2))
    }

    private fun state(committedMessageIdHex: String? = null) =
        NotificationReplyRecoveryState(
            boundary = cursor(10u, "baseline"),
            scope = "scope",
            sequence = 1L,
            committedMessageIdHex = committedMessageIdHex,
        )

    private fun cursor(
        timelineAt: ULong,
        messageIdHex: String,
    ) = NotificationReplyRecoveryBoundary(timelineAt, messageIdHex)

    private fun record(
        timelineAt: ULong,
        messageIdHex: String,
        direction: String,
        plaintext: String,
        sourceMessageIdHex: String? = if (direction.equals("sent", ignoreCase = true)) "source-id" else null,
    ) = NotificationReplyTimelineRecord(timelineAt, messageIdHex, sourceMessageIdHex, direction, plaintext)

    private fun page(
        vararg records: NotificationReplyTimelineRecord,
        hasMore: Boolean = false,
    ) = NotificationReplyTimelinePage(records.toList(), hasMore)
}
