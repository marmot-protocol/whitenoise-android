package dev.ipf.whitenoise.android.ui.account

import android.content.Context
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.quickProfileCycling
import dev.ipf.whitenoise.android.state.updateQuickProfileCycling
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

/** Actual Appearance and Chats bar callbacks retain separate Settings, selector, direct-switch and cycle actions. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class QuickProfileCycleUiTest {
    @get:Rule val composeRule = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** The new whole-row Appearance control defaults off and writes only the app-wide preference. */
    @Test fun appearanceOptInDoesNotChangeTheActiveAccount() {
        val app = state(listOf(A, B))
        composeRule.setContent {
            WhiteNoiseTheme { AppearanceScreen(app, {}, {}, {}, {}) }
        }
        composeRule.onNodeWithText(context.getString(R.string.quick_account_switching)).performClick()
        composeRule.runOnIdle {
            assertTrue(app.quickProfileCycling)
            assertEquals(A.label, app.activeAccountRef)
        }
    }

    /** One signed-in account opens Settings; a retained sign-out never enables cycling. */
    @Test fun oneAccountAvatarOpensSettingsAndNeverCycles() {
        val app = state(listOf(A, B.copy(signedOut = true)))
        app.updateQuickProfileCycling(true)
        var settings = 0
        render(app, onSettings = { settings++ })
        composeRule.onNodeWithTag("chats.quickSwitch").assertDoesNotExist()
        composeRule.onNodeWithTag("chats.switchProfile").performClick()
        assertEquals(1, settings)
        composeRule.onNodeWithTag(ACCOUNT_SELECTOR_CONTENT_TAG).assertDoesNotExist()
    }

    /** Off leaves the cycle absent while the active avatar opens the existing native account selector. */
    @Test fun offWithSeveralAccountsRetainsTheNormalSelector() {
        val app = state(listOf(A, B))
        render(app, onSettings = { error("Multiple profiles open selector") })
        composeRule.onNodeWithTag("chats.quickSwitch").assertDoesNotExist()
        composeRule.onNodeWithTag("chats.switchProfile").performClick()
        composeRule.onNodeWithTag(ACCOUNT_SELECTOR_CONTENT_TAG).assertExists()
        composeRule.onNodeWithText(context.getString(R.string.add_profile)).assertExists()
    }

    /** Enabled cycle has its own next-destination label and callback, leaving direct unread-account actions intact. */
    @Test fun cycleAndDirectAccountActionsRemainDistinctAndNamed() {
        val app = state(listOf(A, B))
        app.updateQuickProfileCycling(true)
        app.updateAccountUnreadCount(B.label, 3uL)
        var cycles = 0
        val switches = mutableListOf<String>()
        render(app, onCycle = { cycles++ }, onSwitch = { switches += it })
        composeRule
            .onNodeWithTag("chats.quickSwitch")
            .assertContentDescriptionEquals(
                context.getString(R.string.quick_account_switch_to, app.accountDisplayNameCached(B.accountIdHex)),
            ).performClick()
        assertEquals(1, cycles)
        assertTrue(switches.isEmpty())
        composeRule.onNodeWithTag(OTHER_ACCOUNT_STACK_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag("chats.switchProfile").performClick()
        composeRule.onNodeWithTag("profile_switcher.profile.b").assertExists()
        composeRule.onNodeWithText("3").assertExists()
    }

    /** Native search mode suppresses all account chrome without replacing its search/voice controls. */
    @Test fun searchModeKeepsAccountActionsHidden() {
        val app = state(listOf(A, B))
        app.updateQuickProfileCycling(true)
        render(app, search = true)
        composeRule.onNodeWithTag("chats.quickSwitch").assertDoesNotExist()
        composeRule.onNodeWithTag("chats.switchProfile").assertDoesNotExist()
        composeRule.onNodeWithTag(OTHER_ACCOUNT_STACK_TAG).assertDoesNotExist()
    }

    private fun render(
        app: WhiteNoiseAppState,
        search: Boolean = false,
        onSettings: () -> Unit = {},
        onCycle: () -> Unit = {},
        onSwitch: (String) -> Unit = {},
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
                    onSwitch,
                    onCycleAccount = onCycle,
                )
            }
        }
    }

    private fun state(accounts: List<AccountSummaryFfi>): WhiteNoiseAppState {
        val preferences = context.getSharedPreferences("cycle-ui-test", Context.MODE_PRIVATE)
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
