package dev.ipf.whitenoise.android.ui.chats.newchat

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.UserProfileMetadataFfi
import dev.ipf.whitenoise.android.core.RecipientSearch
import dev.ipf.whitenoise.android.state.advanceLocalDeleteTestRuntime
import dev.ipf.whitenoise.android.ui.common.LocalSystemAnimationsEnabled
import dev.ipf.whitenoise.android.ui.share.emptyAppState
import dev.ipf.whitenoise.android.ui.share.testAccount
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
// Shared Compose fixture covers this dialog across visual variants and terminal ownership boundaries.
@Suppress("LargeClass")
class GroupMembersReviewScreenshotTest {
    @get:Rule val rule = createComposeRule()

    @Test fun cancelWaitsForExit() = checkTerminalExit(cancel = true)

    @Test fun confirmWaitsForExit() = checkTerminalExit()

    @Test fun busyAtCompletionRejectsAndRetiresReview() = checkTerminalExit(changeBusy = true)

    @Test fun adminChangeAtCompletionRejectsAndRetiresReview() = checkTerminalExit(changeAdmins = true)

    @Test fun replacementSelectionCancelsOldCompletion() = checkTerminalExit(replaceSelection = true)

    // Keep the action, mid-exit mutation, and final exactly-once assertions in chronological order.
    @Suppress("LongMethod")
    private fun checkTerminalExit(
        cancel: Boolean = false,
        changeBusy: Boolean = false,
        changeAdmins: Boolean = false,
        replaceSelection: Boolean = false,
    ) {
        val state =
            emptyAppState(accounts = listOf(testAccount("Owner", "a".repeat(64))), activeAccountRef = "Owner")
                .withGroupReviewActorNpub()
        val busy = mutableStateOf(false)
        val admins = mutableStateOf(emptySet<String>())
        val member = mutableStateOf(REVIEW_FIRST_MEMBER_HEX)
        val open = mutableStateOf(true)
        var confirmed = 0
        var dismissed = 0
        rule.mainClock.autoAdvance = false
        rule.setContent {
            WhiteNoiseTheme {
                CompositionLocalProvider(LocalSystemAnimationsEnabled provides true) {
                    if (open.value) {
                        GroupMembersReviewDialog(
                            appState = state,
                            groups = listOf(MemberReviewGroup("garden", "Garden club")),
                            members = listOf(RecipientSearch.Candidate(member.value, "Alice", "")),
                            busy = busy.value,
                            initialAdminIds = admins.value,
                            onConfirm = {
                                confirmed++
                                open.value = false
                            },
                            onDismiss = {
                                dismissed++
                                open.value = false
                            },
                            confirmLabel = "Add member",
                        )
                    }
                }
            }
        }
        rule.mainClock.advanceTimeBy(400)
        if (cancel) {
            rule.onNodeWithText("Cancel").performClick()
        } else {
            rule.onNodeWithTag(SELECTED_MEMBERS_CONFIRM_TAG).performClick()
        }
        rule.mainClock.advanceTimeBy(80)
        rule.runOnIdle {
            assertEquals(0, confirmed)
            assertEquals(0, dismissed)
            assertTrue(open.value)
            if (changeBusy) busy.value = true
            if (changeAdmins) admins.value = setOf(REVIEW_FIRST_MEMBER_HEX)
            if (replaceSelection) member.value = REVIEW_SECOND_MEMBER_HEX
            androidx.compose.runtime.snapshots.Snapshot
                .sendApplyNotifications()
        }
        val expectsDismissal = cancel || changeBusy || changeAdmins
        val expectedDismissals = if (expectsDismissal) 1 else 0
        val expectedConfirmations = if (expectsDismissal || replaceSelection) 0 else 1
        rule.mainClock.advanceTimeBy(800)
        rule.runOnIdle {
            assertEquals(expectedConfirmations, confirmed)
            assertEquals(expectedDismissals, dismissed)
            assertEquals(replaceSelection, open.value)
        }
        rule.mainClock.advanceTimeBy(800)
        rule.runOnIdle { assertEquals(expectedDismissals, dismissed) }
    }

    /** Captures the destination and public member identity in the light theme. */
    @Test fun destinationAndPublicIdentityLight() = capture("light")

