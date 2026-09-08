package dev.ipf.whitenoise.android.ui.onboarding.setup

import dev.ipf.marmotkit.OnboardingActionFfi
import dev.ipf.marmotkit.OnboardingSnapshotFfi
import dev.ipf.marmotkit.OnboardingStepFfi
import dev.ipf.marmotkit.UserProfileMetadataFfi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises decisions and lifecycle through the same client boundary used by the native adapter. */
class AccountSetupControllerTest {
    /** Ensures failure before the first accepted snapshot still releases the acquired subscription. */
    @Test
    fun initialSnapshotFailureStillClosesTheNativeSubscription() =
        runTest {
            val client = FakeSetupClient().apply { failInitialSnapshot = true }
            val controller = AccountSetupController(SETUP_TEST_ACCOUNT, client, backgroundScope, { true }, {}, {})
            controller.reconnect()
            runCurrent()
            assertTrue(client.closed)
            assertTrue(controller.state.value.error)
            controller.close()
        }

    /** Preserves a missing display name when only the About field was edited. */
    @Test
    fun editingAboutDoesNotPromoteAFallbackNameToDisplayName() =
        runTest {
            val client =
                FakeSetupClient().apply {
                    metadata = UserProfileMetadataFfi("fallback", null, "Before", null, null, null, null)
                }
            val controller = AccountSetupController(SETUP_TEST_ACCOUNT, client, backgroundScope, { true }, {}, {})
            controller.reconnect()
            runCurrent()
            controller.edit(OnboardingStepFfi.PROFILE, OnboardingActionFfi.EDIT_PROFILE, 3uL)
            runCurrent()
            controller.updateEditor(
                controller.state.value.editor!!
                    .copy(about = ""),
            )
            controller.submit(
                controller.state.value.editor!!
                    .request(),
            )
            runCurrent()
            assertEquals(client.metadata!!.copy(about = ""), client.requests.single().profile)
            controller.close()
        }

    /** Checks subscription ordering and prevents activation while a profile decision is pending. */
    @Test
    fun subscribesBeforePreflightAndDoesNotActivateForAProfileDecision() =
        runTest {
            val client = FakeSetupClient()
            var activated = false
            val controller =
                AccountSetupController(
                    SETUP_TEST_ACCOUNT,
                    client,
                    backgroundScope,
                    { true },
                    { activated = true },
                    {},
                )
            controller.reconnect()
            runCurrent()
            assertEquals(listOf("subscribe", "run"), client.order)
            assertEquals(
                OnboardingStepFfi.PROFILE,
                controller.state.value.currentStep
                    ?.step,
            )
            assertFalse(activated)
            controller.close()
            assertTrue(client.closed)
        }

    /** Exercises single-flight submission while a decision is still awaiting its native result. */
    @Test
    fun repeatedTapsPublishOnlyOneDecisionWithTheDisplayedRevision() =
        runTest {
            val client = FakeSetupClient()
            val controller = AccountSetupController(SETUP_TEST_ACCOUNT, client, backgroundScope, { true }, {}, {})
            controller.reconnect()
            runCurrent()
            client.hold = CompletableDeferred()
            val request = SetupRequest(3uL, OnboardingStepFfi.PROFILE, OnboardingActionFfi.CONTINUE_WITHOUT)
            controller.submit(request)
            controller.submit(request)
            runCurrent()
            assertEquals(listOf(request), client.requests)
            assertTrue(controller.state.value.busy)
            client.hold?.complete(Unit)
            runCurrent()
            assertFalse(controller.state.value.busy)
            controller.close()
        }

    /** Rejects an approval when the authoritative checkpoint has advanced beyond the displayed proposal. */
    @Test
    fun newerStoredRevisionRequiresFreshReviewWithoutPublishing() =
        runTest {
            val client = FakeSetupClient()
            val controller = AccountSetupController(SETUP_TEST_ACCOUNT, client, backgroundScope, { true }, {}, {})
            controller.reconnect()
            runCurrent()
            client.current = setupSnapshot(revision = 4uL)
            controller.submit(SetupRequest(3uL, OnboardingStepFfi.PROFILE, OnboardingActionFfi.CONTINUE_WITHOUT))
            runCurrent()
            assertTrue(client.requests.isEmpty())
            assertTrue(controller.state.value.staleDecision)
            assertEquals(
                4uL,
                controller.state.value.snapshot
                    ?.revision,
            )
            controller.close()
        }

