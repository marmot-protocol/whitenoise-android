package dev.ipf.whitenoise.android.ui.group

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import dev.ipf.whitenoise.android.state.GroupRosterLoadState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class GroupMemberAdministrationUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun addMemberActionOnlyOpensForAnAuthoritativeRoster() {
        var rosterState by mutableStateOf(GroupRosterLoadState.LOADING)
        var opens = 0
        composeRule.setContent {
            WhiteNoiseTheme {
                GroupDetailsAddMemberAction(
                    visible = true,
                    rosterState = rosterState,
                    mutationsBlocked = false,
                    onClick = { opens++ },
                )
            }
        }

        listOf(
            GroupRosterLoadState.LOADING,
            GroupRosterLoadState.FAILED,
            GroupRosterLoadState.INCONSISTENT,
        ).forEach { nonAuthoritativeState ->
            composeRule.runOnIdle { rosterState = nonAuthoritativeState }
            composeRule.onNode(hasClickAction()).assertIsNotEnabled()
            composeRule.runOnIdle { assertEquals(0, opens) }
        }

        composeRule.runOnIdle { rosterState = GroupRosterLoadState.READY }
        composeRule.onNode(hasClickAction()).assertIsEnabled().performClick()
        composeRule.runOnIdle { assertEquals(1, opens) }
    }

    /**
     * A self-admin group opened with a warm member snapshot must present the
     * action enabled on the first composed frame, while the authoritative
     * roster refresh is still LOADING, and must open on tap.
     */
    @Test
    fun aSeededSelfMemberEnablesAddMemberOnTheFirstFrameWhileTheRosterLoads() {
        var rosterState by mutableStateOf(GroupRosterLoadState.LOADING)
        var opens = 0
        composeRule.setContent {
            WhiteNoiseTheme {
                GroupDetailsAddMemberAction(
                    visible = true,
                    rosterState = rosterState,
                    mutationsBlocked = false,
                    onClick = { opens++ },
                    seededSelfMember = true,
                )
            }
        }

        composeRule.onNode(hasClickAction()).assertIsEnabled().performClick()
        composeRule.runOnIdle { assertEquals(1, opens) }

        // The seed is presentation only: a roster that fails or disagrees with
        // itself withdraws the action even though the seed still says member.
        listOf(GroupRosterLoadState.FAILED, GroupRosterLoadState.INCONSISTENT).forEach { state ->
            composeRule.runOnIdle { rosterState = state }
            composeRule.onNode(hasClickAction()).assertIsNotEnabled()
        }
        composeRule.runOnIdle { rosterState = GroupRosterLoadState.READY }
        composeRule.onNode(hasClickAction()).assertIsEnabled()
    }
}
