package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatListConnectionStateTest {
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
                    readinessToken = null,
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
            assertTrue(first.await())
            assertTrue(newerNetwork.await())
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
            attempting.catchUpFailed(requireNotNull(attempting.evidenceTokenOrNull())).phase,
        )
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