    /** Prevents UI requests from invoking native actions that the current checkpoint does not offer. */
    @Test
    fun actionsAbsentFromTheSnapshotNeverReachTheEngine() =
        runTest {
            val client = FakeSetupClient(setupSnapshot(OnboardingStepFfi.RELAYS, listOf(OnboardingActionFfi.RETRY)))
            val controller = AccountSetupController(SETUP_TEST_ACCOUNT, client, backgroundScope, { true }, {}, {})
            controller.reconnect()
            runCurrent()
            controller.submit(SetupRequest(3uL, OnboardingStepFfi.RELAYS, OnboardingActionFfi.USE_RECOMMENDED_RELAYS))
            runCurrent()
            assertTrue(client.requests.isEmpty())
            controller.close()
        }

    /** Revalidates readiness before activation even when the update stream ended normally. */
    @Test
    fun readyThenTerminatedStreamStillAllowsOpenChatsAfterFreshRead() =
        runTest {
            val client = FakeSetupClient(setupSnapshot(ready = true))
            var activations = 0
            val controller =
                AccountSetupController(
                    SETUP_TEST_ACCOUNT,
                    client,
                    backgroundScope,
                    { true },
                    { activations++ },
                    {},
                )
            controller.reconnect()
            runCurrent()
            client.updates.close()
            runCurrent()
            assertTrue(controller.state.value.disconnected)
            controller.openChats()
            runCurrent()
            assertEquals(1, activations)
            assertTrue(client.snapshotReads > 0)
            controller.close()
        }

    /** Stops activation when the fresh authoritative snapshot no longer certifies readiness. */
    @Test
    fun readThatRevokesReadinessPreventsActivation() =
        runTest {
            val client = FakeSetupClient(setupSnapshot(ready = true))
            var activated = false
            val controller =
                AccountSetupController(
                    SETUP_TEST_ACCOUNT,
                    client,
                    backgroundScope,
                    { true },
                    { activated = true },
                    {},
                )
            client.snapshotOverride = setupSnapshot(revision = 4uL)
            controller.reconnect()
            runCurrent()
            controller.openChats()
            runCurrent()
            assertFalse(activated)
            assertFalse(
                controller.state.value.snapshot!!
                    .ready,
            )
            controller.close()
        }

    /** Rejects updates belonging to another account or an older checkpoint revision. */
    @Test
    fun foreignAccountAndOldRevisionUpdatesAreDiscarded() =
        runTest {
            val client = FakeSetupClient()
            val controller = AccountSetupController(SETUP_TEST_ACCOUNT, client, backgroundScope, { true }, {}, {})
            controller.reconnect()
            runCurrent()
            client.updates.send(setupSnapshot(revision = 9uL, ready = true).copy(accountIdHex = "cd".repeat(32)))
            client.updates.send(setupSnapshot(revision = 2uL, ready = true))
            runCurrent()
            assertEquals(
                3uL,
                controller.state.value.snapshot
                    ?.revision,
            )
            assertFalse(
                controller.state.value.snapshot!!
                    .ready,
            )
            controller.close()
        }

    /** Fences commands and activation callbacks after the runtime owner is replaced. */
    @Test
    fun invalidatedRuntimeCannotActivateOrSubmit() =
        runTest {
            val client = FakeSetupClient(setupSnapshot(ready = true))
            var current = true
            var activated = false
            val controller =
                AccountSetupController(
                    SETUP_TEST_ACCOUNT,
                    client,
                    backgroundScope,
                    { current },
                    { activated = true },
                    {},
                )
            client.beforeSnapshot = { current = false }
            controller.reconnect()
            runCurrent()
            controller.openChats()
            controller.submit(SetupRequest(3uL, OnboardingStepFfi.PROFILE, OnboardingActionFfi.CONTINUE_WITHOUT))
            runCurrent()
            assertFalse(activated)
            assertTrue(client.requests.isEmpty())
            controller.close()
        }

    /** Retains metadata outside the edited fields and preserves the draft when proposal creation fails. */
    @Test
    fun profileProposalPreservesUntouchedMetadataAndFailedDraft() =
        runTest {
            val client = FakeSetupClient()
            client.metadata =
                UserProfileMetadataFfi(
                    "original",
                    "Old",
                    "Old about",
                    "https://example.com/p.png",
                    "id@example.com",
                    "tip@example.com",
                    "https://example.com/b.png",
                )
            val controller = AccountSetupController(SETUP_TEST_ACCOUNT, client, backgroundScope, { true }, {}, {})
            controller.reconnect()
            runCurrent()
            controller.edit(OnboardingStepFfi.PROFILE, OnboardingActionFfi.EDIT_PROFILE, 3uL)
            runCurrent()
            controller.updateEditor(
                controller.state.value.editor!!
                    .copy(displayName = "New", about = "New about"),
            )
            client.fail = true
            controller.submit(
                controller.state.value.editor!!
                    .request(),
            )
            runCurrent()
            val submitted = client.requests.single().profile!!
            assertEquals(client.metadata!!.copy(displayName = "New", about = "New about"), submitted)
            assertEquals(
                "New",
                controller.state.value.editor
                    ?.displayName,
            )
            assertTrue(controller.state.value.error)
            controller.close()
        }

