package dev.ipf.whitenoise.android.state

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.GroupRecoveryStatusFfi
import dev.ipf.marmotkit.MarmotEventFfi
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.conversation.hasVisibleGroupRecoveryCard
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/**
 * MDK parks a chat's convergence after a batch keeps failing, and holds the user's sends until another member's
 * activity or an explicit retry. These pin how the conversation reads that flag, keeps it under the recovery
 * guards, and runs and explains the Retry.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ConversationGroupConvergenceParkedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** The recovery read made on entry also publishes the parked flag, which shows the card on its own. */
    @Test
    fun theEntryReadPublishesTheParkedFlagAndShowsTheCard() =
        runBlocking {
            val controller = controller(testAppState(), parked = { true })

            controller.retryGroupRecoveryStatus()

            assertTrue(controller.groupConvergenceParked)
            assertTrue(
                hasVisibleGroupRecoveryCard(
                    status = controller.groupRecoveryStatus,
                    readFailed = controller.groupRecoveryReadFailed,
                    convergenceParked = controller.groupConvergenceParked,
                ),
            )
        }

    /** A read that fails says nothing about the chat, so the last known answer stays in both directions. */
    @Test
    fun aFailedReadKeepsTheLastKnownAnswer() =
        runBlocking {
            var answer: () -> Boolean = { true }
            val controller = controller(testAppState(), parked = { answer() })

            controller.retryGroupRecoveryStatus()
            answer = { error("mls state unavailable") }
            controller.retryGroupRecoveryStatus()
            assertTrue("a failed read cleared a parked chat", controller.groupConvergenceParked)

            answer = { false }
            controller.retryGroupRecoveryStatus()
            answer = { throw MarmotKitException.TransportClosed() }
            controller.retryGroupRecoveryStatus()
            assertFalse("a failed read invented a parked chat", controller.groupConvergenceParked)
        }

    /** A group-state edge that lands while the read is in flight makes its answer stale. */
    @Test
    fun aGroupStateEdgeDuringTheReadRejectsItsAnswer() =
        runBlocking {
            lateinit var controller: ConversationController
            controller =
                controller(testAppState(), parked = {
                    controller.applyGroupStateForTest(conversationTimelineTestGroup())
                    true
                })

            controller.retryGroupRecoveryStatus()

            assertFalse(controller.groupConvergenceParked)
        }

    /** Replacing the Marmot runtime while the read is in flight rejects its answer. */
    @Test
    fun aReplacedRuntimeCannotPublishTheParkedFlag() =
        runBlocking {
            val appState = testAppState()
            val controller =
                controller(appState, parked = {
                    replaceRuntimeOwner(appState)
                    true
                })

            controller.retryGroupRecoveryStatus()

            assertFalse(controller.groupConvergenceParked)
        }

    /** Disposing the controller while the read is in flight rejects its answer. */
    @Test
    fun aDisposedControllerCannotPublishTheParkedFlag() =
        runBlocking {
            lateinit var controller: ConversationController
            controller =
                controller(testAppState(), parked = {
                    controller.onCleared()
                    true
                })

            controller.retryGroupRecoveryStatus()

            assertFalse(controller.groupConvergenceParked)
        }

    /** A retry that gets the chat moving clears the card, with progress shown only while it runs. */
    @Test
    fun aSuccessfulRetryClearsTheParkedState() =
        runBlocking {
            var parked = true
            var retries = 0
            lateinit var controller: ConversationController
            controller =
                controller(
                    testAppState(),
                    parked = { parked },
                    retry = { account, group ->
                        assertEquals(ACCOUNT_REF, account)
                        assertEquals(conversationTimelineTestGroup().groupIdHex, group)
                        assertTrue(controller.groupConvergenceRetrying)
                        assertTrue(controller.groupRecoveryMutationInFlight)
                        retries += 1
                        parked = false
                    },
                )
            controller.retryGroupRecoveryStatus()

            controller.retryGroupConvergence()

            assertEquals(1, retries)
            assertFalse(controller.groupConvergenceParked)
            assertNull(controller.groupConvergenceRetryFailure)
            assertFalse(controller.groupConvergenceRetrying)
            assertFalse(controller.groupRecoveryMutationInFlight)
        }

    /** A retry MDK accepted that leaves the chat parked says so instead of looking like success. */
    @Test
    fun aRetryThatLeavesTheChatParkedExplainsIt() =
        runBlocking {
            val controller = controller(testAppState(), parked = { true }, retry = { _, _ -> })
            controller.retryGroupRecoveryStatus()

            controller.retryGroupConvergence()

            assertTrue(controller.groupConvergenceParked)
            assertEquals(R.string.group_convergence_retry_still_parked, controller.groupConvergenceRetryFailure)
            assertFalse(controller.groupRecoveryMutationInFlight)
        }

    /** A retry that fails maps the failure to a localized reason and keeps the chat parked. */
    @Test
    fun aFailedRetryShowsALocalizedReason() =
        runBlocking {
            var failure: Throwable = IllegalStateException("connect relay failed: wss://relay.invalid")
            val controller = controller(testAppState(), parked = { true }, retry = { _, _ -> throw failure })
            controller.retryGroupRecoveryStatus()

            controller.retryGroupConvergence()
            assertEquals(R.string.toast_send_connection_failed, controller.groupConvergenceRetryFailure)

            failure = IllegalStateException("illegal transition from PendingPublish")
            controller.retryGroupConvergence()
            assertEquals(R.string.group_convergence_retry_failed, controller.groupConvergenceRetryFailure)
            assertTrue(controller.groupConvergenceParked)
            assertFalse(controller.groupConvergenceRetrying)
        }

    /** Retry is only for a parked chat, and a second tap while one runs does nothing. */
    @Test
    fun retryRunsOnlyForAParkedChatAndOnlyOnce() =
        runBlocking {
            var retries = 0
            val healthy = controller(testAppState(), parked = { false }, retry = { _, _ -> retries += 1 })
            healthy.retryGroupRecoveryStatus()
            healthy.retryGroupConvergence()
            assertEquals("a healthy chat was retried", 0, retries)

            lateinit var parked: ConversationController
            parked =
                controller(testAppState(), parked = { true }, retry = { _, _ ->
                    retries += 1
                    parked.retryGroupConvergence()
                })
            parked.retryGroupRecoveryStatus()
            parked.retryGroupConvergence()
            assertEquals("a second tap started another retry", 1, retries)
        }

    /** Opening the stream reads once, and only this chat's own raw group-state event reads again. */
    @Test
    fun theOpenAndThisChatsRawEventReReadTheFlag() =
        runBlocking {
            val reads = AtomicInteger(0)
            val groupIdHex = conversationTimelineTestGroup().groupIdHex
            val stream =
                ScriptedStream(
                    listOf(
                        MarmotEventFfi.GroupStateUpdated(ACCOUNT_ID, ACCOUNT_REF, "cc".repeat(32)),
                        MarmotEventFfi.GroupStateUpdated(ACCOUNT_ID, "bob", groupIdHex),
                        MarmotEventFfi.GroupStateUpdated(ACCOUNT_ID, ACCOUNT_REF, groupIdHex.uppercase()),
                    ),
                )
            val controller = controller(testAppState(), events = { stream }, parked = { reads.incrementAndGet() > 0 })

            runLoopUntil(controller) { reads.get() >= 2 && stream.drained }
            delay(SETTLE_MILLIS)

            assertEquals("the open plus this chat's own event", 2, reads.get())
            assertTrue(controller.groupConvergenceParked)
        }

    /** An edge that passes before the stream opens is still caught, because the first open reads too. */
    @Test
    fun theFirstOpenReadsWithoutWaitingForAnEvent() =
        runBlocking {
            val stream = ScriptedStream(emptyList())
            val controller = controller(testAppState(), events = { stream }, parked = { true })

            runLoopUntil(controller) { controller.groupConvergenceParked }

            assertTrue(controller.groupConvergenceParked)
        }

    /** Nothing opens or reads before the conversation's first open settles, and cancelling destroys the stream. */
    @Test
    fun theLoopWaitsForTheOpenAndDestroysItsStreamOnCancel() =
        runBlocking {
            val opened = CompletableDeferred<Unit>()
            val reads = AtomicInteger(0)
            val stream = ScriptedStream(emptyList())
            val controller = controller(testAppState(), events = { stream }, parked = { reads.incrementAndGet() > 0 })
            val loop =
                CoroutineScope(Job() + Dispatchers.Default).launch {
                    val awaitOpen: suspend () -> Unit = { opened.await() }
                    controller.runGroupConvergenceEventLoop(ACCOUNT_REF, awaitOpen, retryMillis = 1L)
                }
            try {
                delay(SETTLE_MILLIS)
                assertEquals("a read ran ahead of the open", 0, reads.get())
                opened.complete(Unit)
                withTimeout(EVENT_TIMEOUT_MILLIS) { while (reads.get() == 0) delay(POLL_MILLIS) }
            } finally {
                loop.cancel()
                loop.join()
            }

            assertEquals(1, stream.destroyed.get())
        }

    /** A stream that ends is destroyed and reopened, and every reopen reads the flag again. */
    @Test
    fun anEndedStreamIsReopenedAndReReads() = assertReopens(ScriptedStream.End.CLOSE)

    /** A stream that fails is destroyed and reopened, and every reopen reads the flag again. */
    @Test
    fun aFailedStreamIsReopenedAndReReads() = assertReopens(ScriptedStream.End.FAIL)

    /** Requests made while a read runs fold into one more read after it, never one read each. */
    @Test
    fun overlappingRequestsFoldIntoOneFollowUpRead() =
        runBlocking {
            val firstRead = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val reads = AtomicInteger(0)
            val controller =
                controller(testAppState(), parked = {
                    if (reads.incrementAndGet() == 1) {
                        firstRead.complete(Unit)
                        release.await()
                    }
                    true
                })
            val scope = CoroutineScope(Job() + Dispatchers.Default)
            val first = scope.launch { controller.requestGroupConvergenceRead() }
            firstRead.await()
            repeat(3) { controller.requestGroupConvergenceRead() }
            release.complete(Unit)
            first.join()

            assertEquals(2, reads.get())
            assertTrue(controller.groupConvergenceParked)
        }

    /** Returning to the foreground before the first open settles leaves the read to the open itself. */
    @Test
    fun aForegroundReturnBeforeTheOpenDoesNotRead() =
        runBlocking {
            val reads = AtomicInteger(0)
            val controller = controller(testAppState(), parked = { reads.incrementAndGet() > 0 })

            controller.refreshGroupConvergenceOnStart()

            assertEquals(0, reads.get())
        }

    /** Runs the event loop with an open conversation and a short reopen pause until [done], then cancels it. */
    private suspend fun runLoopUntil(
        controller: ConversationController,
        done: () -> Boolean,
    ) {
        val loop =
            CoroutineScope(Job() + Dispatchers.Default).launch {
                controller.runGroupConvergenceEventLoop(ACCOUNT_REF, awaitOpen = {}, retryMillis = 1L)
            }
        try {
            withTimeout(EVENT_TIMEOUT_MILLIS) { while (!done()) delay(POLL_MILLIS) }
        } finally {
            loop.cancel()
            loop.join()
        }
    }

    /** Opens streams that end the given way and checks each is destroyed and each reopen reads the flag. */
    private fun assertReopens(end: ScriptedStream.End) =
        runBlocking {
            val reads = AtomicInteger(0)
            val streams = mutableListOf<ScriptedStream>()
            val controller =
                controller(
                    testAppState(),
                    events = { ScriptedStream(emptyList(), end).also { synchronized(streams) { streams += it } } },
                    parked = { reads.incrementAndGet() > 0 },
                )

            runLoopUntil(controller) { synchronized(streams) { streams.size } >= 3 && reads.get() >= 3 }

            val ended = synchronized(streams) { streams.dropLast(1) }
            assertTrue("every ended stream is destroyed", ended.all { it.destroyed.get() == 1 })
            assertTrue("each open reads the flag", reads.get() >= ended.size)
        }

    /** The localized reason table, stated without a controller around it. */
    @Test
    fun retryFailureMessagesNeverExplainAChatThatIsMoving() {
        val relay = IllegalStateException("connect relay failed")
        assertNull(groupConvergenceRetryFailureMessage(relay, stillParked = false))
        assertNull(groupConvergenceRetryFailureMessage(null, stillParked = false))
        assertNull(groupConvergenceRetryFailureMessage(null, stillParked = null))
        assertEquals(
            R.string.group_convergence_retry_still_parked,
            groupConvergenceRetryFailureMessage(null, stillParked = true),
        )
        assertEquals(R.string.toast_send_connection_failed, groupConvergenceRetryFailureMessage(relay, null))
        assertEquals(
            R.string.toast_chat_still_loading,
            groupConvergenceRetryFailureMessage(MarmotKitException.GroupHydrationPending("aa"), true),
        )
        assertEquals(
            R.string.group_convergence_retry_failed,
            groupConvergenceRetryFailureMessage(IllegalStateException("engine"), true),
        )
    }

    /** Applies the existing runtime-replacement publication fence without wiping test data. */
    private fun replaceRuntimeOwner(appState: WhiteNoiseAppState) {
        val state =
            DestructiveAccountWipeRuntimeState(
                activeAccountRef = appState.activeAccountRef,
                activeConversationAccountRef = null,
                activeConversationGroupIdHex = null,
                runtimeGeneration = appState.runtimeGeneration + 1,
            )
        WhiteNoiseAppState::class.java
            .getDeclaredMethod("applyDestructiveWipeRuntimeState", DestructiveAccountWipeRuntimeState::class.java)
            .apply { isAccessible = true }
            .invoke(appState, state)
    }

    /** A healthy recovery status for this suite's group, so only the parked flag can show the card. */
    private fun healthyStatus() =
        GroupRecoveryStatusFfi(
            groupIdHex = conversationTimelineTestGroup().groupIdHex,
            automaticRecoveryFailed = false,
            pendingReinvites = 0u,
            failedReinvites = 0u,
            rejoinInvitations = emptyList(),
            historyMayBeIncomplete = false,
            historyNoticeIds = emptyList(),
        )

    /** A conversation controller whose parked read, retry and raw event stream are injected. */
    private fun controller(
        appState: WhiteNoiseAppState,
        parked: suspend () -> Boolean,
        retry: suspend (String, String) -> Unit = { _, _ -> error("unexpected retry") },
        events: suspend () -> RuntimeEventStream = { error("no event stream in this test") },
    ) = ConversationController(
        appState = appState,
        initialGroup = conversationTimelineTestGroup(),
        groupRecoveryStatusReader = { _, _ -> healthyStatus() },
        groupConvergenceParkedReader = { _, _ -> parked() },
        groupConvergenceRetrier = retry,
        runtimeEventStreamOpener = events,
        historyNoticesReader = { emptyList() },
    )

    /** One signed-in account, with no live subscriptions to start. */
    private fun testAppState(): WhiteNoiseAppState =
        WhiteNoiseAppState(
            context = context,
            draftStore = DraftStore(NoDrafts),
            accountIdHexResolver = { ACCOUNT_ID },
            accounts =
                listOf(
                    AccountSummaryFfi(
                        label = ACCOUNT_REF,
                        accountIdHex = ACCOUNT_ID,
                        localSigning = true,
                        externalSigning = false,
                        signedOut = false,
                        running = true,
                        unlinked = false,
                    ),
                ),
            activeAccountRef = ACCOUNT_REF,
        )

    /** A scripted runtime event stream that hands out [events], then ends the way [end] says. */
    private class ScriptedStream(
        events: List<MarmotEventFfi>,
        private val end: End = End.IDLE,
    ) : RuntimeEventStream {
        /** How the stream behaves once its scripted events are used up. */
        enum class End { IDLE, CLOSE, FAIL }

        private val queue = ArrayDeque(events)
        val destroyed = AtomicInteger(0)

        /** Whether every scripted event has been handed out. */
        val drained: Boolean get() = synchronized(queue) { queue.isEmpty() }

        /** Hands out the next scripted event, then idles, ends or fails as configured. */
        override suspend fun next(): MarmotEventFfi? {
            synchronized(queue) { queue.removeFirstOrNull() }?.let { return it }
            return when (end) {
                End.IDLE -> awaitCancellation()
                End.CLOSE -> null
                End.FAIL -> throw MarmotKitException.TransportClosed()
            }
        }

        /** Counts releases, so a test can prove the stream was destroyed exactly once. */
        override fun destroy() {
            destroyed.incrementAndGet()
        }
    }

    private object NoDrafts : DraftPersistence {
        /** No drafts participate in convergence presentation. */
        override fun read(): Map<String, String> = emptyMap()

        /** Discards writes, drafts are irrelevant to this suite. */
        override fun write(
            key: String,
            value: String?,
        ) = Unit
    }

    private companion object {
        const val ACCOUNT_REF = "alice"
        val ACCOUNT_ID = "aa".repeat(32)
        const val EVENT_TIMEOUT_MILLIS = 5_000L
        const val POLL_MILLIS = 10L
        const val SETTLE_MILLIS = 100L
    }
}