    /** Captures the destination and public member identity in the dark theme. */
    @Test fun destinationAndPublicIdentityDark() = capture("dark", dark = true)

    /** Captures the destination and public member identity on the AMOLED surface. */
    @Test fun destinationAndPublicIdentityAmoled() = capture("amoled", dark = true, amoled = true)

    /** Captures the review pair with large text and RTL layout. */
    @Test fun destinationAndPublicIdentityLargeRtl() = capture("large_rtl", rtl = true)

    /** Keeps confirmation and dismissal inert while an invitation is in progress. */
    @Test
    fun busyConfirmationCannotSubmitOrDismiss() {
        var submits = 0
        var dismisses = 0
        val state =
            emptyAppState(accounts = listOf(testAccount("Owner", "a".repeat(64))), activeAccountRef = "Owner")
                .withGroupReviewActorNpub()
        rule.setContent {
            WhiteNoiseTheme {
                GroupMembersReviewDialog(
                    appState = state,
                    groups = listOf(MemberReviewGroup("group", "Garden club")),
                    members =
                        listOf(RecipientSearch.Candidate(REVIEW_SECOND_MEMBER_HEX, "Alice", REVIEW_SECOND_MEMBER_NPUB)),
                    busy = true,
                    onDismiss = { dismisses += 1 },
                    onConfirm = { submits += 1 },
                    confirmLabel = "Add member",
                )
            }
        }
        rule.onNodeWithTag(SELECTED_MEMBERS_CONFIRM_TAG).assertIsNotEnabled().performClick()
        rule.onNodeWithText("Cancel").assertIsNotEnabled().performClick()
        assertEquals(0, submits)
        assertEquals(0, dismisses)
        rule.onNode(isDialog()).captureRoboImage("src/test/snapshots/group_member_review_busy.png")
    }

    /** A callback retained by the old dialog cannot submit after the same account gets a new runtime. */
    @Test
    fun retainedReviewCallbackRejectsRuntimeReplacement() {
        val state =
            emptyAppState(accounts = listOf(testAccount("Owner", "a".repeat(64))), activeAccountRef = "Owner")
                .withGroupReviewActorNpub()
        var submitted = 0
        rule.setContent {
            WhiteNoiseTheme {
                GroupMembersReviewDialog(
                    appState = state,
                    groups = listOf(MemberReviewGroup("garden", "Garden club")),
                    members = listOf(RecipientSearch.Candidate("b".repeat(64), "Alice", "")),
                    busy = false,
                    onDismiss = {},
                    onConfirm = { submitted++ },
                    confirmLabel = "Add members",
                )
            }
        }
        val retained =
            rule
                .onNodeWithTag(SELECTED_MEMBERS_CONFIRM_TAG)
                .fetchSemanticsNode()
                .config[SemanticsActions.OnClick]
                .action!!
        rule.runOnIdle {
            state.advanceLocalDeleteTestRuntime()
            retained()
        }
        assertEquals(0, submitted)
    }

    /** Even a retained semantics callback can claim its review only once. */
    @Test
    fun retainedCallbackCannotRepeatCompletedSubmission() {
        retainedCallbackIsInvalidated(submitFirst = true)
    }

    /** Dismissal and an identical reopening never revive the old presentation. */
    @Test
    fun retainedCallbackCannotSubmitIdenticallyReopenedReview() {
        retainedCallbackIsInvalidated(submitFirst = false)
    }

    private fun retainedCallbackIsInvalidated(submitFirst: Boolean) {
        val state = emptyAppState()
        val visible = mutableStateOf(true)
        var submitted = 0
        rule.setContent {
            WhiteNoiseTheme {
                if (visible.value) {
                    GroupMembersReviewDialog(
                        appState = state,
                        groups = listOf(MemberReviewGroup("garden", "Garden club")),
                        members = listOf(RecipientSearch.Candidate("b".repeat(64), "Alice", "")),
                        busy = false,
                        onDismiss = { visible.value = false },
                        onConfirm = { submitted++ },
                        confirmLabel = "Add members",
                    )
                }
            }
        }
        val retained =
            rule
                .onNodeWithTag(SELECTED_MEMBERS_CONFIRM_TAG)
                .fetchSemanticsNode()
                .config[SemanticsActions.OnClick]
                .action!!
        if (submitFirst) {
            rule.runOnIdle {
                retained()
                retained()
            }
            completeTerminalExit()
            assertEquals(1, submitted)
        } else {
            rule.onNodeWithText("Cancel").performClick()
            rule.waitForIdle()
            rule.runOnIdle { visible.value = true }
            rule.waitForIdle()
            rule.runOnIdle { invokeDetachedReviewClick(retained) }
            assertEquals(0, submitted)
            rule.onNodeWithTag(SELECTED_MEMBERS_CONFIRM_TAG).performClick()
            completeTerminalExit()
            assertEquals(1, submitted)
        }
    }

