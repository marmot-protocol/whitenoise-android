package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.state.ChatDepartureStage
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseAlertDialog
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class ChatDepartureStatusScreenshotTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun lightPartialFailure() = capture("light")

    @Test fun darkPartialFailure() = capture("dark", dark = true)

    @Test fun amoledPartialFailure() = capture("amoled", dark = true, amoled = true)

    @Test fun largeRtlPartialFailure() = capture("large_rtl", scale = 2f, rtl = true)

    private fun capture(
        name: String,
        dark: Boolean = false,
        amoled: Boolean = false,
        scale: Float = 1f,
        rtl: Boolean = false,
    ) {
        val targets =
            listOf(
                ChatDepartureTarget("grant", "Reading group", false),
                ChatDepartureTarget("cleanup", "Friends", false),
            )
        val result =
            ChatDepartureBatchResult(
                mapOf(
                    "grant" to ChatDepartureOutcome.FAILED,
                    "cleanup" to ChatDepartureOutcome.FAILED,
                    "done" to ChatDepartureOutcome.COMPLETED,
                    "skip" to ChatDepartureOutcome.SKIPPED,
                ),
            )
        composeRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(1f, scale),
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = dark, amoled = amoled) {
                    WhiteNoiseAlertDialog(
                        {},
                        confirmButton = { TextButton({}) { Text("Retry") } },
                        dismissButton = { TextButton({}) { Text("Close") } },
                        title = { Text("Leave and delete") },
                        text = {
                            ChatDepartureResultContent(
                                result,
                                targets,
                                mapOf(
                                    "grant" to ChatDepartureStage.ADMIN_GRANTED,
                                    "cleanup" to ChatDepartureStage.CLEANUP_PENDING,
                                ),
                            )
                        },
                    )
                }
            }
        }
        composeRule.onRoot().captureRoboImage("src/test/snapshots/chat_departure_partial_$name.png")
    }
}
