package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.state.AppText
import dev.ipf.whitenoise.android.state.TransientNotice
import dev.ipf.whitenoise.android.ui.ShellTransientNoticeLayout
import dev.ipf.whitenoise.android.ui.settings.SETTINGS_HOME_CONTENT_TAG
import dev.ipf.whitenoise.android.ui.settings.SettingsHomeAccount
import dev.ipf.whitenoise.android.ui.settings.SettingsHomeContent
import dev.ipf.whitenoise.android.ui.settings.settingsHomeState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import dev.ipf.whitenoise.android.updates.AppUpdateInfo
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class SettingsScreenScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

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
                                subtitle = "npub1alice0000",
                                seed = "alice-account-id",
                                pictureUrl = null,
                            ),
                        appUpdateInfo =
                            AppUpdateInfo(
                                installedVersion = "2026.8.25",
                                latestVersion = null,
                                checkedAtMillis = null,
                                dismissedVersion = null,
                                releasesBehind = null,
                            ),
                        versionName = "2026.8.25",
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

    @Composable
    private fun settingsHomeContent() {
        SettingsHomeContent(
            state = settingsHomeState(hasActiveAccount = true, selfUpdateEnabled = false),
            account =
                SettingsHomeAccount(
                    title = "Alice",
                    subtitle = "npub1alice0000",
                    seed = "alice-account-id",
                    pictureUrl = null,
                ),
            appUpdateInfo =
                AppUpdateInfo(
                    installedVersion = "2026.8.25",
                    latestVersion = null,
                    checkedAtMillis = null,
                    dismissedVersion = null,
                    releasesBehind = null,
                ),
            versionName = "2026.8.25",
            mdkShortSha = "abc1234",
            staging = false,
            onBackToChats = {},
            onOpenAccountSelector = {},
            onOpenQr = {},
            onOpenDetail = {},
            onAppUpdateAction = {},
        )
    }

    private companion object {
        const val SETTINGS_WITH_CONFIRMATION_TAG = "settings-with-global-confirmation"
    }
}
