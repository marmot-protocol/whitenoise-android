package dev.ipf.whitenoise.android.ui.screenshot

import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
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

    /** Captures the default production settings surface with the current release version. */
    @Test
    fun settingsScreenDefaultDark() {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SettingsHomeContent(
                        state = settingsHomeState(hasActiveAccount = true, selfUpdateEnabled = false),
                        account =
                            SettingsHomeAccount(
                                title = "Alice",
                                subtitle = FULL_NPUB,
                                seed = "alice-account-id",
                                pictureUrl = null,
                            ),
                        appUpdateInfo =
                            AppUpdateInfo(
                                installedVersion = "2026.9.5",
                                latestVersion = null,
                                checkedAtMillis = null,
                                dismissedVersion = null,
                                releasesBehind = null,
                            ),
                        versionName = "2026.9.5",
                        mdkShortSha = "abc1234",
                        staging = false,
                        onBackToChats = {},
                        onOpenAccountSelector = {},
                        onOpenQr = {},
                        onOpenDetail = {},
                        onAppUpdateAction = {},
                    )
                }
            }
        }

        composeRule
            .onNodeWithTag(SETTINGS_HOME_CONTENT_TAG)
            .captureRoboImage("src/test/snapshots/settings_screen_default_dark.png")
    }

    @Test
    fun settingsScreenWithGlobalConfirmationDark() {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true) {
                ShellTransientNoticeLayout(
                    notice = TransientNotice(id = 1L, title = AppText.Plain("Notifications enabled")),
                    modifier = Modifier.testTag(SETTINGS_WITH_CONFIRMATION_TAG),
                ) {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        settingsHomeContent()
                    }
                }
            }
        }

        composeRule
            .onNodeWithTag(SETTINGS_WITH_CONFIRMATION_TAG)
            .captureRoboImage("src/test/snapshots/settings_screen_global_confirmation_dark.png")
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

    /** Renders reusable settings content with the current production release metadata. */
    @Composable
    private fun settingsHomeContent() {
        SettingsHomeContent(
            state = settingsHomeState(hasActiveAccount = true, selfUpdateEnabled = false),
            account =
                SettingsHomeAccount(
                    title = "Alice",
                    subtitle = FULL_NPUB,
                    seed = "alice-account-id",
                    pictureUrl = null,
                ),
            appUpdateInfo =
                AppUpdateInfo(
                    installedVersion = "2026.9.5",
                    latestVersion = null,
                    checkedAtMillis = null,
                    dismissedVersion = null,
                    releasesBehind = null,
                ),
            versionName = "2026.9.5",
            mdkShortSha = "abc1234",
            staging = false,
            onBackToChats = {},
            onOpenAccountSelector = {},
            onOpenQr = {},
            onOpenDetail = {},
            onAppUpdateAction = {},
        )
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
        const val FULL_NPUB = "npub1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq"
    }
}
