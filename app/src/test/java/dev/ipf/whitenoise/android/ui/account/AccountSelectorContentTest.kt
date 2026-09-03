package dev.ipf.whitenoise.android.ui.account

import android.content.Context
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
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

    /** Verifies a slow refresh cannot replace an actionable in-session snapshot with a spinner. */
    @Test
    fun preloadedAccountsRemainVisibleAndActionableWhileRefreshIsInFlight() {
        var switchedTo: String? = null
        render(
            refreshing = true,
            onSwitchAccount = { switchedTo = it },
        )

        composeRule.onNodeWithText("Personal").assertIsDisplayed().assertIsEnabled()
        composeRule
            .onNodeWithText("Work")
            .assertIsDisplayed()
            .assertIsEnabled()
            .performClick()
        composeRule.onNodeWithContentDescription(string(R.string.active)).assertIsDisplayed()

        assertEquals("work", switchedTo)
    }

    /** Verifies a completed refresh replaces the snapshot and active marker without stale rows. */
    @Test
    fun refreshedSnapshotReconcilesAccountsAndActiveHighlightWithoutDuplicates() {
        var state by
            mutableStateOf(
                accountSelectorState(
                    accounts = listOf(account("personal"), account("work")),
                    activeAccountRef = "personal",
                    refreshing = true,
                ),
            )
        render(state = { state })

        composeRule.runOnUiThread {
            state =
                accountSelectorState(
                    accounts = listOf(account("work"), account("archive")),
                    activeAccountRef = "archive",
                    refreshing = false,
                )
        }

        composeRule.onNodeWithText("Personal").assertDoesNotExist()
        composeRule.onAllNodesWithText("Work").assertCountEquals(1)
        composeRule.onAllNodesWithText("Archive").assertCountEquals(1)
        composeRule.onNodeWithContentDescription(string(R.string.active)).assertIsDisplayed()
    }

    /** Verifies refresh failure completion leaves the existing snapshot and recovery action intact. */
    @Test
    fun refreshFailureLeavesSnapshotAndAddAccountActionVisible() {
        var state by
            mutableStateOf(
                accountSelectorState(
                    accounts = listOf(account("personal"), account("work")),
                    activeAccountRef = "personal",
                    refreshing = true,
                ),
            )
        render(state = { state })

        composeRule.runOnUiThread { state = state.copy(refreshing = false) }

        composeRule.onNodeWithText("Personal").assertIsDisplayed()
        composeRule.onNodeWithText("Work").assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.add_account)).assertIsDisplayed().assertIsEnabled()
    }

    /** Renders either the standard fixture or a caller-controlled account-selector state. */
    private fun render(
        refreshing: Boolean = false,
        onSwitchAccount: (String) -> Unit = {},
        onAddAccount: () -> Unit = {},
        unreadCountForAccount: (String) -> ULong = { label -> if (label == "work") 4uL else 0uL },
        state: (() -> AccountSelectorState)? = null,
    ) {
        val initialState =
            accountSelectorState(
                accounts = listOf(account("personal"), account("work")),
                activeAccountRef = "personal",
                refreshing = refreshing,
            )
        composeRule.setContent {
            WhiteNoiseTheme {
                Surface {
                    AccountSelectorContent(
                        state = state?.invoke() ?: initialState,
                        displayName = ::displayName,
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

    private fun string(res: Int): String = ApplicationProvider.getApplicationContext<Context>().getString(res)

    /** Maps the fixture account identifier to its visible title. */
    private fun displayName(id: String): String = id.removePrefix("hex-").replaceFirstChar { it.uppercase() }

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
