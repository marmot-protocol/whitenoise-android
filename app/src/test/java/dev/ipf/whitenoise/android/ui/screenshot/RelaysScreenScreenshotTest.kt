package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.MissingRelayListKindFfi
import dev.ipf.whitenoise.android.ui.settings.RelaysContent
import dev.ipf.whitenoise.android.ui.settings.RelaysUiState
import dev.ipf.whitenoise.android.ui.settings.relayLists
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Relays with a missing inbox list: statuses, Refresh and Publish actions, three relays and Restore defaults. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h1100dp-mdpi")
class RelaysScreenScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** Light theme. */
    @Test
    fun lightTheme() = capture(darkTheme = false, amoled = false, name = "relay_settings_light.png")

    /** Dark theme. */
    @Test
    fun darkTheme() = capture(darkTheme = true, amoled = false, name = "relay_settings_dark.png")

    /** AMOLED: outlined groups on black. */
    @Test
    fun amoledTheme() = capture(darkTheme = true, amoled = true, name = "relay_settings_amoled.png")

    /** Renders the list content for one fixed projection and records the window. */
    private fun capture(
        darkTheme: Boolean,
        amoled: Boolean,
        name: String,
    ) {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = darkTheme, amoled = amoled) {
                RelaysContent(
                    state =
                        RelaysUiState(
                            lists =
                                relayLists(
                                    nip65 = listOf("wss://relay.example.com", "wss://relay.us.whitenoise.chat"),
                                    inbox = listOf("wss://relay.example.com", "wss://inbox.example.com"),
                                    missing = listOf(MissingRelayListKindFfi.INBOX),
                                    defaults = listOf("wss://relay.us.whitenoise.chat"),
                                ),
                        ),
                    onBack = {},
                    onOpenRelay = {},
                    onAdd = {},
                    onRefresh = {},
                    onPublishMissing = {},
                    onRestore = {},
                )
            }
        }
        composeRule.onRoot().captureRoboImage("src/test/snapshots/$name")
    }
}