    /** An unavailable formatter never leaks raw keys or operational group identifiers; the actor stays an avatar. */
    @Test
    fun unavailableFormatterHidesRawKeysAndShowsActorAvatar() {
        val actor = "a".repeat(64)
        val member = "b".repeat(64)
        val state = emptyAppState(accounts = listOf(testAccount("Owner", actor)), activeAccountRef = "Owner")
        rule.setContent {
            WhiteNoiseTheme(darkTheme = true) {
                GroupMembersReviewDialog(
                    appState = state,
                    groups = listOf(MemberReviewGroup("garden", "Garden club")),
                    members = listOf(RecipientSearch.Candidate(member, "", "")),
                    busy = false,
                    onDismiss = {},
                    onConfirm = {},
                    confirmLabel = "Add member",
                )
            }
        }
        rule.onNodeWithTag("group.action.acting_account").assertIsDisplayed()
        rule.onAllNodesWithText(actor, substring = true).assertCountEquals(0)
        rule.onAllNodesWithText(member, substring = true).assertCountEquals(0)
        rule.onAllNodesWithText("Public key", substring = true).assertCountEquals(0)
        rule.onAllNodesWithText("Group ID", substring = true).assertCountEquals(0)
        rule.onAllNodesWithText("1 group").assertCountEquals(0)
        rule.onNodeWithTag(SELECTED_MEMBERS_CONFIRM_TAG).assertIsDisplayed()
        rule.onNode(isDialog()).captureRoboImage("src/test/snapshots/group_action_missing_public_metadata_dark.png")
    }

    /** An opaque runtime reference never becomes a public key; its local account label stays explicit. */
    @Test
    fun opaqueActorReferenceUsesExplicitLocalLabel() {
        val state =
            emptyAppState(
                accounts = listOf(testAccount("Owner", "private-runtime-reference")),
                activeAccountRef = "Owner",
            )
        rule.setContent {
            WhiteNoiseTheme { GroupActingAccountIdentity(state, "Owner") }
        }
        rule.onNodeWithText("Local account label: Owner").assertIsDisplayed()
        rule.onAllNodesWithText("private-runtime-reference").assertCountEquals(0)
        rule.onAllNodesWithText("Public key (hex)").assertCountEquals(0)
    }

    /** Revoking admin rights explicitly keeps membership and identifies the account performing it. */
    @Test
    fun revokeReviewExplainsMembershipAndActorDark() {
        val state =
            emptyAppState(accounts = listOf(testAccount("Owner", "a".repeat(64))), activeAccountRef = "Owner")
                .withGroupReviewActorNpub()
        rule.setContent {
            WhiteNoiseTheme(darkTheme = true) {
                GroupMembersReviewDialog(
                    appState = state,
                    groups = listOf(MemberReviewGroup("garden", "Garden club")),
                    members =
                        listOf(
                            RecipientSearch.Candidate(
                                "b".repeat(64),
                                "Private Alice",
                                "",
                                searchProfile =
                                    UserProfileMetadataFfi("Public Alice", null, null, null, null, null, null),
                            ),
                        ),
                    busy = false,
                    onDismiss = {},
                    onConfirm = {},
                    confirmLabel = "Remove admin",
                    title = "Remove admin",
                    description = "This removes admin rights. The person stays a member.",
                    invitation = false,
                )
            }
        }
        rule.onNodeWithTag("group.action.acting_account").assertExists()
        rule.onNodeWithText("This removes admin rights. The person stays a member.").assertIsDisplayed()
        rule.onNodeWithText("Public Alice").performScrollTo().assertIsDisplayed()
        rule.onNode(isDialog()).captureRoboImage("src/test/snapshots/group_action_revoke_review_dark.png")
    }

