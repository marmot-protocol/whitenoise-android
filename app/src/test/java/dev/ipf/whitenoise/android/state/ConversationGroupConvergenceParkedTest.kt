package dev.ipf.whitenoise.android.state

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.EventsSubscriptionInterface
import dev.ipf.marmotkit.GroupRecoveryStatusFfi
import dev.ipf.marmotkit.MarmotEventFfi
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.conversation.hasVisibleGroupRecoveryCard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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

    /** A raw group-state event for this chat re-reads the flag, while events for other chats are ignored. */
    @Test
    fun aRawGroupStateEventForThisChatReReadsTheFlag() =
        runBlocking {
            val reads = AtomicInteger(0)
            val groupIdHex = conversationTimelineTestGroup().groupIdHex
            val events =
                ArrayDeque(
                    listOf(
                        MarmotEventFfi.GroupStateUpdated(ACCOUNT_ID, ACCOUNT_REF, "cc".repeat(32)),
                        MarmotEventFfi.GroupStateUpdated(ACCOUNT_ID, "bob", groupIdHex),
                        MarmotEventFfi.GroupStateUpdated(ACCOUNT_ID, ACCOUNT_REF, groupIdHex.uppercase()),
                    ),
                )
            val stream =
                object : EventsSubscriptionInterface {
                    /** Hands out the scripted events, then waits as an idle firehose would. */
                    override suspend fun next(): MarmotEventFfi? {
                        events.removeFirstOrNull()?.let { return it }
                        delay(Long.MAX_VALUE)
                        return null
                    }
                }
            val controller =
                controller(testAppState(), events = { stream }, parked = {
                    reads.incrementAndGet()
                    true
                })
            val scope = CoroutineScope(Job() + Dispatchers.Default)
            val loop = scope.launch { controller.runGroupConvergenceEventLoop(ACCOUNT_REF) }
            try {
                withTimeout(EVENT_TIMEOUT_MILLIS) {
                    while (!controller.groupConvergenceParked) delay(POLL_MILLIS)
                }
            } finally {
                loop.cancel()
            }

            assertEquals("only this chat's own event triggers a read", 1, reads.get())
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
        parked: () -> Boolean,
        retry: suspend (String, String) -> Unit = { _, _ -> error("unexpected retry") },
        events: suspend () -> EventsSubscriptionInterface = { error("no event stream in this test") },
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
    }
}
