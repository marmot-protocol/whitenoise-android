package dev.ipf.whitenoise.android.ui.group

import androidx.compose.runtime.MutableState
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
import dev.ipf.whitenoise.android.ui.conversation.ConversationSurfaceState
import dev.ipf.whitenoise.android.ui.conversation.rememberConversationSurfaceState
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

    /** Verifies that live roster and selection changes add and remove the persistent warning at the threshold. */
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

    /** Requires an explicit dialog action and dispatches exactly one matching callback. */
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

    /** Restores the parent details route, staged recipients, warning, and confirmation as one navigation stack. */
    @Test
    fun detailsRouteAndStagedSelectionRestoreTogether() {
        val restoration = StateRestorationTester(composeRule)
        lateinit var surfaceState: ConversationSurfaceState
        lateinit var selection: SnapshotStateList<RecipientSearch.Candidate>
        lateinit var confirmationOpen: MutableState<Boolean>
        restoration.setContent {
            surfaceState =
                rememberConversationSurfaceState(
                    controllerIdentity = "controller-a",
                    accountRef = "account-a",
                    chatId = "chat-a",
                    runtimeGeneration = 1,
                )
            if (surfaceState.showDetails.value) {
                selection =
                    rememberSaveable("group-a", saver = AddMemberSelectionSaver) {
                        mutableStateListOf()
                    }
                confirmationOpen = rememberSaveable("group-a") { mutableStateOf(false) }
                val projection =
                    largeGroupInviteProjection(
                        rosterReady = true,
                        authoritativeMemberIds = memberIds(49),
                        activeAccountIdHex = "member-0",
                        pendingInviteMemberIds = emptyList(),
                        stagedRecipientIds = selection.map { it.accountIdHex },
                    )
                if (projection?.shouldWarn == true) LargeGroupInviteWarningBanner()
                if (confirmationOpen.value && projection?.shouldWarn == true) {
                    LargeGroupInviteConfirmationDialog(
                        onContinue = {},
                        onDismiss = {},
                    )
                }
            }
        }
        composeRule.runOnIdle {
            surfaceState.showDetails.value = true
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            selection +=
                RecipientSearch.Candidate(
                    accountIdHex = "new-a",
                    displayName = "Alice",
                    npub = "npub1alice",
                )
            confirmationOpen.value = true
        }
        composeRule.onNodeWithTag(LARGE_GROUP_INVITE_WARNING_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(LARGE_GROUP_INVITE_CONFIRMATION_TAG).assertIsDisplayed()

        restoration.emulateSavedInstanceStateRestore()

        composeRule.runOnIdle {
            assertTrue(surfaceState.showDetails.value)
            assertEquals(listOf("new-a"), selection.map { it.accountIdHex })
            assertEquals("Alice", selection.single().displayName)
            assertEquals("npub1alice", selection.single().npub)
            assertTrue(confirmationOpen.value)
        }
        composeRule.onNodeWithTag(LARGE_GROUP_INVITE_WARNING_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(LARGE_GROUP_INVITE_CONFIRMATION_TAG).assertIsDisplayed()
    }

    /** Generates stable unique member identities for Compose projection fixtures. */
    private fun memberIds(count: Int): List<String> = List(count) { index -> "member-$index" }
}
