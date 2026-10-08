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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.audio.ConversationDictationDeliveryMode
import dev.ipf.whitenoise.android.audio.OFFLINE_SPEECH_TO_TEXT_PACKAGE
import dev.ipf.whitenoise.android.state.AppText
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.TransientNotice
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.ShellTransientNoticeLayout
import dev.ipf.whitenoise.android.ui.common.ConfirmDialog
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

    /** Available Settings update row uses the requested green emblem and real release-version wording. */
    @Test
    fun settingsScreenAvailableUpdateLight() {
        render(darkTheme = false, latestVersion = "2026.10.10")
        composeRule.onNodeWithText("Version 2026.10.10 is available on Zapstore.").assertIsDisplayed()
        capture("settings_screen_available_update_light")
    }

    /** Settings screen with global confirmation dark. */
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
            .performScrollToNode(hasText("Version 2026.10.3"))
        composeRule.onNodeWithText("Version 2026.10.3").assertIsDisplayed()
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
                    DictationSettingsScreen(
                        appState = appState,
                        onBack = {},
                        resolveProviderPackage = { _, _ -> null },
                    )
                }
            }
        }

        composeRule
            .onNodeWithText("The speech service installed on this device processes the audio", substring = true)
            .assertExists()
        composeRule.onRoot().captureRoboImage("src/test/snapshots/dictation_settings_default_light.png")
    }

    /** Installed Offline Speech to Text needs no recommendation or marketplace detour. */
    @Test
    fun dictationSettingsHidesRecommendationWhenOsttIsInstalled() {
        val appState = dictationAppState()
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    DictationSettingsScreen(
                        appState = appState,
                        onBack = {},
                        isOfflineSpeechToTextInstalled = { true },
                        resolveProviderPackage = { _, _ -> OFFLINE_SPEECH_TO_TEXT_PACKAGE },
                    )
                }
            }
        }

        composeRule.onNodeWithText("Get Offline Speech to Text").assertDoesNotExist()
        composeRule
            .onNodeWithText("Offline Speech to Text processes dictation on this device.", substring = true)
            .assertExists()
        composeRule.onRoot().captureRoboImage("src/test/snapshots/dictation_settings_ostt_installed_light.png")
    }

    /** First use of the selected offline engine explains local processing without a cloud warning. */
    @Test
    fun dictationOfflineDisclosureLight() {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                ConfirmDialog(
                    title = stringResource(R.string.dictation_disclosure_offline_title),
                    message = stringResource(R.string.dictation_disclosure_offline_message),
                    confirmLabel = stringResource(R.string.dictation_continue),
                    onConfirm = {},
                    onDismiss = {},
                )
            }
        }

        composeRule.onNodeWithText("Offline speech recognition").assertIsDisplayed()
        composeRule.onNodeWithText("may send the audio", substring = true).assertDoesNotExist()
        composeRule.onRoot().captureRoboImage("src/test/snapshots/dictation_offline_disclosure_light.png")
    }

    /** The external-media switch is accessible and does not clip in the large-font RTL layout. */
    @Test
    fun dictationPauseOtherAudioSwitchRtlLargeFont() {
        val appState = dictationAppState()
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, 2f),
                LocalLayoutDirection provides LayoutDirection.Rtl,
            ) {
                WhiteNoiseTheme(darkTheme = false) {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        DictationSettingsScreen(
                            appState = appState,
                            onBack = {},
                            isOfflineSpeechToTextInstalled = { true },
                            resolveProviderPackage = { _, _ -> null },
                        )
                    }
                }
            }
        }

        // Resolve the provider before scrolling: its explainer changes the height of the first item.
        composeRule
            .onNodeWithText("The speech service installed on this device processes the audio", substring = true)
            .assertExists()
        composeRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("dictation.pause_other_audio"))
        composeRule.onNodeWithTag("dictation.pause_other_audio").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("Pause other audio").assertExists()
        composeRule
            .onNodeWithText("Pause music and podcasts during dictation", substring = true)
            .assertExists()
            .performScrollTo()
        assertEquals(false, appState.conversationDictationPreferences.current().pauseOtherAudio)
        composeRule.onRoot().captureRoboImage("src/test/snapshots/dictation_settings_pause_other_audio_rtl_large.png")
    }

    /** The missing-app recommendation invokes the shared listing opener. */
    @Test
    fun dictationSettingsMissingOsttOpensListing() {
        var opened = 0
        val appState = dictationAppState()
        composeRule.setContent {
            WhiteNoiseTheme {
                DictationSettingsScreen(
                    appState = appState,
                    onBack = {},
                    isOfflineSpeechToTextInstalled = { false },
                    openOfflineSpeechToTextListing = {
                        opened++
                        true
                    },
                )
            }
        }

        composeRule.onNodeWithText("Get Offline Speech to Text").performClick()
        composeRule.runOnIdle { assertEquals(1, opened) }
    }

    /**
     * Verifies the delivery choice appears only once a pause can end a dictation, and retracts again.
     *
     * The row governs automatic completion alone. While Paste and Send are the only things that can
     * finish a dictation there is nothing for it to decide, so offering it there would imply a
     * stored default that could send a later dictation nobody asked it to.
     * Provider discovery is fixed here so its late explainer cannot move a row during touch injection.
     */
    @Test
    fun dictationDeliveryChoiceAppearsOnlyWhileSilenceCanFinishADictation() {
        val appState = dictationAppState()
        composeRule.setContent {
            WhiteNoiseTheme {
                DictationSettingsScreen(
                    appState = appState,
                    onBack = {},
                    resolveProviderPackage = { _, _ -> null },
                )
            }
        }

        composeRule.onNodeWithText("When finished").assertDoesNotExist()

        composeRule
            .onNodeWithText("Finish dictation")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        composeRule.onNodeWithText("After 5 seconds of silence").performClick()
        composeRule
            .onNodeWithText("When finished")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        composeRule.onNodeWithText("Send message").performClick()

        assertEquals(5_000L, appState.conversationDictationPreferences.current().finishAfterSilenceMillis)
        assertEquals(
            ConversationDictationDeliveryMode.SendOnFinish,
            appState.conversationDictationPreferences.current().silenceDeliveryMode,
        )

        composeRule
            .onNodeWithText("Finish dictation")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        composeRule.onNodeWithText("When I choose Paste or Send").performClick()

        composeRule.onNodeWithText("When finished").assertDoesNotExist()
    }

    /** The settings home with Alice signed in, a self-updating build and no-op callbacks. */
    @Composable
    private fun settingsHomeContent(
        profileCount: Int = 1,
        latestVersion: String? = null,
    ) {
        // The same predicate production uses, rather than "a latest version was supplied": a latest
        // version equal to the installed one is not an update, and deriving it any other way would let
        // the fixture draw the prominent placement above an "Up to date" subtitle.
        val appUpdateInfo =
            AppUpdateInfo(
                installedVersion = "2026.10.3",
                latestVersion = latestVersion,
                checkedAtMillis = null,
                dismissedVersion = null,
                releasesBehind = null,
            )
        SettingsHomeContent(
            state =
                settingsHomeState(
                    hasActiveAccount = true,
                    selfUpdateEnabled = true,
                    updateAvailable = appUpdateInfo.isUpdateAvailable,
                ),
            account =
                SettingsHomeAccount(
                    title = "Alice",
                    subtitle = SHORT_NPUB,
                    seed = "alice-account-id",
                    pictureUrl = null,
                ),
            profileCount = profileCount,
            appUpdateInfo = appUpdateInfo,
            versionName = "2026.10.3",
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
        latestVersion: String? = null,
    ) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
                LocalLayoutDirection provides layoutDirection,
            ) {
                WhiteNoiseTheme(darkTheme = darkTheme, amoled = amoled) {
                    settingsHomeContent(profileCount = profileCount, latestVersion = latestVersion)
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
