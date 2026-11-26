package dev.ipf.whitenoise.android.ui.onboarding.setup

import dev.ipf.marmotkit.OnboardingActionFfi
import dev.ipf.marmotkit.OnboardingCheckPurposeFfi
import dev.ipf.marmotkit.OnboardingIssueFfi
import dev.ipf.marmotkit.OnboardingStatusFfi
import dev.ipf.marmotkit.OnboardingStepFfi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Network and foreground recovery use only the current failed check's native grant. */
class AccountSetupAutomaticRetryCoverageTest {
    /** Overlapping recovery events make one attempt while a retry is already in flight. */
    @Test
    fun overlappingRecoveryEventsRetryFailedCheckOnce() =
        runTest {
            val client = FakeSetupClient(inboxDiscoveryIncompleteSnapshot())
            val restored = MutableSharedFlow<Long>(extraBufferCapacity = 8)
            val controller = connectivityController(client, restored)
            controller.reconnect()
            runCurrent()
            val release = CompletableDeferred<Unit>()
            client.hold = release
            restored.tryEmit(1L)
            runCurrent()
            restored.tryEmit(2L)
            controller.foregroundRetry.onBackgrounded()
            controller.foregroundRetry.onForegrounded()
            runCurrent()
            release.complete(Unit)
            runCurrent()
            assertEquals(
                listOf(SetupRequest(3uL, OnboardingStepFfi.INBOX_RELAYS, OnboardingActionFfi.RETRY)),
                client.requests,
            )
            assertFalse(controller.state.value.staleDecision)
            controller.close()
        }

    /** A change waiting to publish, or a proposal under review, waits for the user. */
    @Test
    fun regainedConnectionLeavesPendingDecisionsToTheUser() =
        runTest {
            listOf(refusedPublicationSnapshot(), relayPreviewSnapshot()).forEach { snapshot ->
                val client = FakeSetupClient(snapshot)
                val restored = MutableSharedFlow<Long>(extraBufferCapacity = 8)
                val controller = connectivityController(client, restored)
                controller.reconnect()
                runCurrent()
                restored.tryEmit(1L)
                runCurrent()
                assertTrue(client.requests.isEmpty())
                controller.close()
            }
        }

    /** A refused Save for a changed or invalid declaration remains visible until the user retries. */
    @Test
    fun wakesLeaveInvalidatedApprovalsForManualRetry() =
        runTest {
            val purposes =
                listOf(OnboardingCheckPurposeFfi.SOURCE_FRESHNESS, OnboardingCheckPurposeFfi.DECLARATION_VALIDATION)
            for (purpose in purposes) {
                val snapshot = refusedSaveSnapshot(OnboardingIssueFfi.RECORD_CHANGED, purpose)
                val client = FakeSetupClient(snapshot)
                val restored = MutableSharedFlow<Long>(extraBufferCapacity = 1)
                val controller = connectivityController(client, restored)
                controller.reconnect()
                runCurrent()

                restored.tryEmit(1L)
                controller.foregroundRetry.onBackgrounded()
                controller.foregroundRetry.onForegrounded()
                runCurrent()
                assertTrue(client.requests.isEmpty())
                val state = controller.state.value
                assertEquals(snapshot, state.snapshot)
                assertEquals(listOf(OnboardingActionFfi.RETRY to true), state.decisionButtons())

                val retry = SetupRequest(snapshot.revision, OnboardingStepFfi.INBOX_RELAYS, OnboardingActionFfi.RETRY)
                controller.submit(retry)
                runCurrent()
                assertEquals(listOf(retry), client.requests)
                controller.close()
            }
        }

    /** A proposal installed before the authoritative reread is shown without retrying it. */
    @Test
    fun newlyPendingRepairPreventsAutomaticRetry() =
        runTest {
            val client = FakeSetupClient(inboxDiscoveryIncompleteSnapshot())
            val controller = connectivityController(client, MutableSharedFlow())
            controller.reconnect()
            runCurrent()
            client.current = refusedPublicationSnapshot().copy(revision = 4uL)

            controller.retryFailedCheck()
            runCurrent()

            assertTrue(client.requests.isEmpty())
            assertEquals(
                client.current.proposal,
                controller.state.value.snapshot
                    ?.proposal,
            )
            controller.close()
        }

