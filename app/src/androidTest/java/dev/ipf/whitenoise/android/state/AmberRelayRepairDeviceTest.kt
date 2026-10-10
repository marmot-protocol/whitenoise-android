package dev.ipf.whitenoise.android.state

import android.os.Build
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import dev.ipf.marmotkit.Marmot
import dev.ipf.marmotkit.MarmotAndroid
import dev.ipf.marmotkit.MarmotOptions
import dev.ipf.marmotkit.OnboardingActionFfi
import dev.ipf.marmotkit.OnboardingOptionsFfi
import dev.ipf.marmotkit.OnboardingSnapshotFfi
import dev.ipf.marmotkit.OnboardingStepFfi
import dev.ipf.marmotkit.RelayPolicyFfi
import dev.ipf.whitenoise.android.MainActivity
import dev.ipf.whitenoise.android.ManualDeviceFixture
import dev.ipf.whitenoise.android.amber.AmberSignerController
import dev.ipf.whitenoise.android.amber.Nip55
import dev.ipf.whitenoise.android.ui.onboarding.setup.MarmotAccountSetupClient
import dev.ipf.whitenoise.android.ui.onboarding.setup.SetupRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/** Opt-in interoperability fixture requiring official Amber with the public scalar-one test key on an emulator. */
@ManualDeviceFixture
class AmberRelayRepairDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    /** Actual Amber denial must publish nothing; explicit native retry can then request and receive consent. */
    @Test fun rejectedRelaySignatureCanBeRetriedExplicitly() =
        runBlocking {
            assumeTrue(InstrumentationRegistry.getArguments().getString("amberRelayE2e") == "true")
            check(Build.MODEL.startsWith("sdk_") || Build.FINGERPRINT.contains("generic")) {
                "This fixture requires a disposable emulator"
            }
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            MarmotAndroid.initialize(context)
            check(AmberSignerController.isSignerInstalled(context)) {
                "Install official Amber with the fixture identity"
            }
            Nip55.saveSignerPackage(context, Nip55.AMBER_PACKAGE)
            val root = File(context.cacheDir, "amber-relay-${UUID.randomUUID()}").apply { mkdirs() }
            try {
                LoopbackNostrRelay().use { relay ->
                    val native =
                        Marmot.newWithConfiguration(
                            root.path,
                            listOf(relay.url),
                            MarmotOptions(relayPolicy = RelayPolicyFfi.ALLOW_LOOPBACK_RELAYS_AND_BLOBS),
                        )
                    try {
                        withTimeout(180_000L) { verifyDenialAndRetry(native, relay) }
                    } finally {
                        native.shutdownAndClose()
                    }
                }
            } finally {
                check(root.deleteRecursively())
            }
        }

    /** Keeps the public test account's native checkpoint separate from the application's normal account store. */
    private suspend fun verifyDenialAndRetry(
        native: Marmot,
        relay: LoopbackNostrRelay,
    ) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        native.start()
        var current =
            native.beginExternalSignerOnboarding(
                PUBLIC_KEY,
                AmberSignerController(context).buildSigner(PUBLIC_KEY),
                OnboardingOptionsFfi(listOf(relay.url), listOf(relay.url), listOf(relay.url)),
            )
        current = native.runOnboarding(current.accountIdHex)
        for (step in listOf(OnboardingStepFfi.PROFILE, OnboardingStepFfi.FOLLOWS)) {
            if (OnboardingActionFfi.CONTINUE_WITHOUT in current.steps.first { it.step == step }.actions) {
                current = native.continueOnboardingWithout(current.accountIdHex, step)
            }
        }
        val client = MarmotAccountSetupClient(native, current.accountIdHex) { error("Unexpected signer reconnect") }
        val preview = client.previewRelayRepair(OnboardingStepFfi.RELAYS)
        val approval = request(preview, OnboardingActionFfi.APPROVE_REPAIR)
        assertTrue(relay.publicationAttempts(RELAY_LIST).isEmpty())
        val cancellation = InstrumentationRegistry.getArguments().getString("amberBackBeforeReject") == "true"
        approveWithAmber(client, approval, if (cancellation) "BackThenReject" else "Reject")
        assertTrue(relay.publicationAttempts(RELAY_LIST).isEmpty())
        compose.activityRule.scenario.recreate()
        native.registerExternalSigner(PUBLIC_KEY, AmberSignerController(context).buildSigner(PUBLIC_KEY))
        val rejected = requireNotNull(client.snapshot())
        assertTrue(!rejected.ready)
        approveWithAmber(client, retryRequest(rejected), "Accept")
        assertEquals(1, relay.recordedEvents(RELAY_LIST).size)
        assertEquals(1, relay.publicationAttempts(RELAY_LIST).size)
        assertTrue(!requireNotNull(client.snapshot()).ready)
    }

    /** Runs the real NIP-55 handoff while selecting an explicit answer in the separate Amber process. */
    private suspend fun approveWithAmber(
        client: MarmotAccountSetupClient,
        request: SetupRequest,
        answer: String,
    ) = coroutineScope {
        val result =
            async(Dispatchers.IO) {
                try {
                    Result.success(client.execute(request))
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (failure: Exception) {
                    Result.failure(failure)
                }
            }
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.waitForIdle()
        val buttonText = if (answer == "BackThenReject") "Reject" else answer
        val button = device.wait(Until.findObject(By.text(buttonText)), 30_000L)
        checkNotNull(button) { "Amber did not display $answer" }
        if (answer == "BackThenReject") {
            device.pressBack()
            device.waitForIdle()
            assertTrue("Amber Back must not manufacture an approval", !result.isCompleted)
            val reject = device.wait(Until.findObject(By.text("Reject")), 5_000L)
            checkNotNull(reject) { "Amber did not retain its explicit rejection control" }
            reject.click()
        } else {
            button.click()
        }
        val outcome = withTimeout(20_000L) { result.await() }
        if (answer == "Accept") outcome.getOrThrow()
    }

    /** Uses the next action MDK exposes after the rejected or cancelled signature. */
    private fun retryRequest(snapshot: OnboardingSnapshotFfi): SetupRequest {
        val actions = snapshot.steps.first { it.step == OnboardingStepFfi.RELAYS }.actions
        val action =
            if (OnboardingActionFfi.RETRY in actions) OnboardingActionFfi.RETRY else OnboardingActionFfi.APPROVE_REPAIR
        return request(snapshot, action)
    }

    /** Binds each device action to the latest native revision and recovery epoch. */
    private fun request(
        snapshot: OnboardingSnapshotFfi,
        action: OnboardingActionFfi,
    ) = SetupRequest(
        snapshot.revision,
        OnboardingStepFfi.RELAYS,
        action,
        recoveryEpoch = snapshot.recoveryEpoch,
    )

    private companion object {
        const val RELAY_LIST = 10002
        const val PUBLIC_KEY = "79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798"
    }
}
