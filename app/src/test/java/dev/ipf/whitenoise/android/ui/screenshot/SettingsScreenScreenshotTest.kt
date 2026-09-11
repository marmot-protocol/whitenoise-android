package dev.ipf.whitenoise.android.ui.screenshot

import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.audio.ConversationDictationDeliveryMode
import dev.ipf.whitenoise.android.state.AppText
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.TransientNotice
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.ShellTransientNoticeLayout
import dev.ipf.whitenoise.android.ui.settings.DictationSettingsScreen
import dev.ipf.whitenoise.android.ui.settings.SETTINGS_HOME_CONTENT_TAG
import dev.ipf.whitenoise.android.ui.settings.SettingsHomeAccount
import dev.ipf.whitenoise.android.ui.settings.SettingsHomeContent
import dev.ipf.whitenoise.android.ui.settings.settingsHomeState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import dev.ipf.whitenoise.android.updates.AppUpdateInfo
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h780dp-mdpi")
class SettingsScreenScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** Captures the default settings home on the dark theme with one signed-in profile. */
    @Test
    fun settingsScreenDefaultDark() {
        render(darkTheme = true)
        capture("settings_screen_default_dark")
    }

    /** Captures the settings home on the light theme. */
    @Test
    fun settingsScreenDefaultLight() {
        render(darkTheme = false)
        capture("settings_screen_default_light")
    }

    /** Captures the settings home on AMOLED: black groups with white connected outlines. */
    @Test
    fun settingsScreenDefaultAmoled() {
        render(darkTheme = true, amoled = true)
        capture("settings_screen_default_amoled")
    }

    /** Captures the light settings home mirrored for RTL at a 200 % font scale. */
    @Test
    fun settingsScreenRtlLargeFont() {
        render(darkTheme = false, fontScale = 2f, layoutDirection = LayoutDirection.Rtl)
        capture("settings_screen_rtl_large_font")
    }

    /** Captures the header with several signed-in profiles, which offers switching instead of adding. */
    @Test
    fun settingsScreenSeveralProfilesLight() {
        render(darkTheme = false, profileCount = 2)
        capture("settings_screen_several_profiles_light")
    }

    @Test
    fun settingsScreenWithGlobalConfirmationDark() {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true) {
                ShellTransientNoticeLayout(
                    notice = TransientNotice(id = 1L, title = AppText.Plain("Notifications enabled")),
                    modifier = Modifier.testTag(SETTINGS_WITH_CONFIRMATION_TAG),
                ) {
                    settingsHomeContent()
                }
            }
        }

        composeRule
            .onNodeWithTag(SETTINGS_WITH_CONFIRMATION_TAG)
            .captureRoboImage("src/test/snapshots/settings_screen_global_confirmation_dark.png")
    }

    /** Captures the release version footer below the initial settings viewport. */
    @Test
    fun settingsScreenVersionFooterDark() {
        render(darkTheme = true)
        composeRule
            .onNode(hasScrollToNodeAction())
            .performScrollToNode(hasText("Version 2026.9.11"))
        composeRule.onNodeWithText("Version 2026.9.11").assertIsDisplayed()
        composeRule
            .onNodeWithTag(SETTINGS_HOME_CONTENT_TAG)
            .captureRoboImage("src/test/snapshots/settings_screen_version_footer_dark.png")
    }

    /** Captures the privacy-preserving dictation defaults on the light settings surface. */
    @Test
    fun dictationSettingsDefaultLight() {
        val appState = dictationAppState()
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    DictationSettingsScreen(appState = appState, onBack = {})
                }
            }
        }

        composeRule.onRoot().captureRoboImage("src/test/snapshots/dictation_settings_default_light.png")
    }

    /** Verifies settings sheets persist explicit silence completion and send-on-finish choices. */
    @Test
    fun dictationSettingsWriteExplicitFinishAndDeliverySelections() {
        val appState = dictationAppState()
        composeRule.setContent {
            WhiteNoiseTheme {
                DictationSettingsScreen(appState = appState, onBack = {})
            }
        }

        composeRule.onNodeWithText("Finish dictation").performClick()
        composeRule.onNodeWithText("After 5 seconds of silence").performClick()
        composeRule.onNodeWithText("When finished").performClick()
        composeRule.onNodeWithText("Send message").performClick()

        assertEquals(5_000L, appState.conversationDictationPreferences.current().finishAfterSilenceMillis)
        assertEquals(
            ConversationDictationDeliveryMode.SendOnFinish,
            appState.conversationDictationPreferences.current().deliveryMode,
        )
    }

    /** The settings home with Alice signed in, a self-updating build and no-op callbacks. */
    @Composable
    private fun settingsHomeContent(profileCount: Int = 1) {
        SettingsHomeContent(
            state = settingsHomeState(hasActiveAccount = true, selfUpdateEnabled = true),
            account =
                SettingsHomeAccount(
                    title = "Alice",
                    subtitle = SHORT_NPUB,
                    seed = "alice-account-id",
                    pictureUrl = null,
                ),
            profileCount = profileCount,
            appUpdateInfo =
                AppUpdateInfo(
                    installedVersion = "2026.9.11",
                    latestVersion = null,
                    checkedAtMillis = null,
                    dismissedVersion = null,
                    releasesBehind = null,
                ),
            versionName = "2026.9.11",
            onBack = {},
            onOpenShareConnect = {},
            onAddProfile = {},
            onSwitchProfile = {},
            onOpenDetail = {},
            onAppUpdateAction = {},
        )
    }

    /** Composes the settings home under the requested theme, density and layout direction. */
    private fun render(
        darkTheme: Boolean,
        amoled: Boolean = false,
        fontScale: Float = 1f,
        layoutDirection: LayoutDirection = LayoutDirection.Ltr,
        profileCount: Int = 1,
    ) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
                LocalLayoutDirection provides layoutDirection,
            ) {
                WhiteNoiseTheme(darkTheme = darkTheme, amoled = amoled) {
                    settingsHomeContent(profileCount = profileCount)
                }
            }
        }
    }

    /** Captures the whole home so the prominent title, groups and footer spacing are pinned. */
    private fun capture(name: String) {
        composeRule.onNodeWithTag(SETTINGS_HOME_CONTENT_TAG).captureRoboImage("src/test/snapshots/$name.png")
    }

    /** Creates an isolated app state whose dictation preferences can be mutated by Compose. */
    private fun dictationAppState(): WhiteNoiseAppState {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context
            .getSharedPreferences("whitenoise.composer_dictation", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        return WhiteNoiseAppState(
            context = context,
            draftStore = DraftStore.forContext(context),
            accountIdHexResolver = { null },
            accounts = emptyList(),
            activeAccountRef = "missing-account",
        )
    }

    private companion object {
        const val SETTINGS_WITH_CONFIRMATION_TAG = "settings-with-global-confirmation"
        const val SHORT_NPUB = "npub1alice…9x2k"
    }
}
