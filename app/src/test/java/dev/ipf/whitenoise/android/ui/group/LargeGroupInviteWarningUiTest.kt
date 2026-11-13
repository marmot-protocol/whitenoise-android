package dev.ipf.whitenoise.android.ui.group

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.ipf.marmotkit.UserProfileMetadataFfi
import dev.ipf.whitenoise.android.core.RecipientSearch
import dev.ipf.whitenoise.android.ui.chats.newchat.ContactPickerScreen
import dev.ipf.whitenoise.android.ui.chats.newchat.GroupMembersReviewDialog
import dev.ipf.whitenoise.android.ui.chats.newchat.MemberReviewGroup
import dev.ipf.whitenoise.android.ui.chats.newchat.SELECTED_MEMBERS_CONFIRM_TAG
import dev.ipf.whitenoise.android.ui.chats.newchat.groupReviewAddMembersLabel
import dev.ipf.whitenoise.android.ui.chats.newchat.withGroupReviewActorNpub
import dev.ipf.whitenoise.android.ui.conversation.ConversationSurfaceState
import dev.ipf.whitenoise.android.ui.conversation.rememberConversationSurfaceState
import dev.ipf.whitenoise.android.ui.share.emptyAppState
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
        var existing by mutableStateOf(149)
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
        composeRule.runOnIdle { existing = 148 }
        composeRule.onNodeWithTag(LARGE_GROUP_INVITE_WARNING_TAG).assertDoesNotExist()
    }

    /** The review carries the warning, and its own actions dispatch exactly one matching callback. */
    @Test
    fun reviewWithWarningConfirmsOnceAndCancelsWithoutSending() {
        var confirms = 0
        var cancels = 0
        var open by mutableStateOf(true)
        composeRule.setContent {
            WhiteNoiseTheme {
                if (open) {
                    GroupMembersReviewDialog(
                        appState = reviewState,
                        groups = listOf(MemberReviewGroup("garden", "Garden club")),
                        members = listOf(alice, bob),
                        busy = false,
                        onDismiss = {
                            cancels++
                            open = false
                        },
                        onConfirm = {
                            confirms++
                            open = false
                        },
                        confirmLabel = "Add members",
                        description = LARGE_GROUP_WARNING,
                    )
                }
            }
        }

        composeRule.onNodeWithText(LARGE_GROUP_WARNING).assertIsDisplayed()
        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.runOnIdle {
            assertEquals(0, confirms)
            assertEquals(1, cancels)
            open = true
        }
        composeRule.onNodeWithTag(SELECTED_MEMBERS_CONFIRM_TAG).performClick()
        composeRule.runOnIdle {
            assertEquals(1, confirms)
            assertEquals(1, cancels)
        }
    }

    /** One selected person gets the singular action, which also sends once. */
    @Test
    fun singleMemberReviewUsesSingularActionAndConfirmsOnce() {
        var confirms = 0
        composeRule.setContent {
            WhiteNoiseTheme {
                GroupMembersReviewDialog(
                    appState = reviewState,
                    groups = listOf(MemberReviewGroup("garden", "Garden club")),
                    members = listOf(alice),
                    busy = false,
                    onDismiss = {},
                    onConfirm = { confirms++ },
                    confirmLabel = stringResource(groupReviewAddMembersLabel(1)),
                    description = LARGE_GROUP_WARNING,
                )
            }
        }

        composeRule.onNodeWithText(LARGE_GROUP_WARNING).assertIsDisplayed()
        composeRule.onNodeWithTag(SELECTED_MEMBERS_CONFIRM_TAG).performClick()
        composeRule.runOnIdle { assertEquals(1, confirms) }
    }

    /** Confirming the picker's review sends once and never opens a second large-group dialog. */
    @Test
    fun pickerReviewIsTheOnlyConfirmationForLargeGroups() {
        var submits = 0
        composeRule.setContent {
            WhiteNoiseTheme {
                ContactPickerScreen(
                    appState = reviewState,
                    title = "Add member",
                    selected = mutableStateListOf(alice, bob),
                    onBack = {},
                    onConfirm = { submits++ },
                    confirmLabel = "Add members",
                    reviewGroup = MemberReviewGroup("garden", "Garden club"),
                    reviewDescription = LARGE_GROUP_WARNING,
                )
            }
        }

        composeRule.onNodeWithContentDescription("Review selected members").performClick()
        composeRule.onNodeWithText(LARGE_GROUP_WARNING).assertIsDisplayed()
        composeRule.onNodeWithTag(SELECTED_MEMBERS_CONFIRM_TAG).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(SECOND_DIALOG_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag("group.members.review.dialog").assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(1, submits) }
    }

    /** A selection that changes while the review is open cannot be submitted from that review. */
    @Test
    fun reviewRefusesConfirmAfterSelectedMembersChange() {
        var submits = 0
        val selected = mutableStateListOf(alice)
        composeRule.setContent {
            WhiteNoiseTheme {
                ContactPickerScreen(
                    appState = reviewState,
                    title = "Add member",
                    selected = selected,
                    onBack = {},
                    onConfirm = { submits++ },
                    confirmLabel = "Add member",
                    reviewGroup = MemberReviewGroup("garden", "Garden club"),
                    reviewDescription = LARGE_GROUP_WARNING,
                )
            }
        }

        composeRule.onNodeWithContentDescription("Review selected members").performClick()
        composeRule.runOnIdle { selected += bob }
        composeRule.onNodeWithTag(SELECTED_MEMBERS_CONFIRM_TAG).performClick()
        composeRule.runOnIdle { assertEquals(0, submits) }
    }

    /** Admin toggles made inside the review are intended edits and are submitted as shown. */
    @Test
    fun reviewSubmitsAdminSetEditedInsideIt() {
        var submitted = emptySet<String>()
        val admins = mutableStateListOf<String>()
        composeRule.setContent {
            WhiteNoiseTheme {
                ContactPickerScreen(
                    appState = reviewState,
                    title = "Add member",
                    selected = mutableStateListOf(alice),
                    onBack = {},
                    onConfirm = { submitted = admins.toSet() },
                    confirmLabel = "Add member",
                    reviewGroup = MemberReviewGroup("garden", "Garden club"),
                    reviewDescription = LARGE_GROUP_WARNING,
                    initialAdminIds = admins.toSet(),
                    onInitialAdminChange = { person, admin ->
                        if (admin) admins.add(person.accountIdHex) else admins.remove(person.accountIdHex)
                    },
                )
            }
        }

        composeRule.onNodeWithContentDescription("Review selected members").performClick()
        composeRule.onNodeWithContentDescription("Admin, Alice").performClick()
        composeRule.onNodeWithTag(SELECTED_MEMBERS_CONFIRM_TAG).performClick()
        composeRule.runOnIdle { assertEquals(setOf(alice.accountIdHex), submitted) }
    }

    /** When the roster drops below the threshold the warning goes away and the same confirm still sends. */
    @Test
    fun warningDisappearsWhenThresholdIsNoLongerReachedAndConfirmStillSends() {
        var submits = 0
        var description by mutableStateOf<String?>(LARGE_GROUP_WARNING)
        composeRule.setContent {
            WhiteNoiseTheme {
                ContactPickerScreen(
                    appState = reviewState,
                    title = "Add member",
                    selected = mutableStateListOf(alice),
                    onBack = {},
                    onConfirm = { submits++ },
                    confirmLabel = "Add member",
                    reviewGroup = MemberReviewGroup("garden", "Garden club"),
                    reviewDescription = description,
                )
            }
        }

        composeRule.onNodeWithContentDescription("Review selected members").performClick()
        composeRule.onNodeWithText(LARGE_GROUP_WARNING).assertIsDisplayed()
        composeRule.runOnIdle { description = null }
        composeRule.onNodeWithText(LARGE_GROUP_WARNING).assertDoesNotExist()
        composeRule.onNodeWithTag(SELECTED_MEMBERS_CONFIRM_TAG).performClick()
        composeRule.runOnIdle { assertEquals(1, submits) }
    }

    /** Restores the parent details route, staged recipients, warning, and open review as one navigation stack. */
    @Test
    fun detailsRouteAndStagedSelectionRestoreTogether() {
        val restoration = StateRestorationTester(composeRule)
        lateinit var surfaceState: ConversationSurfaceState
        lateinit var selection: SnapshotStateList<RecipientSearch.Candidate>
        lateinit var reviewOpen: MutableState<Boolean>
        restoration.setContent {
            surfaceState = rememberConversationSurfaceState("controller-a", "account-a", "chat-a", 1)
            if (surfaceState.showDetails.value) {
                selection =
                    rememberSaveable("group-a", saver = AddMemberSelectionSaver) {
                        mutableStateListOf()
                    }
                reviewOpen = rememberSaveable("group-a") { mutableStateOf(false) }
                val projection =
                    largeGroupInviteProjection(
                        rosterReady = true,
                        authoritativeMemberIds = memberIds(149),
                        activeAccountIdHex = "member-0",
                        pendingInviteMemberIds = emptyList(),
                        stagedRecipientIds = selection.map { it.accountIdHex },
                    )
                if (projection?.shouldWarn == true) LargeGroupInviteWarningBanner()
                if (reviewOpen.value) {
                    GroupMembersReviewDialog(
                        appState = reviewState,
                        groups = listOf(MemberReviewGroup("group-a", "Garden club")),
                        members = selection.toList(),
                        busy = false,
                        onDismiss = {},
                        onConfirm = {},
                        confirmLabel = "Add member",
                        description = if (projection?.shouldWarn == true) LARGE_GROUP_WARNING else null,
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
            reviewOpen.value = true
        }
        assertWarningAndReviewDisplayed()

        restoration.emulateSavedInstanceStateRestore()

        composeRule.runOnIdle {
            assertTrue(surfaceState.showDetails.value)
            assertEquals(listOf("new-a"), selection.map { it.accountIdHex })
            assertEquals("Alice", selection.single().displayName)
            assertEquals("npub1alice", selection.single().npub)
            assertTrue(reviewOpen.value)
        }
        assertWarningAndReviewDisplayed()
    }

    private fun assertWarningAndReviewDisplayed() {
        composeRule.onNodeWithTag(LARGE_GROUP_INVITE_WARNING_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag("group.members.review.dialog").assertIsDisplayed()
        composeRule.onNodeWithText(LARGE_GROUP_WARNING).assertIsDisplayed()
    }

    private val reviewState by lazy { emptyAppState().withGroupReviewActorNpub() }
    private val alice =
        RecipientSearch.Candidate(
            "a1".repeat(32),
            "Alice",
            "",
            searchProfile = UserProfileMetadataFfi("Alice", null, null, null, null, null, null),
        )
    private val bob = RecipientSearch.Candidate("b2".repeat(32), "Bob", "")

    /** Generates stable unique member identities for Compose projection fixtures. */
    private fun memberIds(count: Int): List<String> = List(count) { index -> "member-$index" }

    private companion object {
        const val LARGE_GROUP_WARNING =
            "In groups of 150 or more members, additional invitations might not be delivered reliably. " +
                "You can still continue."
        const val SECOND_DIALOG_TAG = "large-group-invite-confirmation"
    }
}
