package dev.ipf.whitenoise.android.ui.account

import android.content.Context
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.chats.ChatListTopBar
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual Chats avatar and sheet route wiring retains direct Settings and the existing Add Identity owner. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class ChatsProfileSwitcherFlowTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun severalProfilesOpenTheSheetAndSettingsClosesItWithoutChangingIdentity() {
        var settings = 0
        val app = render { settings++ }
        composeRule.onNodeWithTag(OTHER_ACCOUNT_STACK_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag("chats.quickSwitch").assertDoesNotExist()
        composeRule.onNodeWithTag("chats.switchProfile").performClick()
        composeRule.onNodeWithTag("profile_switcher.profile.b").assertExists()
        composeRule.onNodeWithTag("profile_switcher.settings").performClick()
        composeRule.onNodeWithTag(ACCOUNT_SELECTOR_CONTENT_TAG).assertDoesNotExist()
        assertEquals(1, settings)
        assertEquals("a", app.activeAccountRef)
    }

    @Test fun addProfileClosesSelectorAndOpensExistingNativeAddIdentitySheet() {
        val app = render {}
        composeRule.onNodeWithTag("chats.switchProfile").performClick()
        composeRule.onNodeWithTag("profile_switcher.add_profile").performClick()
        composeRule.onNodeWithTag(ACCOUNT_SELECTOR_CONTENT_TAG).assertDoesNotExist()
        // The existing owner must remain available; no create/import/Amber action is submitted here.
        composeRule.onNodeWithTag("onboarding.welcome.sign_up").assertExists()
        composeRule.onNodeWithTag("onboarding.welcome.sign_in").assertExists()
        assertEquals("a", app.activeAccountRef)
    }

    private fun render(onSettings: () -> Unit): WhiteNoiseAppState {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("profile-switcher-header-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val app =
            WhiteNoiseAppState(
                context,
                DraftStore.forContext(context),
                { null },
                listOf(
                    AccountSummaryFfi("a", "aa".repeat(32), true, false, false, true),
                    AccountSummaryFfi("b", "bb".repeat(32), true, false, false, true),
                ),
                "a",
                profileReader = { null },
                profileRefreshRequest = {},
                preferences = prefs,
            )
        composeRule.setContent {
            WhiteNoiseTheme {
                ChatListTopBar(
                    app,
                    false,
                    "",
                    remember { FocusRequester() },
                    {},
                    {},
                    {},
                    {},
                    onSettings,
                    {},
                    selfUpdateEnabled = false,
                )
            }
        }
        return app
    }
}
