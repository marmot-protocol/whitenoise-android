package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.diagnostics.PerformanceLayer
import dev.ipf.whitenoise.android.diagnostics.PerformanceOperation
import dev.ipf.whitenoise.android.diagnostics.PerformancePhase
import dev.ipf.whitenoise.android.diagnostics.PerformanceResult
import dev.ipf.whitenoise.android.diagnostics.PerformanceTrace
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationNetworkRecoveryTest {
    /** One generation reaches both Android projections and its first rendered frame. */
    @Test
    fun successfulRecoveryCarriesOneGenerationThroughTheVisibleFrame() {
        var now = 0L
        val recorded = mutableListOf<PerformancePhase>()
        val diagnostics =
            NotificationNetworkRecoveryDiagnostics(
                nowMillis = { now },
                traceFactory = { performanceTrace(1L, now) },
                traceRecorder = { _, phase, _, _, _, _, _ -> recorded += phase },
            )

        diagnostics.networkRestored(1L)
        diagnostics.attemptStarted(1L, 1)
        now = 100L
        diagnostics.attemptPhase(
            generation = 1L,
            phase = PerformancePhase.NOTIFICATION_RECEIVER_READY,
            result = PerformanceResult.SUCCESS,
            layer = PerformanceLayer.ANDROID,
            attempt = 1,
        )
        now = 200L
        diagnostics.attemptPhase(
            generation = 1L,
            phase = PerformancePhase.ACCOUNT_CATCH_UP_START,
            result = PerformanceResult.PENDING,
            layer = PerformanceLayer.MDK,
            attempt = 1,
        )
        now = 700L
        diagnostics.catchUpSucceeded(1L, 1)
        now = 800L
        assertEquals(1L, diagnostics.chatListSubscriptionReceived())
        assertEquals(1L, diagnostics.timelineSubscriptionReceived())
        now = 850L
        assertTrue(diagnostics.chatListProjectionPublished(1L, 1))
        assertTrue(diagnostics.timelineProjectionPublished(1L, 1))
        now = 900L
        assertTrue(diagnostics.firstVisibleFrame(1L))

        assertEquals(
            listOf(
                PerformancePhase.NETWORK_RESTORED,
                PerformancePhase.RECOVERY_ATTEMPT,
                PerformancePhase.NOTIFICATION_RECEIVER_READY,
                PerformancePhase.ACCOUNT_CATCH_UP_START,
                PerformancePhase.ACCOUNT_SUBSCRIPTION_ACTIVATED,
                PerformancePhase.CURRENT_REPLAY_COMPLETE,
                PerformancePhase.DURABLE_INGEST_READY,
                PerformancePhase.ACCOUNT_CATCH_UP_READY,
                PerformancePhase.CHAT_LIST_SUBSCRIPTION_RECEIVED,
                PerformancePhase.TIMELINE_SUBSCRIPTION_RECEIVED,
                PerformancePhase.CHAT_LIST_PROJECTION_PUBLISHED,
                PerformancePhase.TIMELINE_PROJECTION_PUBLISHED,
                PerformancePhase.RECOVERY_FIRST_VISIBLE_FRAME,
            ),
            recorded,
        )
        assertEquals(900L, diagnostics.samples().last().elapsedMillis)
    }

    /** Exhaustion is a typed terminal phase and releases downstream attribution. */
    @Test
    fun exhaustedRecoveryRecordsFailureAndReleasesItsTrace() {
        val recorded = mutableListOf<Triple<PerformancePhase, PerformanceResult, PerformanceLayer>>()
        val diagnostics =
            NotificationNetworkRecoveryDiagnostics(
                nowMillis = { 100L },
                traceFactory = { performanceTrace(1L) },
                traceRecorder = { _, phase, _, result, layer, _, _ ->
                    recorded += Triple(phase, result, layer)
                },
            )

        diagnostics.networkRestored(1L)
        diagnostics.attemptStarted(1L, 4)
        diagnostics.retryExhausted(1L, 4, NotificationNetworkRecoveryOutcome.CatchUpFailed)

        assertEquals(
            Triple(
                PerformancePhase.RECOVERY_RETRY_EXHAUSTED,
                PerformanceResult.FAILURE,
                PerformanceLayer.MDK,
            ),
            recorded.last(),
        )
        assertEquals(null, diagnostics.chatListSubscriptionReceived())
    }

    /** Twenty controlled cycles produce the required percentile report within budget. */
    @Test
    fun twentyForegroundCyclesReportEveryRecoveryPhaseWithinBudget() {
        var now = 0L
        var generation = 0L
        val diagnostics =
            NotificationNetworkRecoveryDiagnostics(
                nowMillis = { now },
                traceFactory = { performanceTrace(generation, now) },
                traceRecorder = { _, _, _, _, _, _, _ -> },
            )

        repeat(20) { cycle ->
            generation = cycle + 1L
            diagnostics.networkRestored(generation)
            diagnostics.attemptStarted(generation, 1)
            now += 100L
            diagnostics.attemptPhase(
                generation,
                PerformancePhase.NOTIFICATION_RECEIVER_READY,
                PerformanceResult.SUCCESS,
                PerformanceLayer.ANDROID,
                1,
            )
            now += 100L
            diagnostics.attemptPhase(
                generation,
                PerformancePhase.ACCOUNT_CATCH_UP_START,
                PerformanceResult.PENDING,
                PerformanceLayer.MDK,
                1,
            )
            now += 500L
            diagnostics.catchUpSucceeded(generation, 1)
            now += 100L
            val receiptGeneration = requireNotNull(diagnostics.timelineSubscriptionReceived())
            now += 100L
            assertTrue(diagnostics.timelineProjectionPublished(receiptGeneration, 1))
            now += 100L
            assertTrue(diagnostics.firstVisibleFrame(receiptGeneration))
            now += 100L
        }

        val report = offlineRecoveryLatencyReport(diagnostics.samples())
        assertEquals(20, report.completedCycles)
        assertTrue(
            requireNotNull(report.phaseLatencies[PerformancePhase.ACCOUNT_SUBSCRIPTION_ACTIVATED]).maximumMillis <=
                3_000L,
        )
        assertTrue(
            requireNotNull(report.phaseLatencies[PerformancePhase.RECOVERY_FIRST_VISIBLE_FRAME]).maximumMillis <=
                5_000L,
        )
        assertEquals(900L, report.phaseLatencies[PerformancePhase.TIMELINE_PROJECTION_PUBLISHED]?.p95Millis)
    }

    /** A coalesced successor must not suppress phases from the attempt already running. */
    @Test
    fun newerPendingTraceDoesNotDiscardTheInFlightGeneration() {
        val traces = NotificationNetworkRecoveryPerformanceTraces()
        val first = performanceTrace(1L)
        val second = performanceTrace(2L)

        assertTrue(traces.begin(1L) { first })
        traces.activate(1L)
        assertTrue(traces.begin(2L) { second })

        assertEquals(first, traces.forGeneration(1L))
        assertEquals(second, traces.forGeneration(2L))
    }

    /** A late stale edge cannot move diagnostic ownership behind the newest edge. */
    @Test
    fun staleTraceCannotReplaceANewerGeneration() {
        val traces = NotificationNetworkRecoveryPerformanceTraces()
        val newer = performanceTrace(2L)

        assertTrue(traces.begin(2L) { newer })
        assertFalse(traces.begin(1L) { performanceTrace(1L) })

        assertEquals(null, traces.forGeneration(1L))
        assertEquals(newer, traces.forGeneration(2L))
    }

    /** Multiple queued edges retain only the newest successor beside the active trace. */
    @Test
    fun newestPendingTraceSupersedesOnlyTheUnattemptedGeneration() {
        val traces = NotificationNetworkRecoveryPerformanceTraces()
        val active = performanceTrace(1L)
        val newest = performanceTrace(3L)

        traces.begin(1L) { active }
        traces.activate(1L)
        traces.begin(2L) { performanceTrace(2L) }
        traces.begin(3L) { newest }

        assertEquals(active, traces.forGeneration(1L))
        assertEquals(null, traces.forGeneration(2L))
        assertEquals(newest, traces.forGeneration(3L))
    }

    @Test
    fun receiverRecoveryOverlapsOutboundWakeAndCatchUpStartsWhenReceiverReady() =
        runTest {
            val wakeStarted = CompletableDeferred<Unit>()
            val receiverStarted = CompletableDeferred<Unit>()
            val releaseWake = CompletableDeferred<Unit>()
            val releaseReceiver = CompletableDeferred<Boolean>()
            var catchUpRan = false

            val result =
                async {
                    runNotificationReconnectOnNetworkRestore(
                        wakeDurableOutbound = {
                            wakeStarted.complete(Unit)
                            releaseWake.await()
                        },
                        ensureNotificationReceiverActive = {
                            receiverStarted.complete(Unit)
                            releaseReceiver.await()
                        },
                        catchUpAccounts = {
                            catchUpRan = true
                            AccountCatchUpResult(AccountCatchUpOutcome.Succeeded)
                        },
                    )
                }

            wakeStarted.await()
            receiverStarted.await()
            releaseReceiver.complete(true)
            runCurrent()
            assertTrue("receiver readiness should start catch-up without waiting for outbound wake", catchUpRan)
            assertFalse("the attempt must still settle its outbound wake", result.isCompleted)

            releaseWake.complete(Unit)
            assertEquals(NotificationNetworkRecoveryOutcome.Success, result.await())
        }

    @Test
    fun unavailableReceiverLeavesCatchUpPending() =
        runTest {
            var catchUpRan = false

            val result =
                runNotificationReconnectOnNetworkRestore(
                    wakeDurableOutbound = {},
                    ensureNotificationReceiverActive = { false },
                    catchUpAccounts = {
                        catchUpRan = true
                        AccountCatchUpResult(AccountCatchUpOutcome.Succeeded)
                    },
                )

            assertEquals(NotificationNetworkRecoveryOutcome.ReceiverUnavailable, result)
            assertFalse(catchUpRan)
        }

    @Test
    fun failedCatchUpIsRetryable() =
        runTest {
            val result =
                runNotificationReconnectOnNetworkRestore(
                    wakeDurableOutbound = {},
                    ensureNotificationReceiverActive = { true },
                    catchUpAccounts = { AccountCatchUpResult(AccountCatchUpOutcome.Failed) },
                )

            assertEquals(NotificationNetworkRecoveryOutcome.CatchUpFailed, result)
        }

    /** Coalescing follows the replacement without repeating the recovery attempt. */
    @Test
    fun supersededCatchUpStaysWithinOneRecoveryAttempt() =
        runTest {
            var wakes = 0
            var receiverChecks = 0
            var catchUps = 0
            val result =
                runNotificationReconnectOnNetworkRestore(
                    wakeDurableOutbound = { wakes += 1 },
                    ensureNotificationReceiverActive = {
                        receiverChecks += 1
                        true
                    },
                    catchUpAccounts = {
                        catchUps += 1
                        AccountCatchUpResult(
                            if (catchUps == 1) {
                                AccountCatchUpOutcome.Superseded
                            } else {
                                AccountCatchUpOutcome.Succeeded
                            },
                        )
                    },
                )

            assertEquals(NotificationNetworkRecoveryOutcome.Success, result)
            assertEquals(1, wakes)
            assertEquals(1, receiverChecks)
            assertEquals(2, catchUps)
        }

    /** Coalescing churn returns to the outer retry budget after a finite number of joins. */
    @Test
    fun supersededCatchUpIsBoundedWithinRecoveryAttempt() =
        runTest {
            var catchUps = 0

            val result =
                runNotificationReconnectOnNetworkRestore(
                    wakeDurableOutbound = {},
                    ensureNotificationReceiverActive = { true },
                    catchUpAccounts = {
                        catchUps += 1
                        AccountCatchUpResult(AccountCatchUpOutcome.Superseded)
                    },
                    maxSupersededReplacements = 2,
                )

            assertEquals(NotificationNetworkRecoveryOutcome.CatchUpFailed, result)
            assertEquals(3, catchUps)
        }

    @Test
    fun drainRetriesTheSameGenerationUntilCatchUpSucceeds() =
        runTest {
            var requested = 1L
            var completed = 0L
            val attempts = mutableListOf<Pair<Long, Int>>()
            val retries = mutableListOf<Int>()

            drainNotificationNetworkRecovery(
                shouldContinue = { true },
                requestedGeneration = { requested },
                completedGeneration = { completed },
                runAttempt = { generation, attempt ->
                    attempts += generation to attempt
                    if (attempt == 1) {
                        NotificationNetworkRecoveryOutcome.ReceiverUnavailable
                    } else {
                        NotificationNetworkRecoveryOutcome.Success
                    }
                },
                markCompleted = { completed = it },
                awaitRetry = { _, attempt -> retries += attempt },
            )

            assertEquals(listOf(1L to 1, 1L to 2), attempts)
            assertEquals(listOf(1), retries)
            assertEquals(1L, completed)
        }

    @Test
    fun drainCoalescesAnInFlightEdgeToTheNewestGeneration() =
        runTest {
            var requested = 1L
            var completed = 0L
            val attemptedGenerations = mutableListOf<Long>()

            drainNotificationNetworkRecovery(
                shouldContinue = { true },
                requestedGeneration = { requested },
                completedGeneration = { completed },
                runAttempt = { generation, _ ->
                    attemptedGenerations += generation
                    if (generation == 1L) requested = 3L
                    NotificationNetworkRecoveryOutcome.Success
                },
                markCompleted = { completed = it },
                awaitRetry = { _, _ -> error("a successful recovery must not back off") },
            )

            assertEquals(listOf(1L, 3L), attemptedGenerations)
            assertEquals(3L, completed)
        }

    @Test
    fun newerGenerationStartsWithAFreshRetryBudget() =
        runTest {
            var requested = 1L
            var completed = 0L
            val attempts = mutableListOf<Pair<Long, Int>>()

            drainNotificationNetworkRecovery(
                shouldContinue = { true },
                requestedGeneration = { requested },
                completedGeneration = { completed },
                runAttempt = { generation, attempt ->
                    attempts += generation to attempt
                    if (generation == 1L) {
                        NotificationNetworkRecoveryOutcome.ReceiverUnavailable
                    } else {
                        NotificationNetworkRecoveryOutcome.Success
                    }
                },
                markCompleted = { completed = it },
                awaitRetry = { _, _ -> requested = 2L },
            )

            assertEquals(listOf(1L to 1, 2L to 1), attempts)
            assertEquals(2L, completed)
        }

    @Test
    fun drainRetainsTheGenerationWhenConnectivityDropsDuringRetry() =
        runTest {
            var online = true
            var completed = 0L

            drainNotificationNetworkRecovery(
                shouldContinue = { online },
                requestedGeneration = { 7L },
                completedGeneration = { completed },
                runAttempt = { _, _ -> NotificationNetworkRecoveryOutcome.CatchUpFailed },
                markCompleted = { completed = it },
                awaitRetry = { _, _ -> online = false },
            )

            assertEquals(0L, completed)
        }

    /** One bad network generation opens the circuit instead of retrying forever. */
    @Test
    fun drainStopsWhenTheRetryBudgetIsExhausted() =
        runTest {
            val attempts = mutableListOf<Int>()
            val retries = mutableListOf<Int>()
            var exhausted: Pair<Long, NotificationNetworkRecoveryOutcome>? = null

            drainNotificationNetworkRecovery(
                shouldContinue = { true },
                requestedGeneration = { 7L },
                completedGeneration = { 0L },
                runAttempt = { _, attempt ->
                    attempts += attempt
                    NotificationNetworkRecoveryOutcome.CatchUpFailed
                },
                markCompleted = { error("a failed generation must not be completed") },
                awaitRetry = { _, attempt -> retries += attempt },
                maxAttempts = 4,
                onRetryExhausted = { generation, outcome -> exhausted = generation to outcome },
            )

            assertEquals(listOf(1, 2, 3, 4), attempts)
            assertEquals(listOf(1, 2, 3), retries)
            assertEquals(7L to NotificationNetworkRecoveryOutcome.CatchUpFailed, exhausted)
        }

    /** A stopped generation stays quiet until a genuinely newer network edge. */
    @Test
    fun exhaustedCoordinatorRequiresANewerGeneration() =
        runTest {
            var attempts = 0
            var wakes = 0
            var completedDrains = 0
            val coordinator =
                NotificationNetworkRecoveryCoordinator(
                    scope = this,
                    shouldContinue = { true },
                    wakeDurableOutbound = {
                        wakes += 1
                        true
                    },
                    ensureNotificationReceiverActive = { true },
                    catchUpAccounts = {
                        attempts += 1
                        AccountCatchUpResult(AccountCatchUpOutcome.Failed)
                    },
                    awaitRetry = { _, _ -> },
                    onDrainCompleted = { completedDrains += 1 },
                    diagnostics =
                        NotificationNetworkRecoveryDiagnostics(
                            nowMillis = { 0L },
                            traceFactory = { null },
                            traceRecorder = { _, _, _, _, _, _, _ -> },
                        ),
                )

            coordinator.noteNetworkRestored(1L)
            advanceUntilIdle()
            assertEquals(4, attempts)
            assertEquals("one edge must issue one outbound wake", 1, wakes)
            assertEquals("terminal exhaustion must offer one independent handoff", 1, completedDrains)

            coordinator.resumeIfPending()
            advanceUntilIdle()
            assertEquals("a lifecycle resume alone must not reopen the circuit", 4, attempts)
            assertEquals("a lifecycle resume must not repeat the handoff", 1, completedDrains)

            coordinator.noteNetworkRestored(2L)
            advanceUntilIdle()
            assertEquals(8, attempts)
            assertEquals(2, wakes)
            assertEquals(2, completedDrains)
        }

    /** A callback after exhaustion cannot retry the same durable wake outside the coordinator. */
    @Test
    fun exhaustedRecoveryBlocksIndependentDrainForTheSameTrigger() =
        runTest {
            val circuit = NotificationPushWakeRecoveryCircuit()
            var catchUps = 0
            val pendingPushWakeGeneration = 11L
            val coordinator =
                NotificationNetworkRecoveryCoordinator(
                    scope = this,
                    shouldContinue = { true },
                    wakeDurableOutbound = { true },
                    ensureNotificationReceiverActive = { true },
                    catchUpAccounts = {
                        catchUps += 1
                        AccountCatchUpResult(AccountCatchUpOutcome.Failed)
                    },
                    awaitRetry = { _, _ -> },
                    onDrainCompleted = {},
                    onRecoveryAttemptStarted = { networkGeneration ->
                        circuit.noteRecoveryAttempt(networkGeneration, pendingPushWakeGeneration)
                    },
                    onRecoveryExhausted = { networkGeneration, _ ->
                        circuit.noteRecoveryExhausted(networkGeneration)
                    },
                    diagnostics =
                        NotificationNetworkRecoveryDiagnostics(
                            nowMillis = { 0L },
                            traceFactory = { null },
                            traceRecorder = { _, _, _, _, _, _, _ -> },
                        ),
                )

            coordinator.noteNetworkRestored(7L)
            advanceUntilIdle()
            assertEquals(4, catchUps)

            if (circuit.claimIndependentDrain(7L, pendingPushWakeGeneration)) catchUps += 1

            assertEquals("the same trigger must remain capped at four attempts", 4, catchUps)
            assertTrue(circuit.claimIndependentDrain(8L, pendingPushWakeGeneration))
            assertFalse(circuit.claimIndependentDrain(8L, pendingPushWakeGeneration))
            assertTrue(circuit.claimIndependentDrain(8L, pendingPushWakeGeneration + 1L))
        }

    /** A wake recorded during the final attempt is handed to exactly one independent drain. */
    @Test
    fun exhaustionHandsPushWakeRecordedDuringFinalAttemptToIndependentDrain() =
        runTest {
            val circuit = NotificationPushWakeRecoveryCircuit()
            var attempts = 0
            var independentDrains = 0
            var pendingPushWakeGeneration = 20L
            val coordinator =
                NotificationNetworkRecoveryCoordinator(
                    scope = this,
                    shouldContinue = { true },
                    wakeDurableOutbound = { true },
                    ensureNotificationReceiverActive = { true },
                    catchUpAccounts = {
                        attempts += 1
                        if (attempts == 4) pendingPushWakeGeneration += 1L
                        AccountCatchUpResult(AccountCatchUpOutcome.Failed)
                    },
                    awaitRetry = { _, _ -> },
                    onDrainCompleted = {
                        if (circuit.claimIndependentDrain(9L, pendingPushWakeGeneration)) {
                            independentDrains += 1
                        }
                    },
                    onRecoveryAttemptStarted = { networkGeneration ->
                        circuit.noteRecoveryAttempt(networkGeneration, pendingPushWakeGeneration)
                    },
                    onRecoveryExhausted = { networkGeneration, _ ->
                        circuit.noteRecoveryExhausted(networkGeneration)
                    },
                    diagnostics =
                        NotificationNetworkRecoveryDiagnostics(
                            nowMillis = { 0L },
                            traceFactory = { null },
                            traceRecorder = { _, _, _, _, _, _, _ -> },
                        ),
                )

            coordinator.noteNetworkRestored(9L)
            advanceUntilIdle()

            assertEquals(4, attempts)
            assertEquals(1, independentDrains)
            assertFalse(circuit.claimIndependentDrain(9L, 20L))
            assertFalse(circuit.claimIndependentDrain(9L, pendingPushWakeGeneration))
        }

    /** A newer claim keeps delayed old work blocked without closing the circuit to later generations. */
    @Test
    fun newerClaimDoesNotReopenAnExhaustedTrigger() {
        val circuit = NotificationPushWakeRecoveryCircuit()

        circuit.noteRecoveryAttempt(networkGeneration = 7L, pushWakeGeneration = 11L)
        circuit.noteRecoveryExhausted(networkGeneration = 7L)
        assertTrue(circuit.claimIndependentDrain(networkGeneration = 8L, pushWakeGeneration = 11L))

        assertFalse(circuit.claimIndependentDrain(networkGeneration = 7L, pushWakeGeneration = 11L))
        assertTrue(circuit.claimIndependentDrain(networkGeneration = 9L, pushWakeGeneration = 12L))
    }

    /** Successful recovery may hand remaining durable work to its next drain. */
    @Test
    fun successfulCoordinatorRunsTheCompletionHandoff() =
        runTest {
            var completedDrains = 0
            val coordinator =
                NotificationNetworkRecoveryCoordinator(
                    scope = this,
                    shouldContinue = { true },
                    wakeDurableOutbound = { true },
                    ensureNotificationReceiverActive = { true },
                    catchUpAccounts = { AccountCatchUpResult(AccountCatchUpOutcome.Succeeded) },
                    awaitRetry = { _, _ -> error("success must not retry") },
                    onDrainCompleted = { completedDrains += 1 },
                    diagnostics =
                        NotificationNetworkRecoveryDiagnostics(
                            nowMillis = { 0L },
                            traceFactory = { null },
                            traceRecorder = { _, _, _, _, _, _, _ -> },
                        ),
                )

            coordinator.noteNetworkRestored(1L)
            advanceUntilIdle()

            assertEquals(1, completedDrains)
        }

    @Test
    fun retryDelayIsPromptAndBounded() {
        assertEquals(500L, notificationNetworkRecoveryRetryDelayMillis(1))
        assertEquals(1_000L, notificationNetworkRecoveryRetryDelayMillis(2))
        assertEquals(8_000L, notificationNetworkRecoveryRetryDelayMillis(100))
    }

    /** Creates an opaque trace token for generation-ownership tests. */
    private fun performanceTrace(
        generation: Long,
        startedAtMillis: Long = 0L,
    ): PerformanceTrace =
        PerformanceTrace(
            operation = PerformanceOperation.SYNC_CATCH_UP,
            sessionGeneration = 1L,
            operationId = generation,
            startedAtMs = startedAtMillis,
        )
}