    /** Same-named destinations remain separate rows while their operational IDs stay hidden. */
    @Test
    fun duplicateGroupNamesKeepSeparateRowsWithoutIdentifiers() {
        val state = emptyAppState().withGroupReviewActorNpub()
        rule.setContent {
            WhiteNoiseTheme {
                GroupMembersReviewDialog(
                    appState = state,
                    groups =
                        listOf(
                            MemberReviewGroup("garden-one", "Garden club"),
                            MemberReviewGroup("garden-two", "Garden club"),
                        ),
                    members = listOf(RecipientSearch.Candidate("b".repeat(64), "Alice", "")),
                    busy = false,
                    onDismiss = {},
                    onConfirm = {},
                    confirmLabel = "Add member",
                )
            }
        }
        rule.onAllNodesWithText("Garden club").assertCountEquals(2)
        rule.onNodeWithContentDescription("Group ID, garden-one").assertDoesNotExist()
        rule.onNodeWithContentDescription("Group ID, garden-two").assertDoesNotExist()
        rule.onNodeWithTag(SELECTED_MEMBERS_CONFIRM_TAG).assertIsDisplayed()
    }

    /** Duplicate public names remain distinguishable and copy the complete canonical npub. */
    @Test
    fun sameNamedPeopleKeepSeparateCopyableNpubs() {
        val state = emptyAppState().withGroupReviewActorNpub()
        rule.setContent {
            WhiteNoiseTheme {
                GroupMembersReviewDialog(
                    appState = state,
                    groups = listOf(MemberReviewGroup("garden", "Garden club")),
                    members =
                        listOf(
                            RecipientSearch.Candidate(
                                REVIEW_FIRST_MEMBER_HEX,
                                "Alex",
                                REVIEW_FIRST_MEMBER_NPUB,
                                searchProfile = UserProfileMetadataFfi("Alex", null, null, null, null, null, null),
                            ),
                            RecipientSearch.Candidate(
                                REVIEW_SECOND_MEMBER_HEX,
                                "Alex",
                                REVIEW_SECOND_MEMBER_NPUB,
                                searchProfile = UserProfileMetadataFfi("Alex", null, null, null, null, null, null),
                            ),
                        ),
                    busy = false,
                    onDismiss = {},
                    onConfirm = {},
                    confirmLabel = "Add members",
                )
            }
        }
        rule.onAllNodesWithText("Alex").assertCountEquals(2)
        val clipboard =
            ApplicationProvider
                .getApplicationContext<Context>()
                .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        listOf(REVIEW_FIRST_MEMBER_NPUB, REVIEW_SECOND_MEMBER_NPUB).forEach { npub ->
            val memberCopy = rule.onNodeWithContentDescription("Alex, Member, $npub")
            memberCopy.performScrollTo()
            val memberBounds = memberCopy.fetchSemanticsNode().boundsInRoot
            assertTrue(memberBounds.height >= 48f && memberBounds.width >= 48f)
            memberCopy.performClick()
            assertEquals(
                npub,
                clipboard.primaryClip!!
                    .getItemAt(0)
                    .text
                    .toString(),
            )
        }
    }

    /** Keeps review actions and the final member reachable when the list overflows. */
    @Test
    fun overflowingReviewKeepsActionsAndLastMemberReachable() {
        val state =
            emptyAppState(accounts = listOf(testAccount("Owner", "a".repeat(64))), activeAccountRef = "Owner")
                .withGroupReviewActorNpub()
        val members =
            (1..8).map {
                RecipientSearch.Candidate(
                    it.toString(16).padStart(64, '0'),
                    "Private member $it",
                    "npub1member$it",
                    searchProfile = UserProfileMetadataFfi("Member $it", null, null, null, null, null, null),
                )
            }
        var submits = 0
        rule.setContent {
            WhiteNoiseTheme(fontScale = 2f) {
                GroupMembersReviewDialog(
                    appState = state,
                    groups = listOf(MemberReviewGroup("garden", "Garden club")),
                    members = members,
                    busy = false,
                    onDismiss = {},
                    onConfirm = { submits += 1 },
                    confirmLabel = "Add members",
                )
            }
        }
        rule.onNodeWithTag(SELECTED_MEMBERS_CONFIRM_TAG).assertIsDisplayed()
        rule.onNodeWithText("Member 8").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Cancel").assertIsDisplayed()
        rule
            .onNodeWithTag("group.members.review.dialog")
            .captureRoboImage("src/test/snapshots/group_member_review_overflow.png")
        rule.onNodeWithTag(SELECTED_MEMBERS_CONFIRM_TAG).performClick()
        completeTerminalExit()
        assertEquals(1, submits)
    }

