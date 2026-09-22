package dev.ipf.whitenoise.android.ui.group

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.ipf.whitenoise.android.core.RecipientSearch
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class LargeGroupInviteWarningUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun warningTracksSelectionAndAuthoritativeRosterChanges() {
        var rosterReady by mutableStateOf(false)
        var existing by mutableStateOf(49)
        var selected by mutableStateOf(emptyList<String>())
        composeRule.setContent {
            WhiteNoiseTheme {
                val projection =
                    largeGroupInviteProjection(
                        rosterReady = rosterReady,
                        authoritativeMemberIds = memberIds(existing),
                        activeAccountIdHex = "member-0",
                        pendingInviteMemberIds = emptyList(),
                        stagedRecipientIds = selected,
                    )
                if (projection?.shouldWarn == true) LargeGroupInviteWarningBanner()
            }
        }

        composeRule.onNodeWithTag(LARGE_GROUP_INVITE_WARNING_TAG).assertDoesNotExist()
        composeRule.runOnIdle { rosterReady = true }
        composeRule.onNodeWithTag(LARGE_GROUP_INVITE_WARNING_TAG).assertDoesNotExist()
        composeRule.runOnIdle { selected = listOf("new-a") }
        composeRule.onNodeWithTag(LARGE_GROUP_INVITE_WARNING_TAG).assertIsDisplayed()
        val semantics = composeRule.onNodeWithTag(LARGE_GROUP_INVITE_WARNING_TAG).fetchSemanticsNode().config
        assertEquals(LiveRegionMode.Polite, semantics[SemanticsProperties.LiveRegion])
        assertTrue(semantics[SemanticsProperties.Text].isNotEmpty())
        composeRule.runOnIdle { existing = 48 }
        composeRule.onNodeWithTag(LARGE_GROUP_INVITE_WARNING_TAG).assertDoesNotExist()
    }

    @Test
    fun confirmationRequiresAnExplicitContinueOrCancelChoice() {
        var continues = 0
        var cancels = 0
        var open by mutableStateOf(true)
        composeRule.setContent {
            WhiteNoiseTheme {
                if (open) {
                    LargeGroupInviteConfirmationDialog(
                        onContinue = {
                            continues++
                            open = false
                        },
                        onDismiss = {
                            cancels++
                            open = false
                        },
                    )
                }
            }
        }

        composeRule.onNodeWithTag(LARGE_GROUP_INVITE_CONFIRMATION_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.runOnIdle {
            assertEquals(0, continues)
            assertEquals(1, cancels)
            open = true
        }
        composeRule.onNodeWithText("Continue").performClick()
        composeRule.runOnIdle {
            assertEquals(1, continues)
            assertEquals(1, cancels)
        }
    }

    @Test
    fun stagedSelectionRestoresAndKeepsTheWarningAcrossConfigurationChange() {
        val restoration = StateRestorationTester(composeRule)
        lateinit var selection: SnapshotStateList<RecipientSearch.Candidate>
        restoration.setContent {
            selection =
                rememberSaveable(saver = AddMemberSelectionSaver) {
                    mutableStateListOf()
                }
        }
        composeRule.runOnIdle {
            selection +=
                RecipientSearch.Candidate(
                    accountIdHex = "new-a",
                    displayName = "Alice",
                    npub = "npub1alice",
                )
        }

        restoration.emulateSavedInstanceStateRestore()

        composeRule.runOnIdle {
            assertEquals(listOf("new-a"), selection.map { it.accountIdHex })
            assertEquals("Alice", selection.single().displayName)
            assertEquals("npub1alice", selection.single().npub)
            val projection =
                largeGroupInviteProjection(
                    rosterReady = true,
                    authoritativeMemberIds = memberIds(49),
                    activeAccountIdHex = "member-0",
                    pendingInviteMemberIds = emptyList(),
                    stagedRecipientIds = selection.map { it.accountIdHex },
                )
            assertTrue(projection?.shouldWarn == true)
        }
    }

    private fun memberIds(count: Int): List<String> = List(count) { index -> "member-$index" }
}
