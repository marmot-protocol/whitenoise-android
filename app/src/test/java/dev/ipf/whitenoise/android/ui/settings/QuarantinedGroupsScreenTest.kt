package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.ipf.whitenoise.android.core.IdentityFormatter
import dev.ipf.whitenoise.android.state.QuarantineRecoveryOutcome
import dev.ipf.whitenoise.android.state.QuarantinedGroupReason
import dev.ipf.whitenoise.android.state.QuarantinedGroupRow
import dev.ipf.whitenoise.android.state.QuarantinedGroupsUiState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h800dp-mdpi")
class QuarantinedGroupsScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun unavailableDoesNotClaimAnEmptyInventory() {
        content(QuarantinedGroupsUiState(available = false))
        compose.onNodeWithTag("quarantine.refresh").assertIsNotEnabled()
        compose.onNodeWithTag("quarantine.empty").assertDoesNotExist()
    }

    @Test fun loadedEmptyIsVisibleAndRefreshCallsOnlyTheReadAction() {
        var refreshes = 0
        content(QuarantinedGroupsUiState(loaded = true), refresh = { refreshes++ })
        compose.onNodeWithTag("quarantine.empty").assertIsDisplayed()
        compose.onNodeWithTag("quarantine.refresh").performClick()
        assertEquals(1, refreshes)
    }

    @Test fun sanitizedErrorOffersRefreshWithoutAnEmptyClaim() {
        var refreshes = 0
        content(QuarantinedGroupsUiState(loadFailed = true), refresh = { refreshes++ })
        compose.onNodeWithTag("quarantine.error").assertIsDisplayed()
        compose.onNodeWithTag("quarantine.empty").assertDoesNotExist()
        compose.onNodeWithTag("quarantine.refresh").performClick()
        assertEquals(1, refreshes)
    }

    @Test fun recoveryShowsOnlyShortIdAndPassesTheNativeIdToTheAction() {
        val id = "0123456789abcdef".repeat(4)
        val actions = mutableListOf<String>()
        content(QuarantinedGroupsUiState(loaded = true, rows = listOf(QuarantinedGroupRow(id, QuarantinedGroupReason.Unknown))), recover = actions::add)
        compose.onNodeWithText("Group ${IdentityFormatter.short(id)}", substring = true).assertIsDisplayed()
        compose.onNodeWithText(id, substring = true).assertDoesNotExist()
        compose.onNodeWithTag("quarantine.recover").performClick()
        assertEquals(listOf(id), actions)
    }

    @Test fun activeRecoveryDisablesBothRefreshAndAnotherRetry() {
        val row = QuarantinedGroupRow("a".repeat(64), QuarantinedGroupReason.StoredState)
        content(QuarantinedGroupsUiState(loaded = true, rows = listOf(row), recoveringGroup = row.groupId))
        compose.onNodeWithTag("quarantine.refresh").assertIsNotEnabled()
        compose.onNodeWithTag("quarantine.recover").assertIsNotEnabled()
    }

    @Test fun recoveredOutcomeAndReloadFailureRemainVisibleTogether() {
        content(QuarantinedGroupsUiState(loaded = true, loadFailed = true, outcome = QuarantineRecoveryOutcome.Recovered))
        compose.onNodeWithTag("quarantine.outcome").assertIsDisplayed()
        compose.onNodeWithTag("quarantine.error").assertIsDisplayed()
        compose.onNodeWithTag("quarantine.empty").assertDoesNotExist()
    }

    private fun content(
        state: QuarantinedGroupsUiState,
        refresh: () -> Unit = {},
        recover: (String) -> Unit = {},
    ) {
        compose.setContent { WhiteNoiseTheme { QuarantinedGroupsContent(state, {}, refresh, recover) } }
    }
}
