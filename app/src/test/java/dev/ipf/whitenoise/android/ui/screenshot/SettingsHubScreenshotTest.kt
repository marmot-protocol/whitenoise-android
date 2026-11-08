@file:Suppress("MaxLineLength")

package dev.ipf.whitenoise.android.ui.screenshot

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.notifications.PushTokenKeyRule
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.settings.AccountScreen
import dev.ipf.whitenoise.android.ui.settings.ChatsScreen
import dev.ipf.whitenoise.android.ui.settings.HelpSupportScreen
import dev.ipf.whitenoise.android.ui.settings.NotificationDeliveryScreen
import dev.ipf.whitenoise.android.ui.settings.NotificationSoundsScreen
import dev.ipf.whitenoise.android.ui.settings.VoiceLanguageScreen
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The Settings hub pages (Account, Chats, Voice & language, Help & support, Sounds, Delivery) in every theme. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h1200dp-mdpi")
class SettingsHubScreenshotTest {
    @get:Rule val pushTokenKey = PushTokenKeyRule()

    @get:Rule val composeRule = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<Context>()

    @Test fun accountLight() = capture("settings_account_light", false) { AccountScreen(it, {}, {}) }

    @Test fun accountDark() = capture("settings_account_dark", true) { AccountScreen(it, {}, {}) }

    @Test fun accountAmoled() = capture("settings_account_amoled", true, amoled = true) { AccountScreen(it, {}, {}) }

    @Test
    fun accountRtlLargeFont() = capture("settings_account_rtl_large_font", false, rtl = true) { AccountScreen(it, {}, {}) }

    @Test fun chatsLight() = capture("settings_chats_light", false) { ChatsScreen(it, {}, {}) }

    @Test fun chatsDark() = capture("settings_chats_dark", true) { ChatsScreen(it, {}, {}) }

    @Test fun chatsAmoled() = capture("settings_chats_amoled", true, amoled = true) { ChatsScreen(it, {}, {}) }

    @Test
    fun chatsRtlLargeFont() = capture("settings_chats_rtl_large_font", false, rtl = true) { ChatsScreen(it, {}, {}) }

    @Test fun voiceLanguageLight() = capture("settings_voice_language_light", false) { VoiceLanguageScreen(it, {}, {}) }

    @Test fun voiceLanguageDark() = capture("settings_voice_language_dark", true) { VoiceLanguageScreen(it, {}, {}) }

    @Test
    fun voiceLanguageAmoled() = capture("settings_voice_language_amoled", true, amoled = true) { VoiceLanguageScreen(it, {}, {}) }

    @Test
    fun voiceLanguageRtlLargeFont() = capture("settings_voice_language_rtl_large_font", false, rtl = true) { VoiceLanguageScreen(it, {}, {}) }

    @Test fun helpSupportLight() = capture("settings_help_support_light", false) { HelpSupportScreen(it, {}, {}) }

    @Test fun helpSupportDark() = capture("settings_help_support_dark", true) { HelpSupportScreen(it, {}, {}) }

    @Test
    fun helpSupportAmoled() = capture("settings_help_support_amoled", true, amoled = true) { HelpSupportScreen(it, {}, {}) }

    @Test
    fun helpSupportRtlLargeFont() = capture("settings_help_support_rtl_large_font", false, rtl = true) { HelpSupportScreen(it, {}, {}) }

    @Test fun soundsLight() = capture("notification_sounds_light", false) { NotificationSoundsScreen(it, {}) }

    @Test fun soundsDark() = capture("notification_sounds_dark", true) { NotificationSoundsScreen(it, {}) }

    @Test
    fun soundsAmoled() = capture("notification_sounds_amoled", true, amoled = true) { NotificationSoundsScreen(it, {}) }

    @Test
    fun soundsRtlLargeFont() = capture("notification_sounds_rtl_large_font", false, rtl = true) { NotificationSoundsScreen(it, {}) }

    @Test fun deliveryLight() = capture("notification_delivery_light", false) { NotificationDeliveryScreen(it, {}) }

    @Test fun deliveryDark() = capture("notification_delivery_dark", true) { NotificationDeliveryScreen(it, {}) }

    @Test
    fun deliveryAmoled() = capture("notification_delivery_amoled", true, amoled = true) { NotificationDeliveryScreen(it, {}) }

    @Test
    fun deliveryRtlLargeFont() = capture("notification_delivery_rtl_large_font", false, rtl = true) { NotificationDeliveryScreen(it, {}) }

    /** Renders [content] for an account-less app state under the requested theme and records its baseline. */
    private fun capture(
        name: String,
        darkTheme: Boolean,
        amoled: Boolean = false,
        rtl: Boolean = false,
        content: @Composable (WhiteNoiseAppState) -> Unit,
    ) {
        val appState =
            WhiteNoiseAppState(
                context = app,
                draftStore = DraftStore.forContext(app),
                accountIdHexResolver = { null },
                accounts = emptyList(),
                activeAccountRef = "missing-account",
            )
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, if (rtl) 2f else 1f),
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = darkTheme, amoled = amoled) { content(appState) }
            }
        }
        composeRule.onRoot().captureRoboImage("src/test/snapshots/$name.png")
    }
}