    /** A native retry that throws before advancing leaves manual Retry and later wakes available. */
    @Test
    fun transientAutomaticRetryFailureKeepsTheDecision() =
        runTest {
            val client = FakeSetupClient(inboxDiscoveryIncompleteSnapshot())
            val controller = connectivityController(client, MutableSharedFlow())
            controller.reconnect()
            runCurrent()
            client.fail = true

            controller.retryFailedCheck()
            runCurrent()

            assertFalse(controller.state.value.error)
            assertEquals(
                OnboardingActionFfi.RETRY,
                controller.state.value
                    .decisionButtons()
                    .first()
                    .first,
            )
            client.fail = false
            controller.retryFailedCheck()
            runCurrent()
            assertEquals(2, client.requests.size)
            controller.close()
        }

    /** A held Save owns the controller until it finishes. Neither wake races the signed operation. */
    @Test
    fun recoveryLeavesAnAlreadyRunningApprovalAlone() =
        runTest {
            val client = FakeSetupClient(relayPreviewSnapshot())
            val restored = MutableSharedFlow<Long>(extraBufferCapacity = 1)
            val controller = connectivityController(client, restored)
            controller.reconnect()
            runCurrent()
            val release = CompletableDeferred<Unit>()
            client.hold = release
            val approve = SetupRequest(4uL, OnboardingStepFfi.RELAYS, OnboardingActionFfi.APPROVE_REPAIR)

            controller.submit(approve)
            runCurrent()
            restored.tryEmit(1L)
            controller.foregroundRetry.onBackgrounded()
            controller.foregroundRetry.onForegrounded()
            runCurrent()
            assertEquals(listOf(approve), client.requests)
            release.complete(Unit)
            runCurrent()
            controller.close()
        }

    /** If Retry advanced before throwing, Resume can restart the interrupted native check. */
    @Test
    fun interruptedRetryAfterNativeResetOffersResume() =
        runTest {
            val client = FakeSetupClient(inboxDiscoveryIncompleteSnapshot())
            val controller = connectivityController(client, MutableSharedFlow())
            controller.reconnect()
            runCurrent()
            client.executeResult = {
                client.current =
                    failedStep(
                        OnboardingStepFfi.INBOX_RELAYS,
                        emptyList(),
                        emptyList(),
                        status = OnboardingStatusFfi.PENDING,
                    ).copy(revision = 4uL)
                error("The retry failed after saving its reset")
            }

            controller.retryFailedCheck()
            runCurrent()

            assertTrue(controller.state.value.showsSetupFailure)
            assertEquals(
                4uL,
                controller.state.value.snapshot
                    ?.revision,
            )
            controller.close()
        }

    /** Connectivity recovery cannot start a native check while Android withholds the setup route. */
    @Test
    fun networkRecoveryWaitsForAnEligibleWake() =
        runTest {
            val client = FakeSetupClient(inboxDiscoveryIncompleteSnapshot())
            val restored = MutableSharedFlow<Long>(extraBufferCapacity = 1)
            var allowed = false
            val controller = connectivityController(client, restored, automaticRetryAllowed = { allowed })
            controller.reconnect()
            runCurrent()

            restored.tryEmit(1L)
            runCurrent()
            assertTrue(client.requests.isEmpty())

            allowed = true
            client.beforeSnapshot = { allowed = false }
            restored.tryEmit(2L)
            runCurrent()
            assertTrue(client.requests.isEmpty())

            allowed = true
            client.beforeSnapshot = {}
            restored.tryEmit(3L)
            runCurrent()
            assertEquals(OnboardingActionFfi.RETRY, client.requests.single().action)
            controller.close()
        }

    /** A retained setup retries one observed background return, independent of the Activity that delivers it. */
    @Test
    fun retainedSetupRetriesOnceOnWarmForeground() =
        runTest {
            val client = FakeSetupClient(inboxDiscoveryIncompleteSnapshot())
            val controller = connectivityController(client, MutableSharedFlow())
            controller.reconnect()
            runCurrent()

            controller.foregroundRetry.onForegrounded()
            runCurrent()
            assertTrue(client.requests.isEmpty())

            controller.foregroundRetry.onBackgrounded()
            controller.foregroundRetry.onForegrounded()
            runCurrent()
            controller.foregroundRetry.onForegrounded()
            runCurrent()

            assertEquals(1, client.requests.size)
            assertEquals(OnboardingActionFfi.RETRY, client.requests.single().action)
            controller.close()
        }

    private fun TestScope.connectivityController(
        client: FakeSetupClient,
        restored: MutableSharedFlow<Long>,
        automaticRetryAllowed: () -> Boolean = { true },
    ) = AccountSetupController(
        SETUP_TEST_ACCOUNT,
        client,
        backgroundScope,
        { true },
        { true },
        {},
        connectivityRestored = restored,
        automaticRetryAllowed = automaticRetryAllowed,
    )
}
