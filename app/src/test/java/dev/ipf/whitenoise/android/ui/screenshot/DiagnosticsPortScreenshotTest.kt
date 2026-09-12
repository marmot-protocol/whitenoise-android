package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.diagnostics.PerformanceDiagnosticStatus
import dev.ipf.whitenoise.android.ui.settings.DIAGNOSTICS_CONTENT_TAG
import dev.ipf.whitenoise.android.ui.settings.DiagnosticLogEntry
import dev.ipf.whitenoise.android.ui.settings.DiagnosticsContent
import dev.ipf.whitenoise.android.ui.settings.DiagnosticsRelayHealth
import dev.ipf.whitenoise.android.ui.settings.diagnosticsState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Presentation coverage for the prototype Events card and real-data Health sheet. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h780dp-mdpi")
class DiagnosticsPortScreenshotTest {
    @get:Rule val composeRule = createComposeRule()

    /** Light uses the lowest card surface on the low canvas. */
    @Test fun eventsLight() = capture("diagnostics_events_light")

    /** AMOLED keeps the card boundary visible on black. */
    @Test fun eventsAmoled() = capture("diagnostics_events_amoled", dark = true, amoled = true)

    /** The same AMOLED outline remains visible at three physical pixels per dp. */
    @Test
    @Config(qualifiers = "en-rUS-w360dp-h780dp-xxhdpi")
    fun eventsAmoledXxhdpi() = capture("diagnostics_events_amoled_xxhdpi", dark = true, amoled = true)

    /** Long sanitized event text wraps in RTL at two-times font scale. */
    @Test
    @Config(qualifiers = "ar-rEG-ldrtl-w360dp-h780dp-mdpi")
    fun eventsRtlLargeText() = capture("diagnostics_events_rtl_large", largeRtl = true)

    /** The empty card remains centered in light mode. */
    @Test fun eventsEmptyLight() = capture("diagnostics_events_empty_light", empty = true)

    /** Health is reachable from the actual menu and displays native counters. */
    @Test fun healthLight() = capture("diagnostics_health_light", health = true)

    /** The shared grouped rows retain their outlines inside the AMOLED sheet. */
    @Test fun healthAmoled() = capture("diagnostics_health_amoled", dark = true, amoled = true, health = true)

    /** An expanded health sheet stays scrollable at large font scale in RTL. */
    @Test
    @Config(qualifiers = "ar-rEG-ldrtl-w360dp-h780dp-mdpi")
    fun healthRtlLargeText() = capture("diagnostics_health_rtl_large", health = true, largeRtl = true)

    /** Uses stable, synthetic presentation fixtures and no wall-clock-dependent event time. */
    private fun capture(
        name: String,
        dark: Boolean = false,
        amoled: Boolean = false,
        health: Boolean = false,
        empty: Boolean = false,
        largeRtl: Boolean = false,
    ) {
        composeRule.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides if (largeRtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = dark, amoled = amoled, fontScale = if (largeRtl) 2f else 1f) {
                    val entries =
                        if (empty) {
                            emptyList()
                        } else {
                            listOf(
                                DiagnosticLogEntry("a", 0uL, "[alice] group event"),
                                DiagnosticLogEntry("b", 0uL, "[alice] message event"),
                                DiagnosticLogEntry(
                                    "c",
                                    0uL,
                                    "[alice] group event — a longer sanitized presentation fixture " +
                                        "to exercise wrapping",
                                ),
                            )
                        }
                    DiagnosticsContent(
                        state =
                            diagnosticsState(
                                DiagnosticsRelayHealth(7u, 4u, 1u, 2u, 17u, 15u),
                                "alice",
                                2,
                                3,
                                entries.size,
                                !empty,
                                false,
                                PerformanceDiagnosticStatus(false, false, 0L, 0, 0),
                            ),
                        entries = entries,
                        onBack = {},
                        onRefresh = {},
                        onSendToSelf = {},
                        onClear = {},
                        onPerformanceEnabledChange = {},
                    )
                }
            }
        }
        if (health) {
            composeRule.onNodeWithTag("diagnostics.actions").performClick()
            composeRule.onNodeWithTag("diagnostics.action.health").performClick()
            composeRule.mainClock.advanceTimeBy(1_000)
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("diagnostics.health").assertIsDisplayed()
            composeRule.onNodeWithTag("diagnostics.actions.menu").assertDoesNotExist()
        }
        composeRule
            .onNodeWithTag(if (health) "sheet.surface" else DIAGNOSTICS_CONTENT_TAG)
            .captureRoboImage("src/test/snapshots/$name.png")
    }
}
