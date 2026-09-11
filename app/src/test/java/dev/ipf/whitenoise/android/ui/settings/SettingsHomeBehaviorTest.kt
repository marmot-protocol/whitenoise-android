package dev.ipf.whitenoise.android.ui.settings

import android.content.Context
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.ui.navigation.SettingsDetail
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import dev.ipf.whitenoise.android.updates.AppUpdateInfo
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Behaviour contract of the Settings home: every row, header action and sheet trigger a restyle must keep. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h780dp-mdpi")
class SettingsHomeBehaviorTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val opened = mutableListOf<SettingsDetail>()
    private var shareConnectCount = 0
    private var addProfileCount = 0
    private var switchProfileCount = 0
    private var supportChatCount = 0
    private var signOutCount = 0
    private var appUpdateCount = 0
    private var backCount = 0

    /** Every hub and support row with a destination opens exactly that detail, in the prototype's order. */
    @Test
    fun rowsOpenTheirDetailsInPrototypeOrder() {
        mount(profileCount = 1)
        val expected = SettingsHomeRow.entries.mapNotNull { it.detail }
        SettingsHomeRow.entries.filter { it.detail != null }.forEach { row ->
            scrollToAndClick(context.getString(row.titleRes))
        }
        composeRule.runOnIdle { assertEquals(expected, opened) }
    }

    /** Chat with support runs the support-chat routing instead of opening a detail. */
    @Test
    fun chatWithSupportRunsTheSupportAction() {
        mount(profileCount = 1)
        scrollToAndClick("Chat with support")
        composeRule.runOnIdle {
            assertEquals(1, supportChatCount)
            assertEquals(emptyList<SettingsDetail>(), opened)
        }
    }

    /** The profile row opens Share & Connect; a single signed-in profile offers Add Profile, not switching. */
    @Test
    fun profileRowOpensShareConnectAndOneProfileOffersAddProfile() {
        mount(profileCount = 1)
        composeRule.onNodeWithTag("settings.active_profile").assertHasClickAction().performClick()
        composeRule.onNodeWithTag("settings.switch_profile").assertDoesNotExist()
        composeRule.onNodeWithTag("settings.add_profile").performClick()
        composeRule.runOnIdle {
            assertEquals(1, shareConnectCount)
            assertEquals(1, addProfileCount)
            assertEquals(0, switchProfileCount)
        }
    }

    /** With more than one profile the header offers switching, which opens the production selector. */
    @Test
    fun severalProfilesOfferSwitchProfileInsteadOfAddProfile() {
        mount(profileCount = 2)
        composeRule.onNodeWithTag("settings.add_profile").assertDoesNotExist()
        composeRule.onNodeWithTag("settings.switch_profile").performClick()
        composeRule.runOnIdle {
            assertEquals(1, switchProfileCount)
            assertEquals(0, addProfileCount)
        }
    }

    /** Sign out, app updates and back each invoke their caller once per tap. */
    @Test
    fun signOutAppUpdatesAndBackFireOncePerTap() {
        mount(profileCount = 1)
        scrollToAndClick("Sign Out")
        scrollToAndClick("App updates")
        composeRule.onNodeWithContentDescription("Back").performClick()
        composeRule.runOnIdle {
            assertEquals(1, signOutCount)
            assertEquals(1, appUpdateCount)
            assertEquals(1, backCount)
        }
    }

    /** Without an active account the home shows neither the profile header nor sign out. */
    @Test
    fun withoutAnAccountTheHomeHasNoProfileHeaderOrSignOut() {
        mount(profileCount = 0, hasActiveAccount = false)
        composeRule.onNodeWithTag("settings.active_profile").assertDoesNotExist()
        composeRule.onNodeWithText("Sign Out").assertDoesNotExist()
        composeRule.onNodeWithText("Profile").assertExists()
    }

    private fun mount(
        profileCount: Int,
        hasActiveAccount: Boolean = true,
    ) {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                SettingsHomeContent(
                    state = settingsHomeState(hasActiveAccount = hasActiveAccount, selfUpdateEnabled = true),
                    account =
                        if (hasActiveAccount) {
                            SettingsHomeAccount("Alice", "npub1alice…9x2k", "alice-account-id", pictureUrl = null)
                        } else {
                            null
                        },
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
                    onBack = { backCount++ },
                    onOpenShareConnect = { shareConnectCount++ },
                    onAddProfile = { addProfileCount++ },
                    onSwitchProfile = { switchProfileCount++ },
                    onOpenDetail = { opened += it },
                    onAppUpdateAction = { appUpdateCount++ },
                    onChatWithSupport = { supportChatCount++ },
                    onSignOut = { signOutCount++ },
                )
            }
        }
    }

    private fun scrollToAndClick(title: String) {
        composeRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(title))
        composeRule.onNodeWithText(title).performClick()
    }
}
