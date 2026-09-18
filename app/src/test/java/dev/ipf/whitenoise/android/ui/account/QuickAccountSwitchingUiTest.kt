package dev.ipf.whitenoise.android.ui.account

import android.content.Context
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.quickAccountSwitching
import dev.ipf.whitenoise.android.state.updateQuickAccountSwitching
import dev.ipf.whitenoise.android.ui.chats.CHAT_LIST_OTHER_ACCOUNT_AVATARS_TAG
import dev.ipf.whitenoise.android.ui.chats.ChatListTopBar
import dev.ipf.whitenoise.android.ui.settings.AppearanceScreen
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Quick account switching shows the other accounts themselves beside the active avatar.
 *
 * Appearance, the avatar and the stacked accounts keep separate Settings, selector and direct-switch actions.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class QuickAccountSwitchingUiTest {
    @get:Rule val composeRule = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** The whole-row Appearance control defaults off and writes only the app-wide preference. */
    @Test fun appearanceOptInDoesNotChangeTheActiveAccount() {
        val app = state(listOf(A, B))
        composeRule.setContent {
            WhiteNoiseTheme { AppearanceScreen(app, {}, {}, {}, {}) }
        }
        composeRule.onNodeWithText(context.getString(R.string.quick_account_switching)).performClick()
        composeRule.runOnIdle {
            assertTrue(app.quickAccountSwitching)
            assertEquals(A.label, app.activeAccountRef)
        }
    }

    /** One signed-in account opens Settings; a retained sign-out never earns an avatar in the row. */
    @Test fun oneAccountAvatarOpensSettingsAndShowsNoOtherAccounts() {
        val app = state(listOf(A, B.copy(signedOut = true)))
        app.updateQuickAccountSwitching(true)
        var settings = 0
        render(app, onSettings = { settings++ })
        composeRule.onNodeWithTag(OTHER_ACCOUNT_STACK_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag("chats.switchProfile").performClick()
        assertEquals(1, settings)
        composeRule.onNodeWithTag(ACCOUNT_SELECTOR_CONTENT_TAG).assertDoesNotExist()
    }

    /** Off leaves the row absent while the active avatar still opens the native account selector. */
    @Test fun offWithSeveralAccountsRetainsTheNormalSelector() {
        val app = state(listOf(A, B))
        render(app, onSettings = { error("Multiple profiles open selector") })
        composeRule.onNodeWithTag(OTHER_ACCOUNT_STACK_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag("chats.switchProfile").performClick()
        composeRule.onNodeWithTag(ACCOUNT_SELECTOR_CONTENT_TAG).assertExists()
        composeRule.onNodeWithText(context.getString(R.string.add_profile)).assertExists()
    }

    /** On, the other account is its own avatar: named, carrying its unread, and switching straight to it. */
    @Test fun otherAccountAvatarNamesItsAccountAndSwitchesDirectly() {
        val app = state(listOf(A, B))
        app.updateQuickAccountSwitching(true)
        app.updateAccountUnreadCount(B.label, 3uL)
        val switches = mutableListOf<String>()
        render(app, onSwitchTo = { switches += it })
        composeRule
            .onNodeWithTag(OTHER_ACCOUNT_STACK_TAG)
            .assertContentDescriptionContains(
                "${context.getString(R.string.switch_account)}: ${app.accountDisplayNameCached(B.accountIdHex)}, " +
                    context.getString(R.string.account_unread_indicator),
            )
        composeRule.onNodeWithTag(otherAccountAvatarTag(B.label), useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag(otherAccountUnreadDotTag(B.label), useUnmergedTree = true).assertExists()

        composeRule.onNodeWithTag(CHAT_LIST_OTHER_ACCOUNT_AVATARS_TAG).performClick()

        assertEquals(listOf(B.label), switches)
    }

    /** The active avatar keeps its own selector destination while the row switches directly. */
    @Test fun activeAvatarStillOpensTheSelectorAlongsideTheRow() {
        val app = state(listOf(A, B))
        app.updateQuickAccountSwitching(true)
        val switches = mutableListOf<String>()
        render(app, onSwitchTo = { switches += it })

        composeRule.onNodeWithTag("chats.switchProfile").performClick()

        composeRule.onNodeWithTag("profile_switcher.profile.b").assertExists()
        assertTrue(switches.isEmpty())
    }

    /** Native search mode suppresses all account chrome without replacing its search/voice controls. */
    @Test fun searchModeKeepsAccountActionsHidden() {
        val app = state(listOf(A, B))
        app.updateQuickAccountSwitching(true)
        render(app, search = true)
        composeRule.onNodeWithTag("chats.switchProfile").assertDoesNotExist()
        composeRule.onNodeWithTag(OTHER_ACCOUNT_STACK_TAG).assertDoesNotExist()
    }

    /** Composes the surface under test with the given fixture. */
    private fun render(
        app: WhiteNoiseAppState,
        search: Boolean = false,
        onSettings: () -> Unit = {},
        onSwitchTo: (String) -> Unit = {},
    ) {
        composeRule.setContent {
            WhiteNoiseTheme {
                ChatListTopBar(
                    app,
                    search,
                    "",
                    remember { FocusRequester() },
                    {},
                    {},
                    {},
                    {},
                    onSettings,
                    {},
                    onSwitchToAccount = onSwitchTo,
                )
            }
        }
    }

    /** Builds the state fixture for the test. */
    private fun state(accounts: List<AccountSummaryFfi>): WhiteNoiseAppState {
        val preferences = context.getSharedPreferences("quick-switch-ui-test", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        return WhiteNoiseAppState(
            context,
            DraftStore.forContext(context),
            { null },
            accounts,
            A.label,
            profileReader = { null },
            profileRefreshRequest = {},
            preferences = preferences,
        )
    }

    private companion object {
        val A = AccountSummaryFfi("a", "aa".repeat(32), true, false, false, true)
        val B = AccountSummaryFfi("b", "bb".repeat(32), true, false, false, true)
    }
}
