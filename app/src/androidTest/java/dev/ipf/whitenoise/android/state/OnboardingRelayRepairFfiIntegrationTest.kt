package dev.ipf.whitenoise.android.state

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ipf.marmotkit.Marmot
import dev.ipf.marmotkit.MarmotAndroid
import dev.ipf.marmotkit.MarmotOptions
import dev.ipf.marmotkit.OnboardingActionFfi
import dev.ipf.marmotkit.OnboardingOptionsFfi
import dev.ipf.marmotkit.OnboardingRelayRepairModeFfi
import dev.ipf.marmotkit.OnboardingSnapshotFfi
import dev.ipf.marmotkit.OnboardingStepFfi
import dev.ipf.marmotkit.RelayPolicyFfi
import dev.ipf.whitenoise.android.ui.onboarding.setup.MarmotAccountSetupClient
import dev.ipf.whitenoise.android.ui.onboarding.setup.SetupRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Checks minimal/manual previews, durable consent and publication through the published Kotlin/native pair. */
@RunWith(AndroidJUnit4::class)
class OnboardingRelayRepairFfiIntegrationTest {
    /** The actual Fix relay setup adapter previews and cancels without publishing, then requires fresh consent. */
    @Test fun minimalRepairCancelsWithoutPublicationAndRequiresCurrentApproval() =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            MarmotAndroid.initialize(context)
            val root = File(context.cacheDir, "onboarding-minimal-${UUID.randomUUID()}").apply { mkdirs() }
            try {
                LoopbackNostrRelay().use { relay ->
                    withTimeout(120_000L) {
                        val native = openNative(root, relay.url)
                        try {
                            verifyMinimalRepair(native, relay)
                        } finally {
                            native.shutdownAndClose()
                        }
                    }
                }
            } finally {
                check(root.deleteRecursively())
            }
        }

    /** Missing relay data exercises the minimal additive API rather than the explicit manual setter or full reset. */
    private suspend fun verifyMinimalRepair(
        native: Marmot,
        relay: LoopbackNostrRelay,
    ) {
        val initial = prepareRelayDecision(native, relay.url)
        assertTrue(
            OnboardingActionFfi.USE_RECOMMENDED_RELAYS in
                initial.steps.first { it.step == OnboardingStepFfi.RELAYS }.actions,
        )
        val client = MarmotAccountSetupClient(native, initial.accountIdHex) { error("Unexpected signer reconnect") }
        val request =
            SetupRequest(
                initial.revision,
                OnboardingStepFfi.RELAYS,
                OnboardingActionFfi.USE_RECOMMENDED_RELAYS,
                recoveryEpoch = initial.recoveryEpoch,
            )
        val first = requireNotNull(client.execute(request))
        val repair = requireNotNull(first.proposal?.relayRepair)
        assertEquals(OnboardingRelayRepairModeFfi.ADDITIVE, repair.mode)
        assertTrue(repair.beforeTags.isEmpty())
        assertEquals(listOf(listOf("r", relay.url)), repair.afterTags.map { it.fields })
        assertTrue(relay.recordedEvents(RELAY_LIST).isEmpty())
        val cancelled =
            requireNotNull(
                client.execute(request.copy(revision = first.revision, action = OnboardingActionFfi.CANCEL_REPAIR)),
            )
        assertNull(cancelled.proposal)
        assertNull(requireNotNull(client.snapshot()).proposal)
        assertTrue(relay.recordedEvents(RELAY_LIST).isEmpty())
        val second = requireNotNull(client.execute(request.copy(revision = cancelled.revision)))
        val approvedRequest =
            request.copy(
                revision = second.revision,
                action = OnboardingActionFfi.APPROVE_REPAIR,
                recoveryEpoch = second.recoveryEpoch,
            )
        val staleApproval =
            try {
                client.execute(approvedRequest.copy(revision = first.revision))
                false
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                true
            }
        assertTrue("A cancelled preview must not authorize its replacement", staleApproval)
        assertEquals(second.proposal, requireNotNull(client.snapshot()).proposal)
        assertTrue(relay.recordedEvents(RELAY_LIST).isEmpty())
        val approvedRepair = requireNotNull(second.proposal?.relayRepair)
        client.execute(approvedRequest)
        val event = relay.recordedEvents(RELAY_LIST).single()
        assertEquals(
            JSONArray(approvedRepair.afterTags.map { it.fields }).toString(),
            event.getJSONArray("tags").toString(),
        )
        assertEquals(approvedRepair.proposedContent, event.getString("content"))
        assertEquals(initial.accountIdHex, event.getString("pubkey"))
    }

    /** Preparing and restoring a manual proposal cannot publish; only its current revision can approve it. */
    @Test fun manualPreviewSurvivesRestartAndRequiresExactApproval() =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            MarmotAndroid.initialize(context)
            val root = File(context.cacheDir, "onboarding-relay-${UUID.randomUUID()}").apply { mkdirs() }
            try {
                LoopbackNostrRelay().use { relay ->
                    withTimeout(120_000L) {
                        val preview = prepareManualPreview(root, relay.url)
                        val repair = requireNotNull(preview.proposal?.relayRepair)
                        assertEquals(OnboardingRelayRepairModeFfi.ADDITIVE, repair.mode)
                        assertEquals(listOf(listOf("r", relay.url)), repair.afterTags.map { it.fields })
                        assertTrue(relay.recordedEvents(RELAY_LIST).isEmpty())
                        val native = openNative(root, relay.url)
                        try {
                            val restored = requireNotNull(native.onboardingSnapshot(preview.accountIdHex))
                            assertEquals(preview.proposal, restored.proposal)
                            assertTrue(
                                runCatching {
                                    native.approveOnboardingRepair(preview.accountIdHex, preview.revision - 1uL)
                                }.isFailure,
                            )
                            assertTrue(relay.recordedEvents(RELAY_LIST).isEmpty())
                            native.approveOnboardingRepair(preview.accountIdHex, preview.revision)
                            val event = relay.recordedEvents(RELAY_LIST).single()
                            assertEquals(
                                JSONArray(repair.afterTags.map { it.fields }).toString(),
                                event.getJSONArray("tags").toString(),
                            )
                            assertEquals(repair.proposedContent, event.getString("content"))
                            assertEquals(preview.accountIdHex, event.getString("pubkey"))
                        } finally {
                            native.shutdownAndClose()
                        }
                    }
                }
            } finally {
                check(root.deleteRecursively())
            }
        }

    /** Starts from missing relay data and submits equivalent URL spellings through the public manual setter. */
    private suspend fun prepareManualPreview(
        root: File,
        relay: String,
    ): OnboardingSnapshotFfi {
        val native = openNative(root, relay)
        try {
            val snapshot = prepareRelayDecision(native, relay)
            return native.proposeOnboardingRelays(
                snapshot.accountIdHex,
                OnboardingStepFfi.RELAYS,
                listOf(relay, "$relay/"),
                listOf("$relay/"),
            )
        } finally {
            native.shutdownAndClose()
        }
    }

    /** Reaches a real missing-relay decision using only an isolated identity and loopback discovery. */
    private suspend fun prepareRelayDecision(
        native: Marmot,
        relay: String,
    ): OnboardingSnapshotFfi {
        // Public test vector for scalar 1, used only with an isolated store and loopback relay.
        val snapshot =
            native.beginOnboarding(
                "nsec1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqsmhltgl",
                OnboardingOptionsFfi(listOf(relay), listOf(relay), listOf(relay)),
            )
        var current = native.runOnboarding(snapshot.accountIdHex)
        for (step in listOf(OnboardingStepFfi.PROFILE, OnboardingStepFfi.FOLLOWS)) {
            if (OnboardingActionFfi.CONTINUE_WITHOUT in current.steps.first { it.step == step }.actions) {
                current = native.continueOnboardingWithout(snapshot.accountIdHex, step)
            }
        }
        assertTrue(
            OnboardingActionFfi.EDIT_RELAYS in current.steps.first { it.step == OnboardingStepFfi.RELAYS }.actions,
        )
        return current
    }

    /** Reopens the same disposable store with loopback traffic explicitly enabled. */
    private suspend fun openNative(
        root: File,
        relay: String,
    ): Marmot =
        Marmot
            .newWithConfiguration(
                root.path,
                listOf(relay),
                MarmotOptions(relayPolicy = RelayPolicyFfi.ALLOW_LOOPBACK_RELAYS_AND_BLOBS),
            ).also { it.start() }

    private companion object {
        const val RELAY_LIST = 10002
    }
}
