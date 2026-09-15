package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.MarmotEventFfi
import dev.ipf.whitenoise.android.core.DiagnosticFormatter
import dev.ipf.whitenoise.android.core.DiagnosticIdentityPresentation
import dev.ipf.whitenoise.android.core.IdentityFormatter
import dev.ipf.whitenoise.android.diagnostics.PerformanceDiagnosticStatus
import dev.ipf.whitenoise.android.ui.settings.DIAGNOSTICS_CONTENT_TAG
import dev.ipf.whitenoise.android.ui.settings.DiagnosticLogEntry
import dev.ipf.whitenoise.android.ui.settings.DiagnosticsContent
import dev.ipf.whitenoise.android.ui.settings.diagnosticsState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class DiagnosticsScreenScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** Pins the empty Events card and truthful Idle indicator in dark mode. */
    @Test
    fun diagnosticsScreenDefaultDark() {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    DiagnosticsContent(
                        state =
                            diagnosticsState(
                                relayHealth = null,
                                activeAccountRef = null,
                                accountCount = 0,
                                bootstrapRelayCount = 0,
                                eventCount = 0,
                                streaming = false,
                                sendingPing = false,
                                performanceStatus =
                                    PerformanceDiagnosticStatus(
                                        available = true,
                                        active = false,
                                        remainingMillis = 0L,
                                        emittedCount = 0,
                                        droppedCount = 0,
                                    ),
                            ),
                        entries = emptyList(),
                        onBack = {},
                        onRefresh = {},
                        onSendToSelf = {},
                        onClear = {},
                        onPerformanceEnabledChange = {},
                    )
                }
            }
        }

        composeRule
            .onNodeWithTag(DIAGNOSTICS_CONTENT_TAG)
            .captureRoboImage("src/test/snapshots/diagnostics_screen_default_dark.png")
    }

    /** Pins the active performance session status without introducing live timers. */
    @Test
    fun diagnosticsScreenPerformanceActiveDark() {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    DiagnosticsContent(
                        state =
                            diagnosticsState(
                                relayHealth = null,
                                activeAccountRef = null,
                                accountCount = 0,
                                bootstrapRelayCount = 0,
                                eventCount = 0,
                                streaming = false,
                                sendingPing = false,
                                performanceStatus =
                                    PerformanceDiagnosticStatus(
                                        available = true,
                                        active = true,
                                        remainingMillis = 29L * 60L * 1_000L,
                                        emittedCount = 2,
                                        droppedCount = 0,
                                    ),
                            ),
                        entries = emptyList(),
                        onBack = {},
                        onRefresh = {},
                        onSendToSelf = {},
                        onClear = {},
                        onPerformanceEnabledChange = {},
                    )
                }
            }
        }

        composeRule.onNodeWithTag("diagnostics.actions").performClick()
        composeRule.onNodeWithTag("diagnostics.action.health").performClick()
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("diagnostics.health").assertIsDisplayed()
        composeRule.onNodeWithTag("diagnostics.actions.menu").assertDoesNotExist()
        composeRule
            .onNodeWithTag("sheet.surface")
            .captureRoboImage("src/test/snapshots/diagnostics_screen_performance_active_dark.png")
    }

    /** Renders the SDK event through the formatter while suppressing wall-clock-dependent relative time. */
    @Test
    fun diagnosticsScreenSupersededGroupChangeDark() {
        val entry = supersededGroupChangeEntry()
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    DiagnosticsContent(
                        state =
                            diagnosticsState(
                                relayHealth = null,
                                activeAccountRef = null,
                                accountCount = 0,
                                bootstrapRelayCount = 0,
                                eventCount = 1,
                                streaming = true,
                                sendingPing = false,
                                performanceStatus =
                                    PerformanceDiagnosticStatus(
                                        available = false,
                                        active = false,
                                        remainingMillis = 0L,
                                        emittedCount = 0,
                                        droppedCount = 0,
                                    ),
                            ),
                        entries = listOf(entry),
                        onBack = {},
                        onRefresh = {},
                        onSendToSelf = {},
                        onClear = {},
                        onPerformanceEnabledChange = {},
                    )
                }
            }
        }

        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("[alice] group event"))
        composeRule.onNodeWithText("[alice] group event").assertIsDisplayed()
        listOf("private-account", "private-group", "private-commit", "private-payload").forEach {
            composeRule.onNodeWithText(it, substring = true).assertDoesNotExist()
        }
        composeRule
            .onNodeWithTag(DIAGNOSTICS_CONTENT_TAG)
            .captureRoboImage("src/test/snapshots/diagnostics_screen_superseded_group_change_dark.png")
    }

    /** Supplies a stable identity and hides relative time while exercising the real SDK formatter. */
    private fun supersededGroupChangeEntry(): DiagnosticLogEntry {
        val event =
            MarmotEventFfi.GroupChangeSuperseded(
                accountIdHex = "private-account",
                accountLabel = "alice",
                groupIdHex = "private-group",
                commitIdHex = "private-commit",
                kind = "group_profile",
                outcome = "conflict",
                reason = "private-payload",
            )
        return DiagnosticLogEntry(
            id = "superseded-group-change",
            timestamp = 0uL,
            text =
                DiagnosticFormatter.describe(
                    event,
                    DiagnosticIdentityPresentation(
                        accountLabel = { label, _ -> label },
                        publicIdentity = IdentityFormatter::short,
                    ),
                ),
        )
    }
}
