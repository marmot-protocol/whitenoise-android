package dev.ipf.whitenoise.android.ui.settings

import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AuditLogSettingsFfi
import dev.ipf.marmotkit.DiagnosticsExporterStatusFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.RelayTelemetrySettingsFfi
import dev.ipf.marmotkit.UsageDiagnosticsDecisionFfi
import dev.ipf.marmotkit.UsageDiagnosticsSettingsFfi
import dev.ipf.marmotkit.UsageDiagnosticsStatusFfi
import dev.ipf.whitenoise.android.state.AppMarmotRuntime
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class DevicePrivacyScreenScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

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

    /** Builds one deterministic native privacy snapshot and captures the full settings surface. */
    private fun captureConsentState(
        decision: UsageDiagnosticsDecisionFfi,
        expectedChecked: Boolean,
    ) {
        val appState = privacyAppState(decision)
        runBlocking { appState.refreshSecurityPrivacySettings() }

        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    DevicePrivacyScreen(appState = appState, onBack = {})
                }
            }
        }
        composeRule.waitForIdle()

        val diagnosticsSwitch = composeRule.onAllNodes(isToggleable())[3]
        if (expectedChecked) diagnosticsSwitch.assertIsOn() else diagnosticsSwitch.assertIsOff()
        val suffix = if (expectedChecked) "granted" else "declined"
        composeRule.onRoot().captureRoboImage("src/test/snapshots/device_privacy_diagnostics_$suffix.png")
    }

    /** Creates a state whose only native reads are fixed device-privacy values. */
    private fun privacyAppState(decision: UsageDiagnosticsDecisionFfi): WhiteNoiseAppState {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences =
            context.getSharedPreferences(
                "device-privacy-screenshot-${decision.name}",
                Context.MODE_PRIVATE,
            )
        preferences.edit().clear().commit()
        val marmot = privacyMarmot(decision)
        return WhiteNoiseAppState(
            context = context,
            draftStore = DraftStore(EmptyDraftPersistence),
            accountIdHexResolver = { null },
            accounts = listOf(AccountSummaryFfi("account", "aa".repeat(32), true, false, false, true)),
            activeAccountRef = "account",
            marmotRuntimeFactory = { AppMarmotRuntime(rootPath = "test", marmot = marmot) },
            preferences = preferences,
        ).also { state ->
            WhiteNoiseAppState::class.java
                .getDeclaredField("marmotRuntime")
                .apply { isAccessible = true }
                .set(state, AppMarmotRuntime(rootPath = "test", marmot = marmot))
        }
    }

    /** Implements device-privacy reads and explicit consent writes using disposable state. */
    private fun privacyMarmot(initialDecision: UsageDiagnosticsDecisionFfi): MarmotInterface {
        var decision = initialDecision
        return Proxy.newProxyInstance(
            MarmotInterface::class.java.classLoader,
            arrayOf(MarmotInterface::class.java),
        ) { proxy, method, arguments ->
            when (method.name) {
                "setUsageDiagnosticsConsent" -> {
                    decision =
                        if (arguments?.firstOrNull() == true) {
                            UsageDiagnosticsDecisionFfi.GRANTED
                        } else {
                            UsageDiagnosticsDecisionFfi.DECLINED
                        }
                    UsageDiagnosticsSettingsFfi(decision, "test-policy", "test-registry", 0L, false)
                }
                "usageDiagnosticsSettings" ->
                    UsageDiagnosticsSettingsFfi(
                        decision = decision,
                        policyRevision = "test-policy",
                        registryRevision = "test-registry",
                        updatedAtMs = 0L,
                        previouslyEnabled = decision == UsageDiagnosticsDecisionFfi.GRANTED,
                    )
                "usageDiagnosticsStatus" ->
                    UsageDiagnosticsStatusFfi(
                        consent = decision,
                        telemetry =
                            if (decision == UsageDiagnosticsDecisionFfi.GRANTED) {
                                DiagnosticsExporterStatusFfi.READY
                            } else {
                                DiagnosticsExporterStatusFfi.DISABLED
                            },
                        productAnalytics = DiagnosticsExporterStatusFfi.UNCONFIGURED,
                        queuedEvents = 0uL,
                        droppedEvents = 0uL,
                        acceptedBatches = 0uL,
                        failedBatches = 0uL,
                    )
                "relayTelemetrySettings" ->
                    RelayTelemetrySettingsFfi(
                        exportEnabled = false,
                        exportIntervalSeconds = 60uL,
                    )
                "auditLogSettings" -> AuditLogSettingsFfi(enabled = false)
                "toString" -> "DevicePrivacyScreenshotMarmotFake"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === arguments?.firstOrNull()
                else -> error("Unexpected Marmot call: ${method.name}")
            }
        } as MarmotInterface
    }

    private object EmptyDraftPersistence : DraftPersistence {
        override fun read(): Map<String, String> = emptyMap()

        override fun write(
            key: String,
            value: String?,
        ) = Unit
    }
}
