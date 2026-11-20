package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.marmotkit.PollTypeFfi
import dev.ipf.marmotkit.SendAcceptDispositionFfi
import dev.ipf.whitenoise.android.state.ConversationActionFixtures.GROUP_ID
import dev.ipf.whitenoise.android.state.ConversationActionFixtures.MESSAGE_ID
import dev.ipf.whitenoise.android.state.ConversationActionFixtures.TARGET_REF
import dev.ipf.whitenoise.android.state.ConversationActionFixtures.appState
import dev.ipf.whitenoise.android.state.ConversationActionFixtures.controller
import dev.ipf.whitenoise.android.state.ConversationActionFixtures.native
import dev.ipf.whitenoise.android.state.ConversationActionFixtures.summary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The Android poll boundary uses the pinned account and serializes with other group sends. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class PollConversationActionsTest {
    /** A vote waits for another commit on the same group, then reaches MDK once with the pinned native ids. */
    @Test
    fun voteWaitsForOtherGroupMutationAndUsesPinnedNativeIds() =
        runTest {
            val entered = CountDownLatch(1)
            // Written on the native call's thread and read here, so the list is copy-on-write.
            val calls = CopyOnWriteArrayList<List<Any?>>()
            val native =
                native { name, args ->
                    assertEquals("castPollVote", name)
                    calls += args.take(4)
                    entered.countDown()
                    summary()
                }
            val state = appState(native)
            val controller = controller(state)
            val held = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val otherMutation =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    state.withGroupCommitLock(TARGET_REF, GROUP_ID) {
                        held.complete(Unit)
                        release.await()
                    }
                }
            held.await()
            val vote =
                async(start = CoroutineStart.UNDISPATCHED) {
                    controller.castPollVote(MESSAGE_ID, listOf("native-option"))
                }
            assertFalse(entered.await(150, TimeUnit.MILLISECONDS))
            release.complete(Unit)
            otherMutation.join()
            assertEquals(SendAcceptDispositionFfi.PUBLISHED, vote.await())
            assertEquals(listOf(listOf(TARGET_REF, GROUP_ID, MESSAGE_ID, listOf("native-option"))), calls)
            val logs = ShadowLog.getLogsForTag("WNPolls").joinToString { it.msg }
            assertTrue(logs.contains("outcome=PUBLISHED"))
            listOf(TARGET_REF, GROUP_ID, MESSAGE_ID, "native-option").forEach { assertFalse(logs.contains(it)) }
        }

    /** Creating a poll waits for another commit on the same group before it reaches MDK. */
    @Test
    fun pollCreationAlsoWaitsForTheGroupMutationLock() =
        runTest {
            val entered = CountDownLatch(1)
            val state =
                appState(
                    native { name, _ ->
                        assertEquals("createTaggedPoll", name)
                        entered.countDown()
                        summary()
                    },
                )
            val controller = controller(state)
            val release = CompletableDeferred<Unit>()
            val otherMutation =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    state.withGroupCommitLock(TARGET_REF, GROUP_ID) { release.await() }
                }
            val creation =
                async(start = CoroutineStart.UNDISPATCHED) {
                    controller.createPoll(
                        "Lunch?",
                        listOf("Soup", "Salad"),
                        PollTypeFfi.SINGLE_CHOICE,
                        null,
                        controller.snapshotReply(),
                    )
                }
            assertFalse(entered.await(150, TimeUnit.MILLISECONDS))
            release.complete(Unit)
            otherMutation.join()
            assertTrue(creation.await())
        }

    @Test
    fun nativeFailureAllowsANewVote() =
        runTest {
            var attempt = 0
            val state =
                appState(
                    native { name, _ ->
                        assertEquals("castPollVote", name)
                        attempt++
                        if (attempt == 1) error("native vote rejected")
                        summary()
                    },
                )
            val controller = controller(state)
            assertNull(controller.castPollVote(MESSAGE_ID, listOf("native-option")))
            assertEquals(
                SendAcceptDispositionFfi.PUBLISHED,
                controller.castPollVote(MESSAGE_ID, listOf("native-option")),
            )
            assertEquals(2, attempt)
        }

    @Test
    fun nativeVoteRejectionHasSafeSupportAttributionWithoutAnAutomaticRetry() =
        runTest {
            var attempts = 0
            val state =
                appState(
                    native { name, _ ->
                        assertEquals("castPollVote", name)
                        attempts++
                        throw MarmotKitException.Runtime(
                            "invalid app message payload: poll is closed at the response event timestamp",
                        )
                    },
                )

            assertNull(controller(state).castPollVote(MESSAGE_ID, listOf("native-option")))
            assertEquals(1, attempts)
            val report = requireNotNull(state.toast?.diagnosticReport)
            assertTrue(report.contains("marmot=Runtime"))
            assertTrue(report.contains("detail=poll_vote_reason=POLL_RESPONSE_TIME_REJECTED"))
            assertFalse(report.contains("poll is closed at the response event timestamp"))
            val logs = ShadowLog.getLogsForTag("WNPolls").joinToString { it.msg }
            listOf(TARGET_REF, GROUP_ID, MESSAGE_ID, "native-option").forEach {
                assertFalse(report.contains(it))
                assertFalse(logs.contains(it))
            }
        }

    @Test
    fun cancelledVoteDoesNotProduceAFailureReportOrRetry() =
        runTest {
            val cancellation = CancellationException("vote cancelled")
            var attempts = 0
            val state =
                appState(
                    native { name, _ ->
                        if (name == "castPollVote") attempts++
                        throw cancellation
                    },
                )
            val failure = runCatching { controller(state).castPollVote(MESSAGE_ID, listOf("native-option")) }
            assertTrue(failure.exceptionOrNull() is CancellationException)
            assertEquals(cancellation.message, failure.exceptionOrNull()?.message)
            assertNull(state.toast)
            assertEquals(1, attempts)
        }

    @Test
    fun preservesPendingAndUnknownNativeOutcomes() =
        runTest {
            val dispositions =
                listOf(
                    SendAcceptDispositionFfi.ACCEPTED_PENDING,
                    SendAcceptDispositionFfi.COMPLETION_UNKNOWN,
                )
            for (disposition in dispositions) {
                val controller = controller(appState(native { _, _ -> summary(disposition) }))
                assertEquals(disposition, controller.castPollVote(MESSAGE_ID, listOf("native-option")))
            }
        }
}
