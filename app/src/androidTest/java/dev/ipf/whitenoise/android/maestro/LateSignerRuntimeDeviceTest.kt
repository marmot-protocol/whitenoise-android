package dev.ipf.whitenoise.android.maestro

import android.content.Context
import android.os.Build
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import dev.ipf.marmotkit.ExternalAccountSignerFfi
import dev.ipf.marmotkit.Marmot
import dev.ipf.marmotkit.MarmotAndroid
import dev.ipf.marmotkit.MarmotOptions
import dev.ipf.marmotkit.OnboardingOptionsFfi
import dev.ipf.marmotkit.OnboardingStepFfi
import dev.ipf.marmotkit.RelayPolicyFfi
import dev.ipf.whitenoise.android.MainActivity
import dev.ipf.whitenoise.android.ManualDeviceFixture
import dev.ipf.whitenoise.android.amber.AmberSignerController
import dev.ipf.whitenoise.android.amber.Nip55
import dev.ipf.whitenoise.android.state.AppMarmotRuntime
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.LoopbackNostrRelay
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Holds a genuine Amber signature across native runtime replacement, without using a personal identity. */
@ManualDeviceFixture
class LateSignerRuntimeDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()

    /** A real signed response for the retired runtime cannot publish or complete another account's setup. */
    @Test fun approvedOldSignatureCannotCrossRuntimeReplacement() =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            check(context.packageName == MaestroFixtureRunner.FIXTURE_PACKAGE)
            check(InstrumentationRegistry.getArguments().getString("lateSignerE2e") == "true")
            check(Build.MODEL.startsWith("sdk_") || Build.FINGERPRINT.contains("generic"))
            MarmotAndroid.initialize(context)
            Nip55.saveSignerPackage(context, Nip55.AMBER_PACKAGE)
            val signer = HeldSigner(AmberSignerController(context).buildSigner(PUBLIC_KEY))
            val root = File(context.filesDir, "late-signer-${UUID.randomUUID()}").apply { mkdirs() }
            LoopbackNostrRelay().use { relay ->
                var current: Session? = null
                var activity: ActivityScenario<MainActivity>? = null
                try {
                    val first = openSession(context, root, relay.url)
                    current = first
                    val initial = first.native.beginExternalSignerOnboarding(PUBLIC_KEY, signer, options(relay.url))
                    withContext(Dispatchers.Main.immediate) { first.app.accountSetup.open(initial) }
                    activity = ActivityScenario.launch(MainActivity::class.java)
                    reachRelayApproval(first)
                    approveInAmber()
                    val signed = withTimeout(30_000) { signer.signed.await() }
                    assertEquals(PUBLIC_KEY, JSONObject(signed).getString("pubkey"))
                    assertTrue(relay.publicationAttempts(10002).isEmpty())
                    activity.close()
                    activity = null
                    closeSession(first)
                    current = null
                    val second = openSession(context, root, relay.url)
                    current = second
                    val next = second.native.beginOnboarding(SECOND_NSEC, options(relay.url))
                    withContext(Dispatchers.Main.immediate) { second.app.accountSetup.open(next) }
                    activity = ActivityScenario.launch(MainActivity::class.java)
                    verifyLateDelivery(second, signer, relay, next.accountIdHex)
                } finally {
                    signer.release.countDown()
                    activity?.close()
                    current?.let { closeSession(it) }
                    context.deleteSharedPreferences(root.name)
                    check(root.deleteRecursively())
                }
            }
        }

    /** The callback returns Amber's actual signed bytes only after the old app/runtime have been retired. */
    private class HeldSigner(
        delegate: ExternalAccountSignerFfi,
    ) : ExternalAccountSignerFfi by delegate {
        private val source = delegate
        val signed = CompletableDeferred<String>()
        val release = CountDownLatch(1)
        val returned = CountDownLatch(1)

        override fun signEvent(unsignedEventJson: String): String {
            val response = source.signEvent(unsignedEventJson)
            signed.complete(response)
            check(release.await(60, TimeUnit.SECONDS)) { "Fixture signature was not released" }
            returned.countDown()
            return response
        }
    }

    /** Freezes the new account's checkpoint before returning the genuine stale native callback. */
    private suspend fun verifyLateDelivery(
        session: Session,
        signer: HeldSigner,
        relay: LoopbackNostrRelay,
        account: String,
    ) {
        waitIdle(session)
        val before = requireNotNull(session.native.onboardingSnapshot(account))
        signer.release.countDown()
        compose.waitUntil(10_000) { signer.returned.count == 0L }
        compose.waitForIdle()
        assertEquals(
            account,
            session.app.accountSetup.controller
                ?.account,
        )
        assertEquals(before, session.native.onboardingSnapshot(account))
        assertTrue(relay.publicationAttempts(10002).isEmpty())
        assertTrue(relay.publicationAttempts(10050).isEmpty())
        // Positive control: only this current account's explicit consent can publish through the new runtime.
        reachRelayApproval(session)
        waitIdle(session)
        compose.waitUntil(30_000) { relay.publicationAttempts(10002).isNotEmpty() }
        assertEquals(account, relay.publicationAttempts(10002).single().getString("pubkey"))
    }

    /** Uses the actual setup controls, stopping only when the selected signer or native local key handles approval. */
    private fun reachRelayApproval(session: Session) {
        waitIdle(session)
        compose.onNodeWithTag("setup-step-PROFILE").performScrollTo().performClick()
        compose.onNodeWithTag("setup-action-CONTINUE_WITHOUT").performScrollTo().performClick()
        waitIdle(session)
        assertEquals(
            OnboardingStepFfi.RELAYS,
            session.app.accountSetup.controller
                ?.state
                ?.value
                ?.currentStep
                ?.step,
        )
        compose.onNodeWithTag("setup-step-RELAYS").performScrollTo().performClick()
        compose.onNodeWithText("Fix relay setup").performScrollTo().performClick()
        waitIdle(session)
        compose.onNodeWithText("Save changes").performScrollTo().performClick()
    }

    /** Confirms the real NIP-55 request in official Amber using only the preconfigured public scalar-one key. */
    private fun approveInAmber() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val accept = device.wait(Until.findObject(By.text("Accept")), 30_000)
        checkNotNull(accept) { "Amber approval was not displayed" }
        accept.click()
    }

    /** Waits for native state delivery instead of relying on arbitrary UI delays. */
    private fun waitIdle(session: Session) {
        compose.waitUntil(30_000) {
            session.app.accountSetup.controller
                ?.state
                ?.value
                ?.busy == false
        }
    }

    private data class Session(
        val native: Marmot,
        val app: WhiteNoiseAppState,
    )

    /** Opens a new native object on the same durable store and supplies it to a fresh application state. */
    private suspend fun openSession(
        context: Context,
        root: File,
        relay: String,
    ): Session {
        val native =
            Marmot.newWithConfiguration(
                root.path,
                listOf(relay),
                MarmotOptions(relayPolicy = RelayPolicyFfi.ALLOW_LOOPBACK_RELAYS_AND_BLOBS),
            )
        val app =
            withContext(Dispatchers.Main.immediate) {
                WhiteNoiseAppState(
                    context,
                    DraftStore.forContext(context),
                    { null },
                    emptyList(),
                    "",
                    marmotRuntimeFactory = { AppMarmotRuntime(root.path, native) },
                    schedulePushWakeRecovery = { false },
                    preferences = context.getSharedPreferences(root.name, Context.MODE_PRIVATE),
                )
            }
        (context.applicationContext as MaestroFixtureApplication).fixtureState = app
        withTimeout(30_000) { app.bootstrap() }
        return Session(native, app)
    }

    /** Waits for production controller cancellation and closes the old native runtime before replacement. */
    private suspend fun closeSession(session: Session) =
        withContext(NonCancellable) {
            withTimeout(15_000) {
                withContext(Dispatchers.Main.immediate) { session.app.accountSetup.close() }
                session.app.stopNotificationListenerForAccountTeardown()
                session.app.mutationsScope.coroutineContext[Job]
                    ?.cancelAndJoin()
                session.native.shutdownAndClose()
            }
        }

    /** Keeps every native discovery and publication endpoint inside the fixture. */
    private fun options(relay: String) = OnboardingOptionsFfi(listOf(relay), listOf(relay), listOf(relay))

    private companion object {
        const val PUBLIC_KEY = "79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798"
        const val SECOND_NSEC = "nsec1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqpqptcfk2"
    }
}
