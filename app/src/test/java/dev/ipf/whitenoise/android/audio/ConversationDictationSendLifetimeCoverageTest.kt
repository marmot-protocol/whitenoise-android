package dev.ipf.whitenoise.android.audio

import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises the production handoff with virtual time, including cancellation on both sides. */
@OptIn(ExperimentalCoroutinesApi::class)
class ConversationDictationSendLifetimeCoverageTest {
    private val request = ConversationDictationSendRequest("account", "group", 1L, "draft", "draft transcript")

    @Test
    fun pendingSendSurvivesOldDeadlineAndCompletesOnce() =
        runTest {
            var attempts = 0
            var pending = 0
            val result =
                async {
                    sendDictationUntilHandoff(20_000L, request.copy(onPendingShown = { pending += 1 })) {
                        attempts += 1
                        it.onPendingShown()
                        delay(60_000L)
                        true
                    }
                }
            runCurrent()
            advanceTimeBy(20_001L)
            runCurrent()
            assertTrue(result.isActive)
            assertEquals(1, pending)
            assertTrue(result.await() == true)
            assertEquals(1, attempts)
        }

    @Test
    fun admissionTimeoutCancelsAndJoinsWithoutRetry() =
        runTest {
            var attempts = 0
            var closed = false
            val result =
                sendDictationUntilHandoff(20_000L, request) {
                    attempts += 1
                    try {
                        awaitCancellation()
                    } finally {
                        closed = true
                    }
                }
            assertNull(result)
            assertTrue(closed)
            assertEquals(1, attempts)
        }

    @Test
    fun immediateRejectionDoesNotWaitForAdmissionDeadline() =
        runTest {
            assertEquals(false, sendDictationUntilHandoff(20_000L, request) { false })
            assertEquals(0L, testScheduler.currentTime)
        }

    @Test
    fun terminalFailureAfterPendingReturnsToOrdinarySender() =
        runTest {
            var failed = false
            val result =
                sendDictationUntilHandoff(20_000L, request) {
                    it.onPendingShown()
                    delay(30_000L)
                    failed = true
                    false
                }
            assertEquals(false, result)
            assertTrue(failed)
        }

    @Test
    fun processCancellationStillCancelsPendingTransport() =
        runTest {
            val started = CompletableDeferred<Unit>()
            var closed = false
            val result =
                async {
                    sendDictationUntilHandoff(20_000L, request) {
                        it.onPendingShown()
                        started.complete(Unit)
                        try {
                            awaitCancellation()
                        } finally {
                            closed = true
                        }
                    }
                }
            started.await()
            result.cancelAndJoin()
            assertTrue(result.isCancelled)
            assertTrue(closed)
        }

    @Test
    fun ownerTeardownAndResetLeavePendingTransportAliveWithoutAnotherOutcome() =
        runTest {
            val draft = TextFieldValue("draft")
            val target = ConversationDictationTarget("account", "group", draft, 1L, ConversationDictationMode.InApp)
            val recovery =
                ConversationDictationDraftRecovery(
                    { _, _ -> ConversationDictationDraftSnapshot(draft, 1L) },
                    { _, _, _, _ -> 2L },
                )
            val owner = ConversationDictationCompletionDispatchOwner { recovery }
            var session: Long? = 1L
            var pending = 0
            var terminal = 0
            var attempts = 0
            var finished = false
            owner.launchDispatch(
                this,
                ConversationDictationCompletionDispatchOwner.DispatchContext(1L, target, request, target),
                ConversationDictationCompletionDispatchOwner.Transport(20_000L) {
                    attempts += 1
                    assertTrue(it.beginDispatch())
                    it.onPendingShown()
                    delay(60_000L)
                    finished = true
                    true
                },
                ConversationDictationCompletionDispatchOwner.DispatchCallbacks(
                    ConversationDictationCompletionDispatchOwner.DispatchGuards(
                        currentSessionId = { session },
                        processing = { session != null },
                        emptyDraft = { 2L },
                        restoreDraft = { _, _ -> 3L },
                    ),
                    ConversationDictationCompletionDispatchOwner.DispatchOutcomes(
                        onPending = {
                            pending += 1
                            owner.finishDispatch()
                            owner.cancelBeforeTransport()
                            session = null
                        },
                        onAccepted = { terminal += 1 },
                        onRetained = { _, _ -> terminal += 1 },
                    ),
                ),
            )
            runCurrent()
            owner.reset()
            advanceTimeBy(60_001L)
            runCurrent()
            assertTrue(finished)
            assertEquals(1, attempts)
            assertEquals(1, pending)
            assertEquals(0, terminal)
        }
}
