package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.diagnostics.PerformanceDiagnosticStatus
import dev.ipf.whitenoise.android.ui.settings.DIAGNOSTICS_CONTENT_TAG
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

        composeRule
            .onNodeWithTag(DIAGNOSTICS_CONTENT_TAG)
            .captureRoboImage("src/test/snapshots/diagnostics_screen_performance_active_dark.png")
    }
}