    /** A dozen destinations scroll in the body, so huge text never pushes the actions off screen. */
    @Test
    fun manyDestinationsKeepActionsAndLastGroupReachable() {
        val state =
            emptyAppState(accounts = listOf(testAccount("Owner", "a".repeat(64))), activeAccountRef = "Owner")
                .withGroupReviewActorNpub()
        val member =
            RecipientSearch.Candidate(
                "b".repeat(64),
                "Private member",
                "npub1member",
                searchProfile = UserProfileMetadataFfi("Member", null, null, null, null, null, null),
            )
        rule.setContent {
            WhiteNoiseTheme(fontScale = 2f) {
                GroupMembersReviewDialog(
                    appState = state,
                    groups = (1..30).map { MemberReviewGroup("group-$it", "Group $it") },
                    members = listOf(member),
                    busy = false,
                    onDismiss = {},
                    onConfirm = {},
                    confirmLabel = "Add member",
                )
            }
        }
        rule.onNodeWithTag(SELECTED_MEMBERS_CONFIRM_TAG).assertIsDisplayed()
        rule.onNodeWithText("Cancel").assertIsDisplayed()
        rule.onNodeWithText("Group 30").assertIsNotDisplayed()
        rule.onNodeWithText("Group 30").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag(SELECTED_MEMBERS_CONFIRM_TAG).assertIsDisplayed()
        rule
            .onNodeWithTag("group.members.review.dialog")
            .captureRoboImage("src/test/snapshots/group_member_review_many_groups.png")
    }

    /** Retains selection on cancellation and closes review when its destination changes. */
    @Test
    fun cancellingPreservesSelectionAndChangingGroupClosesReview() {
        val state =
            emptyAppState(accounts = listOf(testAccount("Owner", "a".repeat(64))), activeAccountRef = "Owner")
                .withGroupReviewActorNpub()
        val selected =
            mutableStateListOf(
                RecipientSearch.Candidate(
                    REVIEW_FIRST_MEMBER_HEX,
                    "Alice",
                    REVIEW_FIRST_MEMBER_NPUB,
                    searchProfile = UserProfileMetadataFfi("Alice", null, null, null, null, null, null),
                ),
            )
        val admins = mutableStateListOf<String>()
        val group = mutableStateOf(MemberReviewGroup("garden", "Garden club"))
        var submits = 0
        rule.setContent {
            WhiteNoiseTheme {
                ContactPickerScreen(
                    appState = state,
                    title = "Choose people",
                    selected = selected,
                    onBack = {},
                    onConfirm = { submits += 1 },
                    confirmLabel = "Add member",
                    reviewGroup = group.value,
                    initialAdminIds = admins.toSet(),
                    onInitialAdminChange = { person, admin ->
                        if (admin) admins.add(person.accountIdHex) else admins.remove(person.accountIdHex)
                    },
                )
            }
        }
        rule.onNodeWithContentDescription("Review selected members").performClick()
        rule.onNodeWithContentDescription("Admin, Alice").performClick()
        rule.onNodeWithText("Cancel").performClick()
        rule.onNodeWithTag("group.members.review.dialog").assertDoesNotExist()
        assertEquals(1, selected.size)
        assertEquals(listOf(REVIEW_FIRST_MEMBER_HEX), admins.toList())
        assertEquals(0, submits)
        rule.onNodeWithContentDescription("Review selected members").performClick()
        rule.onNodeWithContentDescription("Admin, Alice").assertIsOn()
        rule.runOnIdle { group.value = MemberReviewGroup("orchard", "Orchard club") }
        rule.onNodeWithTag("group.members.review.dialog").assertDoesNotExist()
        assertEquals(0, submits)
    }

