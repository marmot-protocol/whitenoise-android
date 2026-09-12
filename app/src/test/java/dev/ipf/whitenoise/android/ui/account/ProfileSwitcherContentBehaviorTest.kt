package dev.ipf.whitenoise.android.ui.account

import android.content.Context
import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Per-row native count/manual-unread signals and direct actions replace the retired stacked-avatar presentation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class ProfileSwitcherContentBehaviorTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun inactiveCountsCapAndManualUnreadRemainIndependent() {
        render()
        composeRule.onNodeWithTag("profile_switcher.profile.a").assertIsSelected()
        composeRule.onNodeWithText("99+").assertExists()
        composeRule.onNodeWithText("10").assertDoesNotExist()
        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule
            .onNodeWithContentDescription(
                context.getString(R.string.chat_row_marked_unread),
                substring = true,
            ).assertExists()
    }

    @Test fun eachProfileAndPinnedDestinationInvokesItsOwnCallback() {
        val selected = mutableListOf<String>()
        var adds = 0
        var settings = 0
        render(onSelect = { selected += it }, onAdd = { adds++ }, onSettings = { settings++ })
        composeRule.onNodeWithTag("profile_switcher.profile.b").performClick()
        composeRule.onNodeWithTag("profile_switcher.profile.c").performClick()
        composeRule.onNodeWithTag("profile_switcher.add_profile").performClick()
        composeRule.onNodeWithTag("profile_switcher.settings").performClick()
        assertEquals(listOf("b", "c"), selected)
        assertEquals(1, adds)
        assertEquals(1, settings)
    }

    private fun render(
        onSelect: (String) -> Unit = {},
        onAdd: () -> Unit = {},
        onSettings: () -> Unit = {},
    ) {
        val accounts = listOf("a", "b", "c").map { AccountSummaryFfi(it, it.repeat(64), true, false, false, true) }
        composeRule.setContent {
            WhiteNoiseTheme {
                Surface {
                    ProfileSwitcherSheet(
                        accountSelectorState(accounts, "a", false),
                        displayName = { it.take(1) },
                        shortNpub = { "npub…${it.take(4)}" },
                        avatarUrl = { null },
                        unreadCountForAccount = {
                            when (it) {
                                "a" -> 10uL
                                "b" -> 100uL
                                else -> 0uL
                            }
                        },
                        hasUnreadForAccount = { it == "c" },
                        onSelectProfile = onSelect,
                        onAddProfile = onAdd,
                        onSettings = onSettings,
                    )
                }
            }
        }
    }
}
