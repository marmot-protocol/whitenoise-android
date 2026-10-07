package dev.ipf.whitenoise.android.state

import android.os.Looper
import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.marmotkit.TimelinePageFfi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/** Committed older-row progress, not a newer revision alone, can retire a late retryable page error. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class ConversationOlderPageReplyRaceTest {
    /** Committed older rows win over a delayed timeout or exhausted not-ready reply in either origin. */
    @Test
    fun streamFirstOlderProgressDoesNotRestoreALateFailure() =
        runBlocking {
            for (reason in RETRYABLE_REASONS) {
                for (origin in ConversationPagingOrigin.entries) {
                    assertDelayedOlderReply(
                        reason,
                        origin,
                        page(listOf(record(OLDER_ID, 100uL), record(SEED_ID, 200uL)), true),
                        recovered = true,
                    )
                }
            }
        }

    /** Recovering an automatic older request cannot dismiss a failure from explicit newer navigation. */
    @Test
    fun streamFirstOlderProgressPreservesAnUnrelatedNewerFailure() =
        runBlocking {
            for (reason in RETRYABLE_REASONS) {
                assertDelayedOlderReply(
                    reason,
                    ConversationPagingOrigin.AUTOMATIC,
                    page(listOf(record(OLDER_ID, 100uL), record(SEED_ID, 200uL)), true),
                    recovered = true,
                    preserveNewerFailure = true,
                )
            }
        }

    /** Content-only and tail-only replacements do not prove that a timed-out older request recovered. */
    @Test
    fun unrelatedStreamUpdatesKeepLateOlderFailuresRetryable() =
        runBlocking {
            val replacements =
                listOf(
                    page(listOf(timelineRecord(SEED_ID, 200uL, plaintext = "edited")), true),
                    page(listOf(record(SEED_ID, 200uL), record(TARGET_ID, 300uL)), true),
                )
            for (reason in RETRYABLE_REASONS) {
                for (replacement in replacements) {
                    assertDelayedOlderReply(reason, ConversationPagingOrigin.EXPLICIT, replacement, recovered = false)
                }
            }
        }

    /** A different viewport that no longer contains the old edge cannot prove an older extension. */
    @Test
    fun recenteredStreamKeepsLateOlderFailuresRetryable() =
        runBlocking {
            for (reason in RETRYABLE_REASONS) {
                assertDelayedOlderReply(
                    reason,
                    ConversationPagingOrigin.EXPLICIT,
                    page(listOf(record(TARGET_ID, 50uL)), true),
                    recovered = false,
                )
            }
        }

    /** Suspends the command reply until the real receive loop has committed the scripted replacement. */
    private suspend fun assertDelayedOlderReply(
        reason: ConversationWindowUnchangedReason,
        origin: ConversationPagingOrigin,
        replacement: TimelinePageFfi,
        recovered: Boolean,
        preserveNewerFailure: Boolean = false,
    ) =
        coroutineScope {
            val requested = CompletableDeferred<Unit>()
            val reply = CompletableDeferred<Unit>()
            val subscription = subscriptionWith(*Array(CONVERSATION_PAGE_NOT_READY_ATTEMPTS) { outcome(reason) })
            subscription.beforeBackwardsReply = {
                requested.complete(Unit)
                reply.await()
            }
            withController(subscription) { controller ->
                awaitRecoveryCondition { controller.timeline.firstOrNull()?.id == SEED_ID }
                if (preserveNewerFailure) {
                    controller.reportPageFailure(ConversationSearchPageDirection.NEWER, IllegalStateException())
                }
                val previousFailure = controller.pageError
                val loading = async { controller.loadOlderPageInternal(origin = origin) }
                try {
                    withTimeout(5_000) { requested.await() }
                    emitWindowAndDrain(subscription, replacement)
                    awaitRecoveryCondition {
                        controller.timeline.map { it.id } == replacement.messages.map { it.messageIdHex } &&
                            controller.timeline.map { it.record.plaintext } == replacement.messages.map { it.plaintext }
                    }
                    reply.complete(Unit)
                    val expected =
                        when {
                            recovered -> ConversationPageLoad.NO_PROGRESS
                            reason == ConversationWindowUnchangedReason.TIMED_OUT -> ConversationPageLoad.TIMED_OUT
                            else -> ConversationPageLoad.NOT_READY
                        }
                    assertEquals(expected, withTimeout(5_000) { loading.await() })
                    assertFalse(controller.isLoadingOlder)
                    assertFalse(controller.automaticOlderPagingBlocked)
                    if (recovered) {
                        assertSame(previousFailure, controller.pageError)
                        val expectedDirection = ConversationSearchPageDirection.NEWER.takeIf { preserveNewerFailure }
                        assertEquals(expectedDirection, controller.failedPageDirection)
                    } else {
                        assertTrue(controller.olderPageBlocked)
                        assertEquals(ConversationSearchPageDirection.OLDER, controller.failedPageDirection)
                    }
                } finally {
                    reply.complete(Unit)
                    loading.cancel()
                    loading.join()
                }
            }
        }

    /** Advances the paused Android clock while background preparation hands the replacement back. */
    private fun awaitRecoveryCondition(condition: () -> Boolean) {
        awaitConversationCondition {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))
            condition()
        }
    }

    /** Delivers a replacement and advances the controller's short live-window batching delay. */
    private fun emitWindowAndDrain(
        subscription: ScriptedConversationTimelineSubscription,
        replacement: TimelinePageFfi,
    ) {
        val callsBefore = subscription.nextWindowCallCount
        subscription.emitWindow(replacement)
        awaitConversationCondition { subscription.nextWindowCallCount > callsBefore }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))
    }

    /** Builds a subscription seeded with one row that still has older history behind it. */
    private fun subscriptionWith(vararg outcomes: TimelinePageOutcome) =
        ScriptedConversationTimelineSubscription(
            snapshotPage = page(listOf(record(SEED_ID, timelineAt = 200uL)), hasMoreBefore = true),
            backwardsOutcomes = outcomes.toMutableList(),
        )

    /** An unchanged outcome that keeps whatever window the handle already holds. */
    private fun outcome(reason: ConversationWindowUnchangedReason) = TimelinePageOutcome.Unchanged(reason, null)

    /** Builds an authoritative page with explicit pagination flags. */
    private fun page(
        messages: List<TimelineMessageRecordFfi>,
        hasMoreBefore: Boolean = false,
    ) = TimelinePageFfi(messages = messages, hasMoreBefore = hasMoreBefore, hasMoreAfter = false)

    /** A plain authoritative chat row, ordered by its own position in history. */
    private fun record(
        messageId: String,
        timelineAt: ULong = 100uL,
    ) = timelineRecord(messageId = messageId, timelineAt = timelineAt)

    /** Owns a controller around one scripted subscription and tears it down afterwards. */
    private suspend fun withController(
        subscription: ScriptedConversationTimelineSubscription,
        block: suspend (ConversationController) -> Unit,
    ) {
        val scripted =
            ScriptedConversationLiveSubscriptions(
                timelineScripts = listOf(subscription),
                group = conversationTimelineTestGroup(),
            )
        val controller =
            ConversationController(
                appState = conversationTimelineTestAppState(scripted.subscriptions),
                initialGroup = conversationTimelineTestGroup(),
                initialMemberSnapshot = conversationTimelineMemberSnapshot(),
                groupRosterReader = { _, _ -> conversationTimelineGroupRoster() },
                startOnConstruction = true,
            )
        try {
            // Paging needs both the opening window and its active subscription.
            awaitConversationCondition {
                controller.timelineSubscription === subscription && controller.hasMoreBefore
            }
            block(controller)
        } finally {
            controller.onCleared()
            awaitOpenedTimelineSubscriptionsClosed(scripted)
        }
    }

    private companion object {
        val SEED_ID = "aa".repeat(32)
        val OLDER_ID = "bb".repeat(32)
        val TARGET_ID = "cc".repeat(32)
        val RETRYABLE_REASONS =
            listOf(ConversationWindowUnchangedReason.TIMED_OUT, ConversationWindowUnchangedReason.NOT_READY)
    }
}
