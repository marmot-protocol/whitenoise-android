package dev.ipf.whitenoise.android.ui.account

import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class AccountSelectorContentTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun accountRowFiresSwitchActionForItsLabel() {
        var switchedTo: String? = null
        render(onSwitchAccount = { switchedTo = it })

        composeRule.onNodeWithText("Work").performClick()

        assertEquals("work", switchedTo)
    }

    @Test
    fun addAccountRowIsVisibleAndFiresAddAction() {
        var addCount = 0
        render(onAddAccount = { addCount++ })

        composeRule
            .onNodeWithText(string(R.string.add_account))
            .assertIsDisplayed()
            .performClick()

        assertEquals(1, addCount)
    }

    @Test
    fun unknownAccountDoesNotRenderOrAnnounceTheRetainedUnreadCount() {
        render(unreadCountForAccount = { 0uL })

        composeRule.onNodeWithText("4").assertDoesNotExist()
    }

    private fun render(
        onSwitchAccount: (String) -> Unit = {},
        onAddAccount: () -> Unit = {},
        unreadCountForAccount: (String) -> ULong = { label -> if (label == "work") 4uL else 0uL },
    ) {
        val state =
            accountSelectorState(
                accounts = listOf(account("personal"), account("work")),
                activeAccountRef = "personal",
                refreshing = false,
            )
        composeRule.setContent {
            WhiteNoiseTheme {
                Surface {
                    AccountSelectorContent(
                        state = state,
                        displayName = { accountId -> if (accountId == "hex-personal") "Personal" else "Work" },
                        shortNpub = { accountId -> "npub…${accountId.takeLast(4)}" },
                        avatarUrl = { null },
                        unreadCountForAccount = unreadCountForAccount,
                        onSwitchAccount = onSwitchAccount,
                        onAddAccount = onAddAccount,
                    )
                }
            }
        }
    }

    private fun string(res: Int): String = ApplicationProvider.getApplicationContext<android.content.Context>().getString(res)

    private fun account(label: String): AccountSummaryFfi =
        AccountSummaryFfi(
            label = label,
            accountIdHex = "hex-$label",
            localSigning = true,
            signedOut = false,
            running = true,
            externalSigning = false,
        )
}
