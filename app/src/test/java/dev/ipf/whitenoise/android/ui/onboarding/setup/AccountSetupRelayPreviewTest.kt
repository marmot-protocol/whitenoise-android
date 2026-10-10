package dev.ipf.whitenoise.android.ui.onboarding.setup

import dev.ipf.marmotkit.OnboardingActionFfi
import dev.ipf.marmotkit.OnboardingSnapshotFfi
import dev.ipf.marmotkit.OnboardingStepFfi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ensures editor acquisition releases only its own undisplayed native proposal. */
class AccountSetupRelayPreviewTest {
    /** Malformed native source data must not leave a replacement proposal waiting for later approval. */
    @Test fun missingTypedDeclarationDiscardsItsProposal() =
        runTest {
            val preview = relayPreviewSnapshot().let { it.copy(proposal = it.proposal!!.copy(relayRepair = null)) }
            val client = previewClient(preview)
            val controller = AccountSetupController(SETUP_TEST_ACCOUNT, client, backgroundScope, { true }, {}, {})
            controller.reconnect()
            runCurrent()
            controller.edit(OnboardingStepFfi.RELAYS, OnboardingActionFfi.EDIT_RELAYS, 3uL)
            runCurrent()
            assertTrue(controller.state.value.error)
            assertNull(controller.state.value.editor)
            assertNull(
                controller.state.value.snapshot
                    ?.proposal,
            )
            assertEquals(OnboardingActionFfi.CANCEL_REPAIR, client.requests.last().action)
            controller.close()
        }

    /** An invalidated owner still cleans its preview before close releases the old native runtime. */
    @Test fun latePreviewIsDiscardedAndCloseDrainsCleanup() =
        runTest {
            val client = previewClient(relayPreviewSnapshot())
            val previewWait = CompletableDeferred<Unit>()
            val cleanupWait = CompletableDeferred<Unit>()
            client.hold = previewWait
            client.beforeSnapshot = { if (client.snapshotReads > 1) client.hold = cleanupWait }
            var current = true
            val controller = AccountSetupController(SETUP_TEST_ACCOUNT, client, backgroundScope, { current }, {}, {})
            controller.reconnect()
            runCurrent()
            controller.edit(OnboardingStepFfi.RELAYS, OnboardingActionFfi.EDIT_RELAYS, 3uL)
            runCurrent()
            current = false
            previewWait.complete(Unit)
            runCurrent()
            val close = launch { controller.close() }
            runCurrent()
            assertFalse(close.isCompleted)
            cleanupWait.complete(Unit)
            close.join()
            assertNull(controller.state.value.editor)
            assertNull(client.current.proposal)
            assertEquals(OnboardingActionFfi.CANCEL_REPAIR, client.requests.last().action)
            controller.close()
        }

    /** Failed acquisition must not cancel a replacement checkpoint, proposal, or recovery attempt. */
    @Test fun cleanupDoesNotTouchNewerNativeDecisions() =
        runTest {
            val preview = relayPreviewSnapshot()
            val replacements =
                listOf(
                    preview.copy(revision = 5uL),
                    preview.copy(recoveryEpoch = "new-epoch"),
                    preview.copy(accountIdHex = "another-account"),
                    preview.copy(proposal = preview.proposal!!.copy(revision = 5uL)),
                    preview.copy(cancellationPending = true),
                    preview.copy(steps = preview.steps.map { it.copy(actions = emptyList()) }),
                )
            for (latest in replacements) {
                val client = FakeSetupClient(latest)
                assertNull(client.discardRelayPreview(preview))
                assertTrue(client.requests.isEmpty())
                assertEquals(latest, client.current)
            }
        }

    /** A stream update received during the load wins over the returned preview and cannot be rolled back. */
    @Test fun checkpointAdvancePreventsEditorInstallation() =
        runTest {
            val preview = relayPreviewSnapshot()
            val client = FakeSetupClient(relayDecision()).apply { hold = CompletableDeferred() }
            val newer = preview.copy(revision = 5uL)
            client.executeResult = { preview }
            val controller = AccountSetupController(SETUP_TEST_ACCOUNT, client, backgroundScope, { true }, {}, {})
            controller.reconnect()
            runCurrent()
            controller.edit(OnboardingStepFfi.RELAYS, OnboardingActionFfi.EDIT_RELAYS, 3uL)
            runCurrent()
            client.current = newer
            client.updates.send(newer)
            runCurrent()
            client.hold!!.complete(Unit)
            runCurrent()
            assertNull(controller.state.value.editor)
            assertEquals(newer, controller.state.value.snapshot)
            assertFalse(client.requests.any { it.action == OnboardingActionFfi.CANCEL_REPAIR })
            controller.close()
        }

    /** A cleanup failure adds diagnostic context without replacing the original failed-load exception. */
    @Test fun cleanupFailurePreservesOriginalFailure() =
        runTest {
            val client = FakeSetupClient(relayPreviewSnapshot()).apply { fail = true }
            val original = IllegalStateException("invalid typed preview")
            assertNull(client.discardRelayPreview(client.current, original))
            assertEquals(1, original.suppressed.size)
        }

    /** Models native preview persistence and cancellation separately from the editor's local state. */
    private fun previewClient(preview: OnboardingSnapshotFfi): FakeSetupClient =
        FakeSetupClient(relayDecision()).apply {
            executeResult = { request ->
                current =
                    if (request.action == OnboardingActionFfi.CANCEL_REPAIR) {
                        relayDecision().copy(revision = preview.revision + 1uL)
                    } else {
                        preview
                    }
                current
            }
        }
}