    /** Changing one member's role never transfers that privilege to another person after removal. */
    @Test
    fun removingMemberDoesNotMoveTheirAdminRoleToAnotherPerson() {
        val state =
            emptyAppState(accounts = listOf(testAccount("Owner", "a".repeat(64))), activeAccountRef = "Owner")
                .withGroupReviewActorNpub()

        /** Creates a stable public member identity for the role-menu ownership check. */
        fun member(
            hex: String,
            name: String,
        ) = RecipientSearch.Candidate(
            hex.repeat(64),
            name,
            "npub1$name",
            searchProfile = UserProfileMetadataFfi(name, null, null, null, null, null, null),
        )
        val selected = mutableStateListOf(member("b", "Alice"), member("c", "Bob"))
        val admins = mutableStateListOf<String>()
        rule.setContent {
            WhiteNoiseTheme {
                GroupMembersReviewDialog(
                    appState = state,
                    groups = listOf(MemberReviewGroup("garden", "Garden club")),
                    members = selected,
                    busy = false,
                    onDismiss = {},
                    onConfirm = {},
                    confirmLabel = "Add members",
                    initialAdminIds = admins.toSet(),
                    onInitialAdminChange = { person, admin ->
                        if (admin) admins.add(person.accountIdHex) else admins.remove(person.accountIdHex)
                    },
                )
            }
        }
        rule.onNodeWithContentDescription("Admin, Alice").performClick()
        rule.runOnIdle { assertEquals(listOf("b".repeat(64)), admins.toList()) }
        rule.runOnIdle { selected.removeAt(0) }
        rule.onNodeWithContentDescription("Admin, Bob").assertIsOff()
        rule.onNodeWithContentDescription("Admin, Bob").assertIsDisplayed()
    }

    /** Unrelated work can disable confirmation without trapping the user or showing false progress. */
    @Test
    fun unrelatedWorkStillAllowsCancellingRoleReview() {
        var dismisses = 0
        val state =
            emptyAppState(accounts = listOf(testAccount("Owner", "a".repeat(64))), activeAccountRef = "Owner")
                .withGroupReviewActorNpub()
        rule.setContent {
            WhiteNoiseTheme {
                GroupMembersReviewDialog(
                    appState = state,
                    groups = listOf(MemberReviewGroup("garden", "Garden club")),
                    members =
                        listOf(
                            RecipientSearch.Candidate(
                                "202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f",
                                "",
                                "npub1yqsjygeyy5nzw2pf9g4jctfw9ucrzv3nxs6nvdec8yark0pa8clst3m4tg",
                            ),
                        ),
                    busy = true,
                    dismissEnabled = true,
                    showProgress = false,
                    invitation = false,
                    description = null,
                    title = "Make admin",
                    confirmLabel = "Make admin",
                    onConfirm = {},
                    onDismiss = { dismisses += 1 },
                )
            }
        }
        rule.onNode(hasTestTag(SELECTED_MEMBERS_CONFIRM_TAG) and hasText("Make admin")).assertIsNotEnabled()
        rule.onNodeWithText("Cancel").performClick()
        completeTerminalExit()
        assertEquals(1, dismisses)
    }

    /** An unresolved public name shows exactly one shortened npub, below its destinations. */
    @Test
    fun unnamedMemberHasOneAbbreviatedIdentity() {
        val state =
            emptyAppState(accounts = listOf(testAccount("Owner", "a".repeat(64))), activeAccountRef = "Owner")
                .withGroupReviewActorNpub()
        val npub = "npub1yqsjygeyy5nzw2pf9g4jctfw9ucrzv3nxs6nvdec8yark0pa8clst3m4tg"
        rule.setContent {
            WhiteNoiseTheme {
                GroupMembersReviewDialog(
                    appState = state,
                    groups =
                        listOf(
                            MemberReviewGroup("garden", "Garden club"),
                            MemberReviewGroup("orchard", "Orchard club"),
                        ),
                    members =
                        listOf(
                            RecipientSearch.Candidate(
                                "202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f",
                                npub,
                                npub,
                            ),
                        ),
                    busy = false,
                    onDismiss = {},
                    onConfirm = {},
                    confirmLabel = "Add member",
                )
            }
        }
        rule.onAllNodesWithText(npub).assertCountEquals(0)
        val short =
            dev.ipf.whitenoise.android.core.IdentityFormatter
                .short(npub, prefix = 10, suffix = 8)
        rule.onAllNodesWithText(short).assertCountEquals(1)
        val person = rule.onNodeWithTag("group.members.review.member").fetchSemanticsNode().boundsInRoot
        val group = rule.onNodeWithTag("group.members.review.destination").fetchSemanticsNode().boundsInRoot
        assertTrue(group.bottom <= person.top)
        rule
            .onNodeWithTag("group.members.review.dialog")
            .captureRoboImage("src/test/snapshots/group_member_review_unnamed.png")
    }