    /** Retries the saved native operation without creating or approving another repair. */
    @Test
    fun repairRetryUsesTheSavedStepWithoutReproposingOrApproving() =
        runTest {
            val client =
                FakeSetupClient(
                    setupSnapshot(
                        OnboardingStepFfi.INBOX_RELAYS,
                        listOf(OnboardingActionFfi.RETRY),
                    ),
                )
            val controller = AccountSetupController(SETUP_TEST_ACCOUNT, client, backgroundScope, { true }, {}, {})
            controller.reconnect()
            runCurrent()
            controller.submit(SetupRequest(3uL, OnboardingStepFfi.INBOX_RELAYS, OnboardingActionFfi.RETRY))
            runCurrent()
            assertEquals(listOf(OnboardingActionFfi.RETRY), client.requests.map { it.action })
            controller.close()
        }

    /** Keeps failed cancellation recoverable and emits a single exit after successful retry. */
    @Test
    fun cancellationFailureKeepsTheRouteAndSuccessfulRetryExitsOnce() =
        runTest {
            val client =
                FakeSetupClient(
                    setupSnapshot(
                        OnboardingStepFfi.SINGLE_DEVICE,
                        listOf(OnboardingActionFfi.CANCEL_ONBOARDING),
                    ).apply { cancellationPending = true },
                )
            var exits = 0
            val controller =
                AccountSetupController(
                    SETUP_TEST_ACCOUNT,
                    client,
                    backgroundScope,
                    { true },
                    {},
                    { exits++ },
                )
            controller.reconnect()
            runCurrent()
            assertEquals(
                OnboardingStepFfi.SINGLE_DEVICE,
                controller.state.value.currentStep
                    ?.step,
            )
            val request = SetupRequest(3uL, OnboardingStepFfi.SINGLE_DEVICE, OnboardingActionFfi.CANCEL_ONBOARDING)
            client.fail = true
            controller.submit(request)
            runCurrent()
            assertEquals(0, exits)
            client.fail = false
            client.cancel = true
            controller.submit(request)
            runCurrent()
            assertEquals(1, exits)
            controller.close()
        }
}

internal class FakeSetupClient(
    var current: OnboardingSnapshotFfi = setupSnapshot(),
) : AccountSetupClient {
    val order = mutableListOf<String>()
    val requests = mutableListOf<SetupRequest>()
    val updates = Channel<OnboardingSnapshotFfi>(Channel.UNLIMITED)
    var hold: CompletableDeferred<Unit>? = null
    var metadata: UserProfileMetadataFfi? = null
    var fail = false
    var cancel = false
    var closed = false
    var snapshotReads = 0
    var failInitialSnapshot = false
    var beforeSnapshot: () -> Unit = {}
    var snapshotOverride: OnboardingSnapshotFfi? = null
    var executeResult: ((SetupRequest) -> OnboardingSnapshotFfi?)? = null

    /** Counts authoritative rereads so activation tests can assert fresh readiness checks. */
    override suspend fun snapshot(): OnboardingSnapshotFfi {
        snapshotReads++
        beforeSnapshot()
        return snapshotOverride ?: current
    }

    /** Records preflight execution so tests can assert that subscription attachment came first. */
    override suspend fun run(): OnboardingSnapshotFfi {
        order += "run"
        return current
    }

    /** Returns fixture metadata for field-preservation tests. */
    override suspend fun profile(): UserProfileMetadataFfi? = metadata

    /** Records decisions and can suspend, fail, or cancel them at controlled test boundaries. */
    override suspend fun execute(request: SetupRequest): OnboardingSnapshotFfi? {
        requests += request
        hold?.await()
        check(!fail)
        return if (cancel) null else executeResult?.invoke(request) ?: current
    }

    /** Returns a controllable update stream and records subscription acquisition order. */
    override suspend fun subscribe(): AccountSetupSubscription {
        order += "subscribe"
        return object : AccountSetupSubscription {
            /** Can fail the initial subscription read to exercise resource cleanup before reader startup. */
            override fun snapshot(): OnboardingSnapshotFfi {
                check(!failInitialSnapshot)
                return current
            }

            /** Waits for the next scripted update, returning null after the test stream closes. */
            override suspend fun next() = updates.receiveCatching().getOrNull()

            /** Records native subscription release for cancellation and initial-read failure assertions. */
            override suspend fun close() {
                closed = true
            }
        }
    }
}
