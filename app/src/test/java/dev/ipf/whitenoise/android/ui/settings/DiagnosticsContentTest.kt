package dev.ipf.whitenoise.android.ui.settings

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.diagnostics.PerformanceDiagnosticStatus
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercises real presentation callbacks; fixtures never subscribe, publish or send through native code. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class DiagnosticsContentTest {
    @get:Rule val composeRule = createComposeRule()
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private var backs = 0
    private var refreshes = 0
    private var sends = 0
    private var clears = 0
    private var performance: Boolean? = null
    private val entries = mutableStateOf(emptyList<DiagnosticLogEntry>())
    private val streaming = mutableStateOf(false)

    /** An idle empty stream is honestly labeled and cannot clear or send without an account. */
    @Test fun emptyIdleStreamDisablesUnavailableActions() {
        render(account = null)
        composeRule.onNodeWithTag("diagnostics.empty").assertIsDisplayed()
        composeRule.onNodeWithText(app.getString(R.string.idle)).assertIsDisplayed()
        actions()
        composeRule.onNodeWithTag("diagnostics.action.clear").assertIsNotEnabled()
        composeRule.onNodeWithTag("diagnostics.action.test").assertIsNotEnabled()
    }

    /** Caller updates replace the empty state, truthfully update status and expose clear exactly once. */
    @Test fun streamingUpdatesAndClearUseCurrentCallerState() {
        render()
        composeRule.runOnIdle {
            streaming.value = true
            entries.value = listOf(DiagnosticLogEntry("first", 0uL, "sanitized event"))
        }
        composeRule.onNodeWithTag("diagnostics.empty").assertDoesNotExist()
        composeRule.onNodeWithText(app.getString(R.string.live)).assertIsDisplayed()
        composeRule.onNodeWithText("sanitized event").assertIsDisplayed()
        actions()
        composeRule.onNodeWithTag("diagnostics.action.clear").performClick()
        composeRule.runOnIdle { assertEquals(1, clears) }
        composeRule.onNodeWithTag("diagnostics.empty").assertIsDisplayed()
        composeRule.onNodeWithTag("diagnostics.actions.menu").assertDoesNotExist()
    }

    /** Both send entry points preserve the production callback and dismiss only the actions popup. */
    @Test fun menuAndHealthSendToSelfInvokeTheSameCallback() {
        render()
        actions()
        composeRule.onNodeWithTag("diagnostics.action.test").performClick()
        health()
        composeRule.onNodeWithTag("diagnostics.health.test").performClick()
        composeRule.runOnIdle { assertEquals(2, sends) }
        composeRule.onNodeWithTag("diagnostics.health").assertExists()
    }

    /** The busy send state blocks both entry points without blocking refresh or close. */
    @Test fun sendingDisablesBothSendActions() {
        render(sending = true)
        actions()
        composeRule.onNodeWithTag("diagnostics.action.test").assertIsNotEnabled()
        composeRule.onNodeWithTag("diagnostics.action.health").performClick()
        composeRule.onNodeWithTag("diagnostics.health.test").assertIsNotEnabled()
        composeRule.onNodeWithTag("diagnostics.health.refresh").performClick()
        composeRule.runOnIdle { assertEquals(1, refreshes) }
        composeRule.onNodeWithContentDescription(app.getString(R.string.close)).performClick()
        composeRule.onNodeWithTag("diagnostics.health").assertDoesNotExist()
    }

    /** All six native health counters and all three runtime values survive the layout migration. */
    @Test fun healthRetainsNativeCountersAndRuntimeDetails() {
        render(relay = DiagnosticsRelayHealth(17u, 11u, 2u, 4u, 31u, 29u))
        health()
        listOf(
            R.string.total,
            R.string.connected,
            R.string.connecting,
            R.string.disconnected,
            R.string.attempts,
            R.string.successes,
            R.string.active_account,
            R.string.accounts,
            R.string.bootstrap_relays,
        ).forEach { label ->
            composeRule.onNodeWithText(app.getString(label)).performScrollTo().assertIsDisplayed()
        }
        listOf("17", "11", "2", "4", "31", "29", "fixture-account", "3", "5").forEach { value ->
            composeRule.onNodeWithText(value).performScrollTo().assertIsDisplayed()
        }
    }

    /** An unavailable snapshot still offers refresh, and the logging switch reaches its explicit opt-in. */
    @Test fun missingSnapshotOffersRefreshAndPerformanceOptIn() {
        render(performanceAvailable = true)
        health()
        composeRule.onNodeWithTag("diagnostics.health.refresh").performClick()
        composeRule.onNodeWithTag("diagnostics.performance").performScrollTo().performClick()
        composeRule.onNodeWithText(app.getString(R.string.no_relay_snapshot_yet)).performScrollTo().assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals(1, refreshes)
            assertEquals(true, performance)
        }
    }

    /** Release builds omit performance controls and closing Health preserves the screen's back navigation. */
    @Test fun unavailableLoggingIsHiddenAndCloseDoesNotNavigateBack() {
        render()
        health()
        composeRule.onNodeWithTag("diagnostics.performance").assertDoesNotExist()
        composeRule.onNodeWithContentDescription(app.getString(R.string.close)).performClick()
        composeRule.runOnIdle { assertEquals(0, backs) }
        composeRule.onNodeWithContentDescription(app.getString(R.string.back)).performClick()
        composeRule.runOnIdle { assertEquals(1, backs) }
    }

    /** Hundreds of events remain individually lazy and the last event is reachable. */
    @Test fun boundedEventLogRemainsScrollable() {
        entries.value = List(500) { DiagnosticLogEntry("event-$it", 0uL, "sanitized event $it") }
        render()
        composeRule.onNodeWithTag("diagnostics.events").performScrollToNode(hasText("sanitized event 499"))
        composeRule.onNodeWithText("sanitized event 499").assertIsDisplayed()
    }

    /** Opens the popup through its actual UI button. */
    private fun actions() {
        composeRule.onNodeWithTag("diagnostics.actions").performClick()
    }

    /** Opens Health through the shared dropdown callback. */
    private fun health() {
        actions()
        composeRule.onNodeWithTag("diagnostics.action.health").performClick()
    }

    /** Supplies caller-owned state and callback counters without constructing a native runtime. */
    private fun render(
        account: String? = "fixture-account",
        sending: Boolean = false,
        relay: DiagnosticsRelayHealth? = null,
        performanceAvailable: Boolean = false,
    ) {
        composeRule.setContent {
            WhiteNoiseTheme {
                DiagnosticsContent(
                    state =
                        diagnosticsState(
                            relay,
                            account,
                            3,
                            5,
                            entries.value.size,
                            streaming.value,
                            sending,
                            PerformanceDiagnosticStatus(performanceAvailable, false, 0L, 0, 0),
                        ),
                    entries = entries.value,
                    onBack = { backs++ },
                    onRefresh = { refreshes++ },
                    onSendToSelf = { sends++ },
                    onClear = {
                        clears++
                        entries.value = emptyList()
                    },
                    onPerformanceEnabledChange = { performance = it },
                )
            }
        }
    }
}
