package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTextInput
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.AccountRelayListsFfi
import dev.ipf.marmotkit.MissingRelayListKindFfi
import dev.ipf.whitenoise.android.ui.settings.AddRelaySheet
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

    /** Imported onion, clearnet ws:// and unusable relays each show their own callout and summary note. */
    @Test
    fun importedConcernsLight() {
        capture(darkTheme = false, amoled = false, name = "relay_settings_imported_concerns_light.png", IMPORTED)
    }

    /** The imported-concern callouts in the dark theme. */
    @Test
    fun importedConcernsDark() {
        capture(darkTheme = true, amoled = false, name = "relay_settings_imported_concerns_dark.png", IMPORTED)
    }

    /** Add Relay keeps its sheet visible and replaces the submit label while publication is in flight. */
    @Test
    fun addRelayLoading() {
        val busy = mutableStateOf(false)
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false, amoled = false) {
                AddRelaySheet(
                    existing = emptyList(),
                    busy = busy.value,
                    rejectedUrl = null,
                    onDismiss = {},
                    onAdd = { _, _ -> },
                )
            }
        }
        composeRule.onNodeWithTag("relay.add.url").performTextInput("wss://relay.example.com")
        composeRule.runOnIdle { busy.value = true }
        composeRule.onNodeWithText("Publishing…").assertIsDisplayed()
        composeRule.onRoot().captureRoboImage("src/test/snapshots/relay_add_loading_light.png")
    }

    /** Renders the list content for one projection and records the window. */
    private fun capture(
        darkTheme: Boolean,
        amoled: Boolean,
        name: String,
        lists: AccountRelayListsFfi = DEFAULT_LISTS,
    ) {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = darkTheme, amoled = amoled) {
                RelaysContent(
                    state = RelaysUiState(lists = lists),
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

    private companion object {
        val DEFAULT_LISTS =
            relayLists(
                nip65 = listOf("wss://relay.example.com", "wss://relay.us.whitenoise.chat"),
                inbox = listOf("wss://relay.example.com", "wss://inbox.example.com"),
                missing = listOf(MissingRelayListKindFfi.INBOX),
                defaults = listOf("wss://relay.us.whitenoise.chat"),
            )
        val IMPORTED =
            relayLists(
                nip65 =
                    listOf(
                        "wss://relay.example.com",
                        "ws://duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad.onion",
                        "ws://legacy.example.com",
                        "wss://relay.example.com:7777",
                    ),
                inbox = listOf("wss://relay.example.com"),
            )
    }
}
