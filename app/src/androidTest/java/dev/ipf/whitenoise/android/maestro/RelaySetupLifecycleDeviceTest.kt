package dev.ipf.whitenoise.android.maestro

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.ipf.marmotkit.Marmot
import dev.ipf.marmotkit.MarmotAndroid
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.MarmotOptions
import dev.ipf.marmotkit.OnboardingActionFfi
import dev.ipf.marmotkit.OnboardingIssueFfi
import dev.ipf.marmotkit.OnboardingOptionsFfi
import dev.ipf.marmotkit.OnboardingSnapshotFfi
import dev.ipf.marmotkit.OnboardingStatusFfi
import dev.ipf.marmotkit.OnboardingStepFfi
import dev.ipf.marmotkit.RelayPolicyFfi
import dev.ipf.whitenoise.android.MainActivity
import dev.ipf.whitenoise.android.ManualDeviceFixture
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.AppMarmotRuntime
import dev.ipf.whitenoise.android.state.AppPhase
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.LoopbackNostrRelay
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.onboarding.setup.SetupRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/** Real MainActivity journeys restricted to the test-only application, including on an authorized physical device. */
@ManualDeviceFixture
class RelaySetupLifecycleDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()

    /** Rotation and Later preserve the native preview; reopening and cancelling restore both repair choices. */
    @Test fun importRotationLaterResumeAndCancelKeepConsent() =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            check(context.packageName == MaestroFixtureRunner.FIXTURE_PACKAGE)
            check(InstrumentationRegistry.getArguments().getString("relayLifecycleE2e") == "true")
            val application = context.applicationContext as MaestroFixtureApplication
            val phase = InstrumentationRegistry.getArguments().getString("relayProcessPhase")
            val token =
                InstrumentationRegistry.getArguments().getString("relayProcessToken") ?: UUID.randomUUID().toString()
            check(token.matches(Regex("[a-f0-9-]{36}")))
            check(phase == null || phase in setOf("prepare", "restore"))
            val root = File(context.filesDir, "relay-lifecycle-$token")
            if (phase == "restore") check(root.isDirectory) else check(root.mkdir())
            MarmotAndroid.initialize(context)
            val mixed = InstrumentationRegistry.getArguments().getString("relayMixedFixture") == "true"
            LoopbackNostrRelay(
                if (mixed || InstrumentationRegistry.getArguments().getString("relayChangedSource") == "true") {
                    44202
                } else if (phase == null) {
                    0
                } else {
                    44201
                },
            ).use { relay ->
                if (mixed) seedMixedDeclarations(context, relay)
                val native =
                    Marmot.newWithConfiguration(
                        root.path,
                        listOf(relay.url),
                        MarmotOptions(relayPolicy = RelayPolicyFfi.ALLOW_LOOPBACK_RELAYS_AND_BLOBS),
                    )
                var state: WhiteNoiseAppState? = null
                var activity: ActivityScenario<MainActivity>? = null
                try {
                    val app = createState(context, root, native, relay.url)
                    state = app
                    application.fixtureState = app
                    withTimeout(30_000L) { app.bootstrap() }
                    assertEquals(AppPhase.Onboarding, app.phase)
                    activity = ActivityScenario.launch(MainActivity::class.java)
                    if (phase == "restore") {
                        verifyProcessRestore(app, native, relay, root)
                    } else if (mixed) {
                        verifyMixedImport(context, app, native, relay)
                    } else {
                        verifyImportAndResume(context, app, activity, native, relay)
                    }
                } finally {
                    activity?.close()
                    closeFixture(context, root, state, native)
                }
            }
        }

    /** Drains native readers and commands before closing their runtime, even when the test fails. */
    private suspend fun closeFixture(
        context: Context,
        root: File,
        state: WhiteNoiseAppState?,
        native: Marmot,
    ) = withContext(NonCancellable) {
        try {
            withTimeout(15_000L) {
                withContext(Dispatchers.Main.immediate) { state?.accountSetup?.close() }
                state?.stopNotificationListenerForAccountTeardown()
                state
                    ?.mutationsScope
                    ?.coroutineContext
                    ?.get(Job)
                    ?.cancelAndJoin()
            }
        } finally {
            withTimeout(15_000L) { native.shutdownAndClose() }
            context.deleteSharedPreferences(root.name)
            check(root.deleteRecursively())
        }
    }

    /** Creates a disposable app state without scheduling production push recovery. */
    private suspend fun createState(
        context: Context,
        root: File,
        native: Marmot,
        relay: String,
    ): WhiteNoiseAppState =
        withContext(Dispatchers.Main.immediate) {
            WhiteNoiseAppState(
                context,
                DraftStore.forContext(context),
                { null },
                emptyList(),
                "",
                marmotRuntimeFactory = { AppMarmotRuntime(root.path, loopbackImport(native, relay)) },
                schedulePushWakeRecovery = { false },
                preferences = context.getSharedPreferences(root.name, Context.MODE_PRIVATE),
            )
        }

    /** Keeps production import behavior while redirecting network defaults to the isolated relay. */
    private fun loopbackImport(
        native: Marmot,
        relay: String,
    ): MarmotInterface =
        object : MarmotInterface by native {
            override suspend fun beginOnboarding(
                nsec: String,
                options: OnboardingOptionsFfi,
            ): OnboardingSnapshotFfi {
                val fixtureOptions = OnboardingOptionsFfi(listOf(relay), listOf(relay), listOf(relay))
                return native.beginOnboarding(nsec, fixtureOptions)
            }
        }

    /** Uses public sign-in and setup controls, retaining exact native approval identity across navigation. */
    private suspend fun verifyImportAndResume(
        context: Context,
        app: WhiteNoiseAppState,
        activity: ActivityScenario<MainActivity>,
        native: Marmot,
        relay: LoopbackNostrRelay,
    ) {
        importToRelayStep(context, app)
        val setup = requireNotNull(app.accountSetup.controller)
        compose.onNodeWithText("Fix relay setup").performScrollTo().performClick()
        compose.waitUntil(30_000L) {
            !setup.state.value.busy &&
                setup.state.value.snapshot
                    ?.proposal != null
        }
        val preview = requireNotNull(native.onboardingSnapshot(setup.account))
        if (InstrumentationRegistry.getArguments().getString("relayChangedSource") == "true") {
            verifyChangedSource(context, app, native, relay)
            return
        }
        assertTrue(relay.publicationAttempts(10002).isEmpty())
        if (InstrumentationRegistry.getArguments().getString("relayProcessPhase") == "prepare") {
            val token = requireNotNull(InstrumentationRegistry.getArguments().getString("relayProcessToken"))
            File(context.filesDir, "relay-lifecycle-$token/checkpoint.json").writeText(
                JSONObject().put("account", setup.account).put("proposal", preview.proposal.toString()).toString(),
            )
            // Host force-stops only this isolated package after observing the durable marker.
            awaitCancellation()
        }
        activity.recreate()
        compose.waitForIdle()
        assertEquals(setup.account, app.accountSetup.controller?.account)
        assertEquals(preview.proposal, requireNotNull(native.onboardingSnapshot(setup.account)).proposal)
        compose.onNodeWithTag("setup-later").performScrollTo().performClick()
        compose.waitUntil(30_000L) { app.accountSetup.controller == null }
        assertTrue(relay.publicationAttempts(10002).isEmpty())
        // The production account-selection path reopens the retained native checkpoint.
        withContext(Dispatchers.Main.immediate) { assertTrue(app.accountSetup.routeIfPending(setup.account)) }
        compose.waitUntil(30_000L) {
            app.accountSetup.controller
                ?.state
                ?.value
                ?.busy == false
        }
        compose.onNodeWithTag("setup-step-RELAYS").performScrollTo().performClick()
        compose.onNodeWithTag("setup-action-CANCEL_REPAIR").performScrollTo().performClick()
        compose.waitUntil(30_000L) {
            app.accountSetup.controller
                ?.state
                ?.value
                ?.snapshot
                ?.proposal == null &&
                app.accountSetup.controller
                    ?.state
                    ?.value
                    ?.busy == false
        }
        assertNull(requireNotNull(native.onboardingSnapshot(setup.account)).proposal)
        compose.onNodeWithText("Fix relay setup").assertIsDisplayed()
        compose.onNodeWithText("Review relays").assertIsDisplayed()
        assertTrue(relay.publicationAttempts(10002).isEmpty())
        verifyAccountReplacement(app, native, relay, setup.account)
        verifyRejectedPublicationAndRetry(app, native, relay, setup.account)
    }

    /** Imports the public fixture identity through production controls and opens its relay decision. */
    private fun importToRelayStep(
        context: Context,
        app: WhiteNoiseAppState,
    ) {
        compose.onNodeWithText(context.getString(R.string.onboarding_login)).performClick()
        compose.onNodeWithTag("onboarding.sign_in.private_key").performTextInput(TEST_NSEC)
        compose.onNodeWithTag("onboarding.sign_in.action").performClick()
        compose.waitUntil(30_000L) { app.accountSetup.controller != null }
        val setup = requireNotNull(app.accountSetup.controller)
        compose.waitUntil(30_000L) {
            !setup.state.value.busy &&
                setup.state.value.currentStep
                    ?.step == OnboardingStepFfi.PROFILE
        }
        compose.onNodeWithTag("setup-step-PROFILE").performScrollTo().performClick()
        compose.onNodeWithTag("setup-action-CONTINUE_WITHOUT").performScrollTo().performClick()
        compose.waitUntil(30_000L) {
            !setup.state.value.busy &&
                setup.state.value.currentStep
                    ?.step == OnboardingStepFfi.RELAYS
        }
        compose.onNodeWithTag("setup-step-RELAYS").performScrollTo().performClick()
    }

    /** After host process termination, production routing restores the exact unapproved native proposal. */
    private suspend fun verifyProcessRestore(
        app: WhiteNoiseAppState,
        native: Marmot,
        relay: LoopbackNostrRelay,
        root: File,
    ) {
        val saved = JSONObject(File(root, "checkpoint.json").readText())
        val account = saved.getString("account")
        val restored = requireNotNull(native.onboardingSnapshot(account))
        assertEquals(saved.getString("proposal"), restored.proposal.toString())
        assertTrue(relay.publicationAttempts(10002).isEmpty())
        withContext(Dispatchers.Main.immediate) { assertTrue(app.accountSetup.routeIfPending(account)) }
        compose.waitUntil(30_000L) {
            app.accountSetup.controller
                ?.state
                ?.value
                ?.busy == false
        }
        compose.onNodeWithTag("setup-step-RELAYS").performScrollTo().performClick()
        compose.onNodeWithText("Save changes").assertIsDisplayed()
        compose.onNodeWithTag("setup-action-CANCEL_REPAIR").performScrollTo().performClick()
        compose.waitUntil(30_000L) {
            app.accountSetup.controller
                ?.state
                ?.value
                ?.snapshot
                ?.proposal == null
        }
        assertNull(requireNotNull(native.onboardingSnapshot(account)).proposal)
        assertTrue(relay.publicationAttempts(10002).isEmpty())
    }

    /** Loads signed declarations from the pinned native exporter, never personal account metadata. */
    private fun seedMixedDeclarations(
        context: Context,
        relay: LoopbackNostrRelay,
    ) {
        val fixture = JSONObject(File(context.cacheDir, "pr3201-mixed-relays.json").readText())
        check(fixture.getString("mdk") == "d4e91c8d90293de1a664a950a134f747dbb197a8")
        val records = fixture.getJSONArray("events")
        relay.seedEvents((0 until records.length()).map(records::getJSONObject))
    }

    /** Usable mixed-health declarations advance import without rewriting roles, duplicates, or opaque fields. */
    private suspend fun verifyMixedImport(
        context: Context,
        app: WhiteNoiseAppState,
        native: Marmot,
        relay: LoopbackNostrRelay,
    ) {
        val before = listOf(10002, 10050).associateWith { relay.recordedEvents(it).single().toString() }
        compose.onNodeWithText(context.getString(R.string.onboarding_login)).performClick()
        compose.onNodeWithTag("onboarding.sign_in.private_key").performTextInput(TEST_NSEC)
        compose.onNodeWithTag("onboarding.sign_in.action").performClick()
        compose.waitUntil(30_000L) {
            app.accountSetup.controller
                ?.state
                ?.value
                ?.busy == false
        }
        val setup = requireNotNull(app.accountSetup.controller)
        compose.onNodeWithTag("setup-step-PROFILE").performScrollTo().performClick()
        compose.onNodeWithTag("setup-action-CONTINUE_WITHOUT").performScrollTo().performClick()
        compose.waitUntil(60_000L) { !setup.state.value.busy }
        val snapshot = requireNotNull(native.onboardingSnapshot(setup.account))
        for (step in listOf(OnboardingStepFfi.RELAYS, OnboardingStepFfi.INBOX_RELAYS)) {
            val result = snapshot.steps.single { it.step == step }
            assertEquals("$step must stay usable", OnboardingStatusFfi.PASSED, result.status)
            assertTrue(result.findings.any { it.issue == OnboardingIssueFfi.RETIRED_RELAY })
            compose.onNodeWithTag("setup-step-$step").performScrollTo().assertIsDisplayed()
        }
        assertNull(snapshot.proposal)
        for (kind in listOf(10002, 10050)) {
            assertTrue(relay.publicationAttempts(kind).isEmpty())
            assertEquals(before.getValue(kind), relay.recordedEvents(kind).single().toString())
        }
    }

    /** A newer signed source invalidates the displayed approval instead of overwriting its declaration. */
    private suspend fun verifyChangedSource(
        context: Context,
        app: WhiteNoiseAppState,
        native: Marmot,
        relay: LoopbackNostrRelay,
    ) {
        val setup = requireNotNull(app.accountSetup.controller)
        seedMixedDeclarations(context, relay)
        compose.onNodeWithText("Save changes").performScrollTo().performClick()
        compose.waitUntil(30_000L) { !setup.state.value.busy }
        val snapshot = requireNotNull(native.onboardingSnapshot(setup.account))
        assertNull(snapshot.proposal)
        assertTrue(
            snapshot.steps
                .single { it.step == OnboardingStepFfi.RELAYS }
                .findings
                .any { it.issue == OnboardingIssueFfi.RECORD_CHANGED },
        )
        assertTrue(relay.publicationAttempts(10002).isEmpty())
    }

    /** A captured callback from the first account cannot publish or replace the newly selected setup owner. */
    private suspend fun verifyAccountReplacement(
        app: WhiteNoiseAppState,
        native: Marmot,
        relay: LoopbackNostrRelay,
        account: String,
    ) {
        val previous = requireNotNull(app.accountSetup.controller)
        val first = requireNotNull(native.onboardingSnapshot(account))
        val other =
            native.beginOnboarding(
                SECOND_NSEC,
                OnboardingOptionsFfi(listOf(relay.url), listOf(relay.url), listOf(relay.url)),
            )
        withContext(Dispatchers.Main.immediate) { app.accountSetup.open(other) }
        compose.waitUntil(30_000L) {
            app.accountSetup.controller
                ?.state
                ?.value
                ?.busy == false
        }
        val current = requireNotNull(app.accountSetup.controller)
        assertEquals(other.accountIdHex, current.account)
        withContext(Dispatchers.Main.immediate) {
            previous.submit(
                SetupRequest(
                    first.revision,
                    OnboardingStepFfi.RELAYS,
                    OnboardingActionFfi.USE_RECOMMENDED_RELAYS,
                    recoveryEpoch = first.recoveryEpoch,
                ),
            )
        }
        compose.waitForIdle()
        assertEquals(other.accountIdHex, app.accountSetup.controller?.account)
        assertNull(requireNotNull(native.onboardingSnapshot(account)).proposal)
        assertTrue(relay.publicationAttempts(10002).isEmpty())
        withContext(Dispatchers.Main.immediate) { app.accountSetup.routeIfPending(account) }
        compose.waitUntil(30_000L) {
            app.accountSetup.controller
                ?.state
                ?.value
                ?.busy == false
        }
        compose.onNodeWithTag("setup-step-RELAYS").performScrollTo().performClick()
    }

    /** A failed relay acknowledgement stays resumable and retries the identical signed event after explicit consent. */
    private suspend fun verifyRejectedPublicationAndRetry(
        app: WhiteNoiseAppState,
        native: Marmot,
        relay: LoopbackNostrRelay,
        account: String,
    ) {
        val setup = requireNotNull(app.accountSetup.controller)
        compose.onNodeWithText("Fix relay setup").performClick()
        compose.waitUntil(30_000L) {
            !setup.state.value.busy &&
                setup.state.value.snapshot
                    ?.proposal != null
        }
        relay.rejectedPublicationKinds = setOf(10002)
        compose.onNodeWithText("Save changes").performScrollTo().performClick()
        compose.waitUntil(60_000L) { !setup.state.value.busy && relay.publicationAttempts(10002).isNotEmpty() }
        val rejected = requireNotNull(native.onboardingSnapshot(account))
        assertTrue(!rejected.ready)
        assertTrue(relay.recordedEvents(10002).isEmpty())
        val attempts = relay.publicationAttempts(10002)
        val signedId = attempts.first().getString("id")
        assertTrue(attempts.all { it.getString("id") == signedId })
        relay.rejectedPublicationKinds = emptySet()
        relay.authAfterPublicationKinds = setOf(10002)
        compose.onNodeWithTag("setup-action-RETRY").performScrollTo().performClick()
        compose.waitUntil(60_000L) { !setup.state.value.busy && relay.recordedEvents(10002).isNotEmpty() }
        assertEquals(signedId, relay.recordedEvents(10002).single().getString("id"))
        assertTrue(relay.publicationAttempts(10002).all { it.getString("id") == signedId })
        val unreadable = requireNotNull(native.onboardingSnapshot(account))
        assertTrue(!unreadable.ready)
        assertTrue(unreadable.steps.single { it.step == OnboardingStepFfi.RELAYS }.status != OnboardingStatusFfi.PASSED)
        val publishedCount = relay.publicationAttempts(10002).size
        relay.authAfterPublicationKinds = emptySet()
        relay.authRequiredKinds = emptySet()
        compose.onNodeWithTag("setup-action-RETRY").performScrollTo().performClick()
        compose.waitUntil(60_000L) { !setup.state.value.busy }
        val recovered = requireNotNull(native.onboardingSnapshot(account))
        assertEquals(OnboardingStatusFfi.PASSED, recovered.steps.single { it.step == OnboardingStepFfi.RELAYS }.status)
        assertEquals(publishedCount, relay.publicationAttempts(10002).size)
    }

    private companion object {
        const val SECOND_NSEC = "nsec1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqpqptcfk2"
        const val TEST_NSEC = "nsec1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqsmhltgl"
    }
}
