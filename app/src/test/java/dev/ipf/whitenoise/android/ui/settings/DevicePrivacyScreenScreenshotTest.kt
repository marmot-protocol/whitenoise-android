package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.DiagnosticsExporterStatusFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.UsageDiagnosticsDecisionFfi
import dev.ipf.whitenoise.android.state.AccountSwitchLocalSnapshot
import dev.ipf.whitenoise.android.state.AccountSwitchLocalSnapshotHandoff
import dev.ipf.whitenoise.android.state.AppPhase
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.WhiteNoiseApp
import dev.ipf.whitenoise.android.ui.navigation.MainShellStateHolder
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class DevicePrivacyScreenScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** A pending native receipt must leave Welcome and the sign-in form unobstructed. */
    @Test
    fun pendingConsentNeverCoversWelcomeOrSignIn() {
        val state = privacyAppState(UsageDiagnosticsDecisionFfi.ACCEPTANCE_REQUIRED, hasAccount = false)
        runBlocking { state.refreshSecurityPrivacySettings() }
        val shell = presentBootstrappedApp(state, AppPhase.Onboarding)
        composeRule.onNodeWithText("Sign Up").assertIsDisplayed()
        composeRule.onNodeWithText("Help Improve White Noise").assertDoesNotExist()
        composeRule.onRoot().captureRoboImage("src/test/snapshots/usage_diagnostics_welcome_deferred.png")
        composeRule.onNodeWithText("Sign In").performClick()
        composeRule
            .onNodeWithTag("onboarding.sign_in.private_key")
            .assertIsDisplayed()
            .assertIsEnabled()
        composeRule.onNodeWithText("Help Improve White Noise").assertDoesNotExist()
        assertEquals(AppPhase.Onboarding, state.phase)
        assertTrue(state.diagnostics.requiresChoice)
        composeRule.runOnIdle { shell.release() }
    }

    /** Existing signed-in accounts with unanswered or renewed consent are prompted on Chats. */
    @Test
    fun existingAccountWithPendingConsentIsPromptedAtChats() {
        val state = privacyAppState(UsageDiagnosticsDecisionFfi.ACCEPTANCE_REQUIRED)
        runBlocking { state.refreshSecurityPrivacySettings() }
        val shell = presentBootstrappedApp(state, AppPhase.Ready)
        composeRule.onNodeWithText("Help Improve White Noise").assertIsDisplayed()
        composeRule.onNodeWithText("Done").assertIsDisplayed()
        composeRule.onRoot().captureRoboImage("src/test/snapshots/usage_diagnostics_chats_prompt.png")
        composeRule.runOnIdle { shell.release() }
    }

    /** A previous explicit decline is respected when an existing account opens Chats. */
    @Test
    fun existingAccountWithDeclinedConsentIsNotPromptedAgain() {
        assertExistingDecisionDoesNotPrompt(UsageDiagnosticsDecisionFfi.DECLINED)
    }

    /** A current grant is not presented as another unanswered choice at launch. */
    @Test
    fun existingAccountWithGrantedConsentIsNotPromptedAgain() {
        assertExistingDecisionDoesNotPrompt(UsageDiagnosticsDecisionFfi.GRANTED)
    }

    /** Exercises the real app-root receipt gate while confirming Chats is actually visible. */
    private fun assertExistingDecisionDoesNotPrompt(decision: UsageDiagnosticsDecisionFfi) {
        val state = privacyAppState(decision)
        runBlocking { state.refreshSecurityPrivacySettings() }
        val shell = presentBootstrappedApp(state, AppPhase.Ready)
        composeRule.onNodeWithContentDescription("New message").assertIsDisplayed()
        composeRule.onNodeWithText("Help Improve White Noise").assertDoesNotExist()
        assertEquals(decision, state.usageDiagnosticsSettings?.decision)
        composeRule.runOnIdle { shell.release() }
    }

    /** Seeds a completed offline startup so root effects cannot race a privacy-only native fake. */
    private fun presentBootstrappedApp(
        state: WhiteNoiseAppState,
        phase: AppPhase,
    ): MainShellStateHolder {
        WhiteNoiseAppState::class.java
            .getDeclaredMethod("setPhase", AppPhase::class.java)
            .apply { isAccessible = true }
            .invoke(state, phase)
        listOf("bootstrapCompleted", "networkNotificationRecoverySuppressed").forEach { field ->
            WhiteNoiseAppState::class.java
                .getDeclaredField(field)
                .apply { isAccessible = true }
                .setBoolean(state, true)
        }
        state.markDefaultNotificationsEnableAttempted()
        if (phase == AppPhase.Ready) {
            val handoff =
                WhiteNoiseAppState::class.java
                    .getDeclaredField("accountSwitchHandoff")
                    .apply { isAccessible = true }
                    .get(state) as AccountSwitchLocalSnapshotHandoff
            handoff.publish(
                handoff.beginRequest("account"),
                AccountSwitchLocalSnapshot(
                    "account",
                    "aa".repeat(32),
                    emptyList(),
                    emptyList(),
                    emptyList(),
                    emptyList(),
                ),
            )
        }
        val shell = MainShellStateHolder(state, SavedStateHandle())
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                WhiteNoiseApp(state, shell, warmResumeTraceToken = 0, warmResumeEpoch = 0)
            }
        }
        return shell
    }

    /** Pins the diagnostics switch when the unified MDK consent is granted. */
    @Test
    fun diagnosticsGranted() {
        captureConsentState(UsageDiagnosticsDecisionFfi.GRANTED, expectedChecked = true)
    }

    /** Pins the diagnostics switch when the unified MDK consent is declined. */
    @Test
    fun diagnosticsDeclined() {
        captureConsentState(UsageDiagnosticsDecisionFfi.DECLINED, expectedChecked = false)
    }

    /** First-launch prompt starts with both choices off and an explicit completion action. */
    @Test
    fun firstLaunchPrompt() {
        capturePrompt("usage_diagnostics_prompt", dark = false)
    }

    /** The compact grouping and full disclosure retain contrast in dark appearance. */
    @Test
    fun firstLaunchPromptDark() {
        capturePrompt("usage_diagnostics_prompt_dark", dark = true)
    }

    /** Dark, RTL and enlarged text keep the consent body scrollable and its action reachable. */
    @Test
    @Config(qualifiers = "ar-w360dp-h780dp-mdpi")
    fun firstLaunchPromptRtlLargeDark() {
        capturePrompt("usage_diagnostics_prompt_rtl_large_dark", dark = true, fontScale = 1.3f)
    }

    /** Failed receipt loading preserves a visible retry path without an enabled sharing toggle. */
    @Test
    fun firstLaunchPromptReadFailure() {
        capturePrompt("usage_diagnostics_prompt_error", dark = false, loadFailure = true)
    }

    /** Renders the receipt-backed prompt using a disposable fake, with no personal app data. */
    private fun capturePrompt(
        name: String,
        dark: Boolean,
        fontScale: Float = 1f,
        loadFailure: Boolean = false,
    ) {
        val appState = privacyAppState(UsageDiagnosticsDecisionFfi.ACCEPTANCE_REQUIRED)
        runBlocking {
            appState.refreshSecurityPrivacySettings()
            if (loadFailure) {
                val unavailable =
                    Proxy.newProxyInstance(
                        MarmotInterface::class.java.classLoader,
                        arrayOf(MarmotInterface::class.java),
                    ) { _, _, _ -> error("synthetic read failure") } as MarmotInterface
                appState.diagnostics.refresh(unavailable)
            }
        }
        composeRule.setContent {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides
                    androidx.compose.ui.unit
                        .Density(1f, fontScale),
            ) {
                WhiteNoiseTheme(darkTheme = dark, fontScale = fontScale) {
                    UsageDiagnosticsPrompt(appState, onDone = {})
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onAllNodes(isToggleable())[0].assertIsOff()
        composeRule.onNodeWithText("Done").assertIsDisplayed()
        val viewport =
            composeRule
                .onAllNodes(isRoot())
                .fetchSemanticsNodes()
                .maxBy { it.boundsInRoot.height }
                .boundsInRoot
        val done = composeRule.onNodeWithText("Done").fetchSemanticsNode().boundsInRoot
        assertTrue("Done $done must fit inside $viewport", done.bottom <= viewport.bottom)
        composeRule.onRoot().captureRoboImage("src/test/snapshots/$name.png")
    }

    /** Done records decline while leaving the separate logging preference untouched. */
    @Test
    fun doneSavesDeclineWithLogsOff() {
        val state = privacyAppState(UsageDiagnosticsDecisionFfi.ACCEPTANCE_REQUIRED)
        runBlocking { state.refreshSecurityPrivacySettings() }
        var dismissed = false
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                UsageDiagnosticsPrompt(state) { dismissed = true }
            }
        }
        composeRule.onNodeWithText("Done").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            org.robolectric.Shadows
                .shadowOf(android.os.Looper.getMainLooper())
                .idle()
            dismissed
        }
        assertEquals(UsageDiagnosticsDecisionFfi.DECLINED, state.usageDiagnosticsSettings?.decision)
        assertFalse(state.auditLogSettings?.enabled ?: true)
    }

    /** Grant enables the configured diagnostics exporter even when Aptabase has no key yet. */
    @Test
    fun acceptanceEnablesDiagnosticsWithIndependentLogsOff() {
        val state = privacyAppState(UsageDiagnosticsDecisionFfi.ACCEPTANCE_REQUIRED)
        runBlocking { state.refreshSecurityPrivacySettings() }
        var dismissed = false
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                UsageDiagnosticsPrompt(state) { dismissed = true }
            }
        }
        composeRule.onAllNodes(isToggleable())[0].performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            org.robolectric.Shadows
                .shadowOf(android.os.Looper.getMainLooper())
                .idle()
            state.isUsageDiagnosticsGranted() && !state.diagnostics.busy
        }
        composeRule.onNodeWithText("Done").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            org.robolectric.Shadows
                .shadowOf(android.os.Looper.getMainLooper())
                .idle()
            dismissed
        }
        assertEquals(UsageDiagnosticsDecisionFfi.GRANTED, state.usageDiagnosticsSettings?.decision)
        assertEquals(DiagnosticsExporterStatusFfi.READY, state.usageDiagnosticsStatus?.telemetry)
        assertEquals(DiagnosticsExporterStatusFfi.UNCONFIGURED, state.usageDiagnosticsStatus?.productAnalytics)
        assertFalse(state.auditLogSettings?.enabled ?: true)
    }

    /** Both directions of each slow save retain switch geometry, text wrapping, and the Done position. */
    @Test
    fun savingEitherChoiceKeepsTheSheetLayoutStable() {
        val writes = LinkedBlockingQueue<Pair<CountDownLatch, CountDownLatch>>()
        val state =
            privacyAppState(UsageDiagnosticsDecisionFfi.ACCEPTANCE_REQUIRED) {
                val (entered, release) = checkNotNull(writes.poll(5, TimeUnit.SECONDS))
                entered.countDown()
                check(release.await(30, TimeUnit.SECONDS))
            }
        runBlocking { state.refreshSecurityPrivacySettings() }
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) { UsageDiagnosticsPrompt(state, onDone = {}) }
        }
        val initialBounds = promptContentBounds()
        for ((index, enabled) in listOf(0 to true, 0 to false, 1 to true, 1 to false)) {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            writes.put(entered to release)
            try {
                val title = if (index == 0) "Share usage and diagnostics" else "Audit logs"
                composeRule.onNodeWithText(title).performClick()
                waitForMutation { entered.count == 0L }
                val toggle = composeRule.onAllNodes(isToggleable())[index]
                if (enabled) toggle.assertIsOn() else toggle.assertIsOff()
                toggle.assertIsNotEnabled()
                composeRule.onNodeWithText("Done").assertIsNotEnabled()
                assertEquals(initialBounds, promptContentBounds())
                if (index == 0 && enabled) {
                    composeRule.onRoot().captureRoboImage("src/test/snapshots/usage_diagnostics_prompt_saving.png")
                }
            } finally {
                release.countDown()
            }
            waitForMutation {
                if (index == 0) {
                    !state.diagnostics.busy && state.diagnostics.granted == enabled
                } else {
                    state.auditLogSettings?.enabled == enabled
                }
            }
            composeRule.onNodeWithText("Done").assertIsEnabled()
            assertEquals(initialBounds, promptContentBounds())
        }
    }

    /** Samples the static disclosure and completion action instead of relying on screenshot timing alone. */
    private fun promptContentBounds() =
        listOf("Help Improve White Noise", "Share usage and diagnostics", "Audit logs", "Done").map {
            composeRule.onNodeWithText(it).fetchSemanticsNode().boundsInRoot
        }

    /** Advances the main looper while an off-main native save or its UI completion is pending. */
    private fun waitForMutation(condition: () -> Boolean) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            org.robolectric.Shadows
                .shadowOf(android.os.Looper.getMainLooper())
                .idle()
            condition()
        }
    }

    /** Builds one deterministic native privacy snapshot and captures Diagnostics & Improvements. */
    private fun captureConsentState(
        decision: UsageDiagnosticsDecisionFfi,
        expectedChecked: Boolean,
    ) {
        val appState = privacyAppState(decision)
        runBlocking { appState.refreshSecurityPrivacySettings() }

        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    DiagnosticsImprovementsScreen(appState = appState, onBack = {})
                }
            }
        }
        composeRule.waitForIdle()

        val diagnosticsSwitch = composeRule.onAllNodes(isToggleable())[0]
        if (expectedChecked) diagnosticsSwitch.assertIsOn() else diagnosticsSwitch.assertIsOff()
        val suffix = if (expectedChecked) "granted" else "declined"
        composeRule.onRoot().captureRoboImage("src/test/snapshots/diagnostics_improvements_$suffix.png")
    }
}
