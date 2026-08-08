package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.state.RelayListKind
import dev.ipf.whitenoise.android.ui.settings.RelayListSettingsContent
import dev.ipf.whitenoise.android.ui.settings.relayLists
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private const val RELAY_SETTINGS_FIXTURE_TAG = "relay-settings-fixture"

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class RelayListSettingsScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun lightTheme() = capture(darkTheme = false, amoled = false, name = "relay_settings_light.png")

    @Test
    fun darkTheme() = capture(darkTheme = true, amoled = false, name = "relay_settings_dark.png")

    @Test
    fun amoledTheme() = capture(darkTheme = true, amoled = true, name = "relay_settings_amoled.png")

    private fun capture(
        darkTheme: Boolean,
        amoled: Boolean,
        name: String,
    ) {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = darkTheme, amoled = amoled) {
                Surface(
                    modifier = Modifier.width(360.dp),
                    color = if (amoled) Color.Black else MaterialTheme.colorScheme.surface,
                ) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                            .testTag(RELAY_SETTINGS_FIXTURE_TAG),
                    ) {
                        RelayListSettingsContent(
                            lists =
                                relayLists(
                                    nip65 = listOf("wss://relay.example.com"),
                                    inbox = listOf("wss://inbox.example.com"),
                                ),
                            selectedKind = RelayListKind.Nip65,
                            onSelectKind = {},
                            pendingUrl = "",
                            onPendingUrlChange = {},
                            saving = false,
                            canEdit = true,
                            onUpdateRelays = { _, _, _ -> },
                        )
                    }
                }
            }
        }
        composeRule
            .onNodeWithTag(RELAY_SETTINGS_FIXTURE_TAG)
            .captureRoboImage("src/test/snapshots/$name")
    }
}
