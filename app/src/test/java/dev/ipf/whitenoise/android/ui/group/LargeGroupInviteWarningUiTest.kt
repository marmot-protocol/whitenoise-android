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
import androidx.compose.ui.test.assertIsOn
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

    /** Shows the warning inside the one review; Cancel dispatches only the dismiss callback. */
    @Test
    fun reviewShowsWarningAndCancelDispatchesOnlyDismiss() {
        val members = listOf(candidate("b", "Alice"), candidate("c", "Bob"))
        var confirms = 0
        var dismisses = 0
        showReview(members = { members }, onConfirm = { confirms++ }, onDismiss = { dismisses++ })

        composeRule.onNodeWithText(WARNING_TEXT).assertIsDisplayed()
        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.runOnIdle {
            assertEquals(0, confirms)
            assertEquals(1, dismisses)
        }
    }

    /** Confirming the review dispatches exactly one confirm callback and no dismiss. */
    @Test
    fun reviewConfirmDispatchesOnlyConfirm() {
        val members = listOf(candidate("b", "Alice"), candidate("c", "Bob"))
        var confirms = 0
        var dismisses = 0
        showReview(members = { members }, onConfirm = { confirms++ }, onDismiss = { dismisses++ })

        composeRule.onNodeWithTag(SELECTED_MEMBERS_CONFIRM_TAG).performClick()
        composeRule.runOnIdle {
            assertEquals(1, confirms)
            assertEquals(0, dismisses)
        }
    }

    /** A single selected person gets the singular action and still confirms with one tap. */
    @Test
    fun reviewForOnePersonUsesSingularActionAndConfirmsOnce() {
        var confirms = 0
        showReview(members = { listOf(candidate("b", "Alice")) }, onConfirm = { confirms++ })

        composeRule.onNodeWithText(WARNING_TEXT).assertIsDisplayed()
        composeRule.onNodeWithTag(SELECTED_MEMBERS_CONFIRM_TAG).performClick()
        composeRule.runOnIdle { assertEquals(1, confirms) }
    }

    /** The picker's review confirm sends once and no separate large-group dialog follows it. */
    @Test
    fun noSecondDialogFollowsTheReviewConfirm() {
        val state = emptyAppState().withGroupReviewActorNpub()
        val selected = mutableStateListOf(candidate("b", "Alice"))
        var invites = 0
        composeRule.setContent {
            WhiteNoiseTheme {
                ContactPickerScreen(
                    appState = state,
                    title = "Choose people",
                    selected = selected,
                    onBack = {},
                    onConfirm = { invites++ },
                    confirmLabel = "Add member",
                    reviewGroup = MemberReviewGroup("garden", "Garden club"),
                    reviewDescription = WARNING_TEXT,
                )
            }
        }
        composeRule.onNodeWithContentDescription("Review selected members").performClick()
        composeRule.onNodeWithText(WARNING_TEXT).assertIsDisplayed()
        composeRule.onNodeWithTag(SELECTED_MEMBERS_CONFIRM_TAG).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("large-group-invite-confirmation").assertDoesNotExist()
        composeRule.onNodeWithTag("group.members.review.dialog").assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(1, invites) }
    }

    /** A member list that changes while the review is open cannot be submitted. */
    @Test
    fun reviewRefusesConfirmAfterTheMemberListChanges() {
        val selected = mutableStateListOf(candidate("b", "Alice"), candidate("c", "Bob"))
        var confirms = 0
        showReview(members = { selected }, onConfirm = { confirms++ })

        composeRule.runOnIdle { selected.removeAt(1) }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(SELECTED_MEMBERS_CONFIRM_TAG).performClick()
        composeRule.runOnIdle { assertEquals(0, confirms) }
    }

    /** Admin choices made inside the review are the set that gets submitted. */
    @Test
    fun adminToggleInsideReviewIsSubmittedAsShown() {
        val admins = mutableStateListOf<String>()
        var submitted: Set<String>? = null
        showReview(
            members = { listOf(candidate("b", "Alice")) },
            admins = admins,
            onConfirm = { submitted = admins.toSet() },
        )

        composeRule.onNodeWithContentDescription("Admin, Alice").performClick()
        composeRule.onNodeWithContentDescription("Admin, Alice").assertIsOn()
        composeRule.onNodeWithTag(SELECTED_MEMBERS_CONFIRM_TAG).performClick()
        composeRule.runOnIdle { assertEquals(setOf("b".repeat(64)), submitted) }
    }

    /** The warning text leaves the same review when the projection drops below the threshold. */
    @Test
    fun warningDisappearsWhenTheProjectionNoLongerWarns() {
        var warn by mutableStateOf(true)
        var confirms = 0
        showReview(
            members = { listOf(candidate("b", "Alice")) },
            description = { if (warn) WARNING_TEXT else null },
            onConfirm = { confirms++ },
        )

        composeRule.onNodeWithText(WARNING_TEXT).assertIsDisplayed()
        composeRule.runOnIdle { warn = false }
        composeRule.onNodeWithText(WARNING_TEXT).assertDoesNotExist()
        composeRule.onNodeWithTag(SELECTED_MEMBERS_CONFIRM_TAG).performClick()
        composeRule.runOnIdle { assertEquals(1, confirms) }
    }

    /** Restores the parent details route, staged recipients, warning, and open review as one navigation stack. */
    @Test
    fun detailsRouteAndStagedSelectionRestoreTogether() {
        val restoration = StateRestorationTester(composeRule)
        val reviewState = emptyAppState().withGroupReviewActorNpub()
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
                        members = selection,
                        busy = false,
                        onDismiss = {},
                        onConfirm = {},
                        confirmLabel = "Add member",
                        description = if (projection?.shouldWarn == true) WARNING_TEXT else null,
                    )
                }
            }
        }
        composeRule.runOnIdle {
            surfaceState.showDetails.value = true
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            selection += RecipientSearch.Candidate(accountIdHex = "new-a", displayName = "Alice", npub = "npub1alice")
            reviewOpen.value = true
        }
        assertBannerAndWarnedReviewShown()

        restoration.emulateSavedInstanceStateRestore()

        composeRule.runOnIdle {
            assertTrue(surfaceState.showDetails.value)
            assertEquals(listOf("new-a"), selection.map { it.accountIdHex })
            assertEquals("Alice", selection.single().displayName)
            assertEquals("npub1alice", selection.single().npub)
            assertTrue(reviewOpen.value)
        }
        assertBannerAndWarnedReviewShown()
    }

    /** Renders the review dialog the way Group Details feeds it, with a live member list and optional warning. */
    private fun assertBannerAndWarnedReviewShown() {
        composeRule.onNodeWithTag(LARGE_GROUP_INVITE_WARNING_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(REVIEW_TAG).assertIsDisplayed()
        composeRule.onNodeWithText(WARNING_TEXT).assertIsDisplayed()
    }

    private fun showReview(
        members: () -> List<RecipientSearch.Candidate>,
        admins: SnapshotStateList<String> = mutableStateListOf(),
        description: () -> String? = { WARNING_TEXT },
        onConfirm: () -> Unit,
        onDismiss: () -> Unit = {},
    ) {
        val state = emptyAppState().withGroupReviewActorNpub()
        composeRule.setContent {
            WhiteNoiseTheme {
                val current = members()
                GroupMembersReviewDialog(
                    appState = state,
                    groups = listOf(MemberReviewGroup("garden", "Garden club")),
                    members = current,
                    busy = false,
                    onDismiss = onDismiss,
                    onConfirm = onConfirm,
                    confirmLabel = if (current.size == 1) "Add member" else "Add members",
                    initialAdminIds = admins.toSet(),
                    onInitialAdminChange = { person, admin ->
                        if (admin) admins.add(person.accountIdHex) else admins.remove(person.accountIdHex)
                    },
                    description = description(),
                )
            }
        }
    }

    /** Builds a named candidate whose profile resolves to a stable display name. */
    private fun candidate(
        hex: String,
        name: String,
    ) = RecipientSearch.Candidate(
        hex.repeat(64),
        name,
        "npub1$name",
        searchProfile = UserProfileMetadataFfi(name, null, null, null, null, null, null),
    )

    /** Generates stable unique member identities for Compose projection fixtures. */
    private fun memberIds(count: Int): List<String> = List(count) { index -> "member-$index" }

    private companion object {
        const val REVIEW_TAG = "group.members.review.dialog"
        const val WARNING_TEXT = "Large groups are harder to moderate. Confirm that everyone should be added."
    }
}
