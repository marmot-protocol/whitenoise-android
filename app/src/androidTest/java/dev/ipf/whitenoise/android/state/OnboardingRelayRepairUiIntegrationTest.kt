package dev.ipf.whitenoise.android.state

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import dev.ipf.marmotkit.Marmot
import dev.ipf.marmotkit.MarmotAndroid
import dev.ipf.marmotkit.MarmotOptions
import dev.ipf.marmotkit.OnboardingActionFfi
import dev.ipf.marmotkit.OnboardingOptionsFfi
import dev.ipf.marmotkit.OnboardingStepFfi
import dev.ipf.marmotkit.RelayPolicyFfi
import dev.ipf.whitenoise.android.ui.onboarding.setup.AccountSetupController
import dev.ipf.whitenoise.android.ui.onboarding.setup.AccountSetupScreen
import dev.ipf.whitenoise.android.ui.onboarding.setup.MarmotAccountSetupClient
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/** Exercises production Compose actions and its controller with the published native relay-repair implementation. */
class OnboardingRelayRepairUiIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    /** Cancel returns to both choices without publishing; a new repair needs its own explicit Save changes tap. */
    @Test fun reviewCancelThenFixRequiresExplicitApproval() =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            MarmotAndroid.initialize(context)
            val root = File(context.cacheDir, "relay-ui-${UUID.randomUUID()}").apply { mkdirs() }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            try {
                LoopbackNostrRelay().use { relay ->
                    val native =
                        Marmot.newWithConfiguration(
                            root.path,
                            listOf(relay.url),
                            MarmotOptions(relayPolicy = RelayPolicyFfi.ALLOW_LOOPBACK_RELAYS_AND_BLOBS),
                        )
                    var controller: AccountSetupController? = null
                    try {
                        val account = withTimeout(60_000L) { prepare(native, relay.url) }
                        val setup =
                            AccountSetupController(
                                account,
                                MarmotAccountSetupClient(native, account) { error("Unexpected signer reconnect") },
                                scope,
                                isCurrent = { true },
                                onReady = { error("Relay approval alone must not activate the account") },
                                onCancelled = { error("Editor cancellation must not cancel the account") },
                            )
                        controller = setup
                        verifyRepairFlow(setup, native, relay, account)
                    } finally {
                        controller?.close()
                        native.shutdownAndClose()
                    }
                }
            } finally {
                scope.cancel()
                check(root.deleteRecursively())
            }
        }

    /** Drives actual screen buttons and compares the resulting native publication with the reviewed proposal. */
    private suspend fun verifyRepairFlow(
        setup: AccountSetupController,
        native: Marmot,
        relay: LoopbackNostrRelay,
        account: String,
    ) {
        compose.setContent {
            WhiteNoiseTheme { AccountSetupScreen(setup, { "Fixture" }, {}) }
        }
        compose.runOnIdle { setup.reconnect() }
        awaitIdle(setup)
        compose.onNodeWithTag("setup-step-RELAYS").performClick()
        compose.onNodeWithText("Fix relay setup").performScrollTo().assertIsDisplayed()
        compose
            .onNodeWithText("Review relays")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        compose.waitUntil(30_000L) { setup.state.value.editor != null && !setup.state.value.busy }
        compose.onNodeWithTag("setup-editor-save").assertIsNotEnabled()
        assertTrue(relay.publicationAttempts(RELAY_LIST).isEmpty())
        compose.onNodeWithText("Cancel").performScrollTo().performClick()
        compose.waitUntil(30_000L) { setup.state.value.editor == null && !setup.state.value.busy }
        assertNull(requireNotNull(native.onboardingSnapshot(account)).proposal)
        compose.onNodeWithText("Fix relay setup").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Review relays").performScrollTo().assertIsDisplayed()
        assertTrue(relay.publicationAttempts(RELAY_LIST).isEmpty())
        compose.onNodeWithText("Fix relay setup").performClick()
        compose.waitUntil(30_000L) {
            setup.state.value.snapshot
                ?.proposal != null &&
                !setup.state.value.busy
        }
        val repair =
            requireNotNull(
                setup.state.value.snapshot
                    ?.proposal
                    ?.relayRepair,
            )
        assertTrue(relay.publicationAttempts(RELAY_LIST).isEmpty())
        compose.onNodeWithText("Save changes").performScrollTo().performClick()
        awaitIdle(setup)
        assertTrue("The controller must surface native errors", !setup.state.value.error)
        assertEquals(1, relay.publicationAttempts(RELAY_LIST).size)
        val event = relay.recordedEvents(RELAY_LIST).single()
        assertEquals(
            JSONArray(repair.afterTags.map { it.fields }).toString(),
            event.getJSONArray("tags").toString(),
        )
        assertEquals(repair.proposedContent, event.getString("content"))
        assertTrue(!requireNotNull(native.onboardingSnapshot(account)).ready)
    }

    /** Bounds native startup and reaches the actual missing-relay decision for an isolated public test key. */
    private suspend fun prepare(
        native: Marmot,
        relay: String,
    ): String {
        native.start()
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
        return snapshot.accountIdHex
    }

    /** Waits for a completed native command rather than guessing relay or device timing. */
    private fun awaitIdle(controller: AccountSetupController) {
        compose.waitUntil(30_000L) { controller.state.value.snapshot != null && !controller.state.value.busy }
    }

    private companion object {
        const val RELAY_LIST = 10002
    }
}
