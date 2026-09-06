package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatListConnectionStateTest {
    /** Matching account, runtime, and network identities must share one native catch-up result. */
    @Test
    fun catchUpSharesOnlyAnExactReadinessIdentity() =
        runTest {
            val coordinator = AccountCatchUpCoordinator(this)
            val release = CompletableDeferred<Unit>()
            val key =
                AccountCatchUpKey(
                    accountRef = "personal",
                    runtimeGeneration = 4,
                    networkGeneration = 8L,
                )
            val first =
                coordinator.launch(key) {
                    release.await()
                    true
                }
            runCurrent()
            val shared = coordinator.launch(key) { false }
            val newerNetwork =
                coordinator.launch(key.copy(networkGeneration = 9L)) {
                    release.await()
                    true
                }

            assertSame(first, shared)
            assertNotSame(first, newerNetwork)
            release.complete(Unit)
            assertEquals(AccountCatchUpOutcome.Succeeded, first.await().outcome)
            assertEquals(AccountCatchUpOutcome.Succeeded, newerNetwork.await().outcome)
        }

    /** Native catch-up calls for different network generations must never overlap. */
    @Test
    fun catchUpSerializesDifferentNetworkGenerations() =
        runTest {
            val coordinator = AccountCatchUpCoordinator(this)
            val firstStarted = CompletableDeferred<Unit>()
            val releaseFirst = CompletableDeferred<Unit>()
            val secondStarted = CompletableDeferred<Unit>()
            var inFlight = 0
            var maximumInFlight = 0
            val firstKey =
                AccountCatchUpKey(
                    accountRef = "personal",
                    runtimeGeneration = 4,
                    networkGeneration = 8L,
                )

            val first =
                coordinator.launch(firstKey) {
                    inFlight += 1
                    maximumInFlight = maxOf(maximumInFlight, inFlight)
                    firstStarted.complete(Unit)
                    releaseFirst.await()
                    inFlight -= 1
                    true
                }
            firstStarted.await()
            val second =
                coordinator.launch(firstKey.copy(networkGeneration = 9L)) {
                    inFlight += 1
                    maximumInFlight = maxOf(maximumInFlight, inFlight)
                    secondStarted.complete(Unit)
                    inFlight -= 1
                    true
                }
            runCurrent()

            assertFalse("a successor must wait for the active native call", secondStarted.isCompleted)
            releaseFirst.complete(Unit)
            assertEquals(AccountCatchUpOutcome.Succeeded, first.await().outcome)
            assertEquals(AccountCatchUpOutcome.Succeeded, second.await().outcome)
            assertEquals(1, maximumInFlight)
        }

    /** Only the newest queued network generation should run after an active catch-up. */
    @Test
    fun catchUpCoalescesQueuedNetworkGenerations() =
        runTest {
            val coordinator = AccountCatchUpCoordinator(this)
            val releaseFirst = CompletableDeferred<Unit>()
            val firstKey =
                AccountCatchUpKey(
                    accountRef = "personal",
                    runtimeGeneration = 4,
                    networkGeneration = 8L,
                )
            val attempts = mutableListOf<Long>()
            val first =
                coordinator.launch(firstKey) {
                    attempts += 8L
                    releaseFirst.await()
                    true
                }
            runCurrent()
            val superseded =
                coordinator.launch(firstKey.copy(networkGeneration = 9L)) {
                    attempts += 9L
                    true
                }
            val newest =
                coordinator.launch(firstKey.copy(networkGeneration = 10L)) {
                    attempts += 10L
                    true
                }

            releaseFirst.complete(Unit)
            assertEquals(AccountCatchUpOutcome.Succeeded, first.await().outcome)
            assertEquals(AccountCatchUpOutcome.Superseded, superseded.await().outcome)
            assertEquals(AccountCatchUpOutcome.Succeeded, newest.await().outcome)
            assertEquals(listOf(8L, 10L), attempts)
        }

    /** Work started before a new trigger cannot satisfy that trigger's catch-up. */
    @Test
    fun catchUpAfterATriggerQueuesBehindOlderMatchingWork() =
        runTest {
            val coordinator = AccountCatchUpCoordinator(this)
            val key =
                AccountCatchUpKey(
                    accountRef = "personal",
                    runtimeGeneration = 4,
                    networkGeneration = 8L,
                )
            val oldStarted = CompletableDeferred<Unit>()
            val releaseOld = CompletableDeferred<Unit>()
            val freshStarted = CompletableDeferred<Unit>()
            val old =
                coordinator.launch(key) {
                    oldStarted.complete(Unit)
                    releaseOld.await()
                    true
                }
            oldStarted.await()

            val observedStartSequence = coordinator.captureStartSequence()
            val fresh =
                coordinator.launchAfter(observedStartSequence, key) {
                    freshStarted.complete(Unit)
                    true
                }

            assertNotSame(old, fresh)
            assertFalse(freshStarted.isCompleted)
            releaseOld.complete(Unit)
            assertEquals(observedStartSequence, old.await().startSequence)
            assertEquals(AccountCatchUpOutcome.Succeeded, fresh.await().outcome)
            assertTrue(requireNotNull(fresh.await().startSequence) > observedStartSequence)
        }

    /** Successful work from before a push cannot clear the newly recorded wake. */
    @Test
    fun pushWakeClearsOnlyAfterTheFreshSuccessorCompletes() =
        runTest {
            val coordinator = AccountCatchUpCoordinator(this)
            val key =
                AccountCatchUpKey(
                    accountRef = "personal",
                    runtimeGeneration = 4,
                    networkGeneration = 8L,
                )
            val oldStarted = CompletableDeferred<Unit>()
            val releaseOld = CompletableDeferred<Unit>()
            val freshStarted = CompletableDeferred<Unit>()
            val releaseFresh = CompletableDeferred<Unit>()
            var markerCleared = false

            coordinator.launch(key) {
                oldStarted.complete(Unit)
                releaseOld.await()
                true
            }
            oldStarted.await()
            val observedStartSequence = coordinator.captureStartSequence()
            val drain =
                async {
                    runCatchUpAfterTrigger(
                        observedStartSequence = observedStartSequence,
                        launchAfter = { sequence ->
                            coordinator.launchAfter(sequence, key) {
                                freshStarted.complete(Unit)
                                releaseFresh.await()
                                true
                            }
                        },
                        onSucceeded = { markerCleared = true },
                    )
                }
            runCurrent()

            releaseOld.complete(Unit)
            freshStarted.await()
            assertFalse("the older success must leave the push marker pending", markerCleared)
            releaseFresh.complete(Unit)

            assertEquals(AccountCatchUpOutcome.Succeeded, drain.await().outcome)
            assertTrue(markerCleared)
        }

    /** A triggered drain preserves its marker and fails into backoff after bounded coalescing. */
    @Test
    fun triggeredCatchUpBoundsSupersededReplacements() =
        runTest {
            var launches = 0
            var markerCleared = false

            val result =
                runCatchUpAfterTrigger(
                    observedStartSequence = 8L,
                    launchAfter = {
                        launches += 1
                        CompletableDeferred(AccountCatchUpResult(AccountCatchUpOutcome.Superseded))
                    },
                    onSucceeded = { markerCleared = true },
                    maxSupersededReplacements = 2,
                )

            assertEquals(AccountCatchUpOutcome.Failed, result.outcome)
            assertEquals(3, launches)
            assertFalse(markerCleared)
        }

    @Test
    fun subscriptionLifecycleCoversDropRetryAndRecovery() {
        val firstValidation =
            ChatListConnectionState().beginSubscriptionValidation(
                accountRef = "personal",
                runtimeGeneration = 4,
                bindEpoch = 7,
            )
        val firstReady =
            firstValidation.readyFromCatchUp(requireNotNull(firstValidation.evidenceTokenOrNull()))
        val dropped =
            firstReady.finishSessionAttempt(
                accountRef = "personal",
                runtimeGeneration = 4,
                bindEpoch = 7,
                sessionAttemptId = firstValidation.sessionAttemptId,
            )
        val retry =
            dropped.beginSessionAttempt(
                accountRef = "personal",
                runtimeGeneration = 4,
                bindEpoch = 7,
            )
        val recovered =
            retry.readyFromLiveUpdate(
                accountRef = "personal",
                runtimeGeneration = 4,
                bindEpoch = 7,
                sessionAttemptId = retry.sessionAttemptId,
                hasValidatedInternet = true,
            )

        assertEquals(ChatListConnectionPhase.Validating, firstValidation.phase)
        assertEquals(ChatListConnectionPhase.Ready, firstReady.phase)
        assertEquals(ChatListConnectionPhase.Idle, dropped.phase)
        assertEquals(ChatListConnectionPhase.Attempting, retry.phase)
        assertEquals(ChatListConnectionPhase.Ready, recovered.phase)
    }

    @Test
    fun healthyRevalidationDoesNotPublishAnAttemptingPhase() {
        val ready =
            ChatListConnectionState()
                .beginSubscriptionValidation(
                    accountRef = "personal",
                    runtimeGeneration = 4,
                    bindEpoch = 7,
                ).let { it.readyFromCatchUp(requireNotNull(it.evidenceTokenOrNull())) }

        val validating = ready.beginReadinessRefresh(presentAttempt = false)
        val refreshed = validating.readyFromCatchUp(requireNotNull(validating.evidenceTokenOrNull()))

        assertEquals(ChatListConnectionPhase.Validating, validating.phase)
        assertEquals(ChatListConnectionPhase.Ready, refreshed.phase)
    }

    @Test
    fun explicitLossRecoveryStillPublishesAttemptingThenReady() {
        val ready =
            ChatListConnectionState()
                .beginSubscriptionValidation(
                    accountRef = "personal",
                    runtimeGeneration = 4,
                    bindEpoch = 7,
                ).let { it.readyFromCatchUp(requireNotNull(it.evidenceTokenOrNull())) }

        val attempting = ready.beginReadinessRefresh(presentAttempt = true)
        val recovered = attempting.readyFromCatchUp(requireNotNull(attempting.evidenceTokenOrNull()))

        assertEquals(ChatListConnectionPhase.Attempting, attempting.phase)
        assertEquals(ChatListConnectionPhase.Ready, recovered.phase)
    }

    @Test
    fun staleCatchUpCannotReadyANewerRefreshOrSession() {
        val firstAttempt =
            ChatListConnectionState().beginSessionAttempt(
                accountRef = "personal",
                runtimeGeneration = 4,
                bindEpoch = 7,
            )
        val staleCatchUp = requireNotNull(firstAttempt.evidenceTokenOrNull())
        val refreshed = firstAttempt.beginReadinessRefresh(presentAttempt = false)

        assertEquals(refreshed, refreshed.readyFromCatchUp(staleCatchUp))

        val nextSession =
            refreshed.beginSessionAttempt(
                accountRef = "personal",
                runtimeGeneration = 4,
                bindEpoch = 7,
            )
        assertEquals(nextSession, nextSession.readyFromCatchUp(staleCatchUp))
    }

    @Test
    fun currentCatchUpReadiesOnlyItsExactAttempt() {
        val attempting =
            ChatListConnectionState().beginSessionAttempt(
                accountRef = "personal",
                runtimeGeneration = 4,
                bindEpoch = 7,
            )

        assertEquals(
            ChatListConnectionPhase.Ready,
            attempting.readyFromCatchUp(requireNotNull(attempting.evidenceTokenOrNull())).phase,
        )
    }

    @Test
    fun offlineInvalidationRejectsCompletionThatWasAlreadyInFlight() {
        val attempting =
            ChatListConnectionState().beginSessionAttempt(
                accountRef = "personal",
                runtimeGeneration = 4,
                bindEpoch = 7,
            )
        val staleCatchUp = requireNotNull(attempting.evidenceTokenOrNull())
        val offline = attempting.invalidateReadiness()

        assertEquals(ChatListConnectionPhase.Idle, offline.phase)
        assertEquals(offline, offline.readyFromCatchUp(staleCatchUp))
    }

    @Test
    fun failedCatchUpReturnsToNoAttemptWithoutAFalseReadyEdge() {
        val attempting =
            ChatListConnectionState().beginSessionAttempt(
                accountRef = "personal",
                runtimeGeneration = 4,
                bindEpoch = 7,
            )

        assertEquals(
            ChatListConnectionPhase.Idle,
            attempting
                .applyCatchUpResult(
                    token = requireNotNull(attempting.evidenceTokenOrNull()),
                    result = AccountCatchUpResult(AccountCatchUpOutcome.Failed),
                ).phase,
        )
    }

    /** Coalescing is neutral: a request that never ran cannot invalidate readiness. */
    @Test
    fun supersededCatchUpPreservesTheCurrentReadinessPhase() {
        val attempting =
            ChatListConnectionState().beginSessionAttempt(
                accountRef = "personal",
                runtimeGeneration = 4,
                bindEpoch = 7,
            )

        assertEquals(
            attempting,
            attempting.applyCatchUpResult(
                token = requireNotNull(attempting.evidenceTokenOrNull()),
                result = AccountCatchUpResult(AccountCatchUpOutcome.Superseded),
            ),
        )
    }

    /** A readiness observer follows the replacement work instead of remaining mid-attempt. */
    @Test
    fun ownerFollowsSupersededCatchUpToReplacementSuccess() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val replacement = CompletableDeferred<AccountCatchUpResult>()
            var replacementJoinCount = 0
            val owner =
                ChatListConnectionOwner(
                    runtimeGeneration = { 4 },
                    hasValidatedInternet = { true },
                    launchCatchUpRequest = {
                        replacementJoinCount += 1
                        replacement
                    },
                    hasCurrentSubscriptions = { true },
                )
            try {
                owner.beginSessionAttempt(accountRef = "personal", bindEpoch = 7)
                owner.observe(
                    CompletableDeferred(AccountCatchUpResult(AccountCatchUpOutcome.Superseded)),
                )
                runCurrent()

                assertEquals(1, replacementJoinCount)
                assertEquals(ChatListConnectionPhase.Attempting, owner.state.phase)

                replacement.complete(AccountCatchUpResult(AccountCatchUpOutcome.Succeeded))
                runCurrent()

                assertEquals(ChatListConnectionPhase.Ready, owner.state.phase)
            } finally {
                owner.clear()
                Dispatchers.resetMain()
            }
        }

    /** A readiness observer yields back to refresh instead of spinning under continuous churn. */
    @Test
    fun ownerBoundsSupersededCatchUpReplacements() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            var replacementJoinCount = 0
            val owner =
                ChatListConnectionOwner(
                    runtimeGeneration = { 4 },
                    hasValidatedInternet = { true },
                    launchCatchUpRequest = {
                        replacementJoinCount += 1
                        CompletableDeferred(AccountCatchUpResult(AccountCatchUpOutcome.Superseded))
                    },
                    hasCurrentSubscriptions = { true },
                )
            try {
                owner.beginSessionAttempt(accountRef = "personal", bindEpoch = 7)
                owner.observe(
                    CompletableDeferred(AccountCatchUpResult(AccountCatchUpOutcome.Superseded)),
                )
                runCurrent()

                assertEquals(CATCH_UP_MAX_SUPERSEDED_REPLACEMENTS, replacementJoinCount)
                assertEquals(ChatListConnectionPhase.Idle, owner.state.phase)
            } finally {
                owner.clear()
                Dispatchers.resetMain()
            }
        }

    @Test
    fun liveUpdateRequiresCurrentSessionAndValidatedInternet() {
        val attempting =
            ChatListConnectionState().beginSessionAttempt(
                accountRef = "personal",
                runtimeGeneration = 4,
                bindEpoch = 7,
            )

        assertEquals(
            ChatListConnectionPhase.Idle,
            attempting
                .invalidateReadiness()
                .readyFromLiveUpdate(
                    accountRef = "personal",
                    runtimeGeneration = 4,
                    bindEpoch = 7,
                    sessionAttemptId = attempting.sessionAttemptId,
                    hasValidatedInternet = false,
                ).phase,
        )
        assertEquals(
            ChatListConnectionPhase.Ready,
            attempting
                .readyFromLiveUpdate(
                    accountRef = "personal",
                    runtimeGeneration = 4,
                    bindEpoch = 7,
                    sessionAttemptId = attempting.sessionAttemptId,
                    hasValidatedInternet = true,
                ).phase,
        )
        assertEquals(
            attempting,
            attempting.readyFromLiveUpdate(
                accountRef = "work",
                runtimeGeneration = 4,
                bindEpoch = 7,
                sessionAttemptId = attempting.sessionAttemptId,
                hasValidatedInternet = true,
            ),
        )
    }

    @Test
    fun staleSessionFinishCannotClearNewerReadyState() {
        val first =
            ChatListConnectionState().beginSessionAttempt(
                accountRef = "personal",
                runtimeGeneration = 4,
                bindEpoch = 7,
            )
        val second =
            first
                .beginSessionAttempt(
                    accountRef = "personal",
                    runtimeGeneration = 4,
                    bindEpoch = 7,
                ).readyFromLiveUpdate(
                    accountRef = "personal",
                    runtimeGeneration = 4,
                    bindEpoch = 7,
                    sessionAttemptId = first.sessionAttemptId + 1L,
                    hasValidatedInternet = true,
                )

        assertEquals(
            second,
            second.finishSessionAttempt(
                accountRef = "personal",
                runtimeGeneration = 4,
                bindEpoch = 7,
                sessionAttemptId = first.sessionAttemptId,
            ),
        )
    }

    @Test
    fun queuedLiveUpdateCannotReadyAFinishedSession() {
        val attempting =
            ChatListConnectionState().beginSessionAttempt(
                accountRef = "personal",
                runtimeGeneration = 4,
                bindEpoch = 7,
            )
        val finished =
            attempting.finishSessionAttempt(
                accountRef = "personal",
                runtimeGeneration = 4,
                bindEpoch = 7,
                sessionAttemptId = attempting.sessionAttemptId,
            )

        assertEquals(
            finished,
            finished.readyFromLiveUpdate(
                accountRef = "personal",
                runtimeGeneration = 4,
                bindEpoch = 7,
                sessionAttemptId = attempting.sessionAttemptId,
                hasValidatedInternet = true,
            ),
        )
    }
}