    /** Separates each member's own role control at large font sizes. */
    @Test
    @Config(qualifiers = "en-w360dp-h1280dp-mdpi")
    fun multipleMembersLarge() = captureMultipleMembers(false)

    /** Mirrors the member rows without losing per-member control grouping. */
    @Test
    @Config(qualifiers = "ar-rEG-ldrtl-w360dp-h1280dp-mdpi")
    fun multipleMembersLargeRtl() = captureMultipleMembers(true)

    private fun captureMultipleMembers(rtl: Boolean) {
        val state =
            emptyAppState(accounts = listOf(testAccount("Owner", "a".repeat(64))), activeAccountRef = "Owner")
                .withGroupReviewActorNpub()
        val members =
            listOf("Alice", "Bob").mapIndexed { index, name ->
                RecipientSearch.Candidate(
                    if (index == 0) REVIEW_FIRST_MEMBER_HEX else REVIEW_SECOND_MEMBER_HEX,
                    name,
                    if (index == 0) REVIEW_FIRST_MEMBER_NPUB else REVIEW_SECOND_MEMBER_NPUB,
                    searchProfile = UserProfileMetadataFfi(name, null, null, null, null, null, null),
                )
            }
        rule.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(fontScale = 1.5f) {
                    GroupMembersReviewDialog(
                        appState = state,
                        groups = listOf(MemberReviewGroup("garden", "Garden club")),
                        members = members,
                        busy = false,
                        onDismiss = {},
                        onConfirm = {},
                        confirmLabel = "Add members",
                        initialAdminIds = setOf(members.first().accountIdHex),
                        onInitialAdminChange = { _, _ -> },
                    )
                }
            }
        }
        rule.onAllNodesWithText("Add members").assertCountEquals(2)
        rule.onNodeWithContentDescription("Admin, Alice").assertIsOn()
        rule.onNodeWithContentDescription("Admin, Bob").performScrollTo().assertIsOff()
        val suffix = if (rtl) "rtl" else "ltr"
        rule.onNode(isDialog()).captureRoboImage("src/test/snapshots/group_member_review_multiple_$suffix.png")
    }

    /** Commit the accepted action and drain its exit before checking business callbacks. */
    private fun completeTerminalExit() {
        rule.runOnUiThread {
            androidx.compose.runtime.snapshots.Snapshot
                .sendApplyNotifications()
        }
        rule.mainClock.advanceTimeBy(400)
        rule.waitForIdle()
    }

    /** Renders the production member-review dialog in the requested visual variant. */
    private fun capture(
        name: String,
        dark: Boolean = false,
        amoled: Boolean = false,
        rtl: Boolean = false,
    ) {
        var submits = 0
        rule.showGroupMembersReview(dark, amoled, rtl) { submits += 1 }
        rule.assertGroupMembersReview(rtl)
        rule
            .onNodeWithTag("group.members.review.dialog")
            .captureRoboImage("src/test/snapshots/group_member_review_$name.png")
        rule.onNodeWithTag(SELECTED_MEMBERS_CONFIRM_TAG).assertIsDisplayed().performClick()
        completeTerminalExit()
        assertEquals(1, submits)
    }
}

/** A disposed UI node rejects the saved semantics action before invoking application code. */
private fun invokeDetachedReviewClick(action: () -> Boolean) {
    try {
        action()
    } catch (failure: IllegalStateException) {
        if (failure.message != "Cannot read CompositionLocal because the Modifier node is not currently attached.") {
            throw failure
        }
    }
}
