package dev.ipf.whitenoise.android.ui.chats.newchat

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasAnyAncestor
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
import dev.ipf.whitenoise.android.core.AvatarImageLoader
import dev.ipf.whitenoise.android.core.RecipientSearch
import dev.ipf.whitenoise.android.state.advanceLocalDeleteTestRuntime
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
class GroupMembersReviewScreenshotTest {
    @get:Rule val rule = createComposeRule()

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
        val state = emptyAppState(accounts = listOf(testAccount("Owner", "a".repeat(64))), activeAccountRef = "Owner")
        rule.setContent {
            WhiteNoiseTheme {
                GroupMembersReviewDialog(
                    appState = state,
                    groups = listOf(MemberReviewGroup("group", "Garden club")),
                    members = listOf(RecipientSearch.Candidate("b".repeat(64), "Alice", "npub1alice")),
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
    }

    /** A callback retained by the old dialog cannot submit after the same account gets a new runtime. */
    @Test
    fun retainedReviewCallbackRejectsRuntimeReplacement() {
        val state = emptyAppState(accounts = listOf(testAccount("Owner", "a".repeat(64))), activeAccountRef = "Owner")
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
            assertEquals(1, submitted)
        } else {
            rule.onNodeWithText("Cancel").performClick()
            rule.waitForIdle()
            rule.runOnIdle { visible.value = true }
            rule.waitForIdle()
            rule.runOnIdle { invokeDetachedReviewClick(retained) }
            assertEquals(0, submitted)
            rule.onNodeWithTag(SELECTED_MEMBERS_CONFIRM_TAG).performClick()
            assertEquals(1, submitted)
        }
    }

    /** Valid public hex remains identifiable and copyable without a profile or native npub formatter. */
    @Test
    fun missingMetadataKeepsActorAndMemberPublicHexCopyable() {
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
                    confirmLabel = "Add members",
                )
            }
        }
        rule.onNodeWithContentDescription("Public key (hex), $actor").assertExists()
        rule.onNodeWithContentDescription("Public key (hex), $member").performScrollTo().performClick()
        val clipboard =
            ApplicationProvider
                .getApplicationContext<Context>()
                .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertEquals(
            member,
            clipboard.primaryClip!!
                .getItemAt(0)
                .text
                .toString(),
        )
        rule.onNodeWithText("1 group").assertExists()
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
        val state = emptyAppState(accounts = listOf(testAccount("Owner", "a".repeat(64))), activeAccountRef = "Owner")
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

    /** Public identifiers distinguish same-named destinations and copy complete bytes rather than an abbreviation. */
    @Test
    fun duplicateGroupNamesKeepDistinctCopyableIdentifiers() {
        val state = emptyAppState(accounts = listOf(testAccount("Owner", "a".repeat(64))), activeAccountRef = "Owner")
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
                    confirmLabel = "Add members",
                )
            }
        }
        rule.onNodeWithContentDescription("Group ID, garden-one").assertExists()
        rule.onNodeWithContentDescription("Group ID, garden-two").performScrollTo().performClick()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertEquals(
            "garden-two",
            clipboard.primaryClip
                ?.getItemAt(0)
                ?.text
                ?.toString(),
        )
        rule.onNodeWithTag(SELECTED_MEMBERS_CONFIRM_TAG).assertIsDisplayed()
    }

    /** Keeps review actions and the final member reachable when the list overflows. */
    @Test
    fun overflowingReviewKeepsActionsAndLastMemberReachable() {
        val state = emptyAppState(accounts = listOf(testAccount("Owner", "a".repeat(64))), activeAccountRef = "Owner")
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
        assertEquals(1, submits)
    }

    /** Retains selection on cancellation and closes review when its destination changes. */
    @Test
    fun cancellingPreservesSelectionAndChangingGroupClosesReview() {
        val state = emptyAppState(accounts = listOf(testAccount("Owner", "a".repeat(64))), activeAccountRef = "Owner")
        val selected = mutableStateListOf(RecipientSearch.Candidate("b".repeat(64), "Alice", "npub1alice"))
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
                )
            }
        }
        rule.onNodeWithContentDescription("Review selected members").performClick()
        rule.onNodeWithText("Cancel").performClick()
        rule.onNodeWithTag("group.members.review.dialog").assertDoesNotExist()
        assertEquals(1, selected.size)
        assertEquals(0, submits)
        rule.onNodeWithContentDescription("Review selected members").performClick()
        rule.runOnIdle { group.value = MemberReviewGroup("orchard", "Orchard club") }
        rule.onNodeWithTag("group.members.review.dialog").assertDoesNotExist()
        assertEquals(0, submits)
    }

    /** Changing one member's role never transfers that privilege to another person after removal. */
    @Test
    fun removingMemberDoesNotMoveTheirAdminRoleToAnotherPerson() {
        val state = emptyAppState(accounts = listOf(testAccount("Owner", "a".repeat(64))), activeAccountRef = "Owner")

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
        val state = emptyAppState(accounts = listOf(testAccount("Owner", "a".repeat(64))), activeAccountRef = "Owner")
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
        assertEquals(1, dismisses)
    }

    /** An unresolved public name shows exactly one shortened npub, with person before destination. */
    @Test
    fun unnamedMemberHasOneAbbreviatedIdentity() {
        val state = emptyAppState(accounts = listOf(testAccount("Owner", "a".repeat(64))), activeAccountRef = "Owner")
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
                    confirmLabel = "Add members",
                    onRemove = {},
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
        assertTrue(person.right <= group.left)
        rule
            .onNodeWithTag("group.members.review.dialog")
            .captureRoboImage("src/test/snapshots/group_member_review_unnamed.png")
    }

    /** Separates each member's own role and remove controls at large font sizes. */
    @Test
    @Config(qualifiers = "en-w360dp-h1280dp-mdpi")
    fun multipleMembersLarge() = captureMultipleMembers(false)

    /** Mirrors the paired identity columns without losing per-member control grouping. */
    @Test
    @Config(qualifiers = "ar-rEG-ldrtl-w360dp-h1280dp-mdpi")
    fun multipleMembersLargeRtl() = captureMultipleMembers(true)

    private fun captureMultipleMembers(rtl: Boolean) {
        val state = emptyAppState(accounts = listOf(testAccount("Owner", "a".repeat(64))), activeAccountRef = "Owner")
        val members =
            listOf("Alice", "Bob").mapIndexed { index, name ->
                RecipientSearch.Candidate(
                    (index + 1).toString(16).padStart(64, '0'),
                    name,
                    "npub1member$index",
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
                        onRemove = {},
                        initialAdminIds = setOf(members.first().accountIdHex),
                        onInitialAdminChange = { _, _ -> },
                    )
                }
            }
        }
        rule.onNodeWithContentDescription("Admin, Alice").assertIsOn()
        rule.onNodeWithContentDescription("Admin, Bob").performScrollTo().assertIsOff()
        val suffix = if (rtl) "rtl" else "ltr"
        rule.onNode(isDialog()).captureRoboImage("src/test/snapshots/group_member_review_multiple_$suffix.png")
    }

    /** Renders the production member-review dialog in the requested visual variant. */
    private fun capture(
        name: String,
        dark: Boolean = false,
        amoled: Boolean = false,
        rtl: Boolean = false,
    ) {
        val state = emptyAppState(accounts = listOf(testAccount("Owner", "a".repeat(64))), activeAccountRef = "Owner")
        val id = "202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f"
        val npub = "npub1yqsjygeyy5nzw2pf9g4jctfw9ucrzv3nxs6nvdec8yark0pa8clst3m4tg"
        val pictureUrl = "https://example.com/alice-review.png"
        val memberImage = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff008080.toInt()) }
        val groupImage = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff1565c0.toInt()) }
        AvatarImageLoader.putCached(pictureUrl, memberImage.asImageBitmap())
        val member =
            RecipientSearch.Candidate(
                id,
                "Private Alice",
                npub,
                searchProfile = UserProfileMetadataFfi("alice", "Alice Public", null, pictureUrl, null, null, null),
            )
        val selected = mutableStateListOf(member)
        var submits = 0
        val admins = mutableStateListOf<String>()
        rule.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = dark, amoled = amoled, fontScale = if (rtl) 2f else 1f) {
                    ContactPickerScreen(
                        appState = state,
                        title = "Choose people",
                        selected = selected,
                        onBack = {},
                        onConfirm = { submits += 1 },
                        confirmLabel = "Add member",
                        reviewGroup = MemberReviewGroup("garden", "Garden club", image = groupImage.asImageBitmap()),
                        initialAdminIds = admins.toSet(),
                        onInitialAdminChange = { person, admin ->
                            if (admin) admins.add(person.accountIdHex) else admins.remove(person.accountIdHex)
                        },
                    )
                }
            }
        }
        rule.onNodeWithContentDescription("Review selected members").performClick()
        rule.onNode(isDialog()).assertExists()
        rule.onNodeWithTag("group.action.acting_account").assertExists()
        assertPublicReviewIdentity(npub, rtl)
        rule
            .onNodeWithTag("group.members.review.dialog")
            .captureRoboImage("src/test/snapshots/group_member_review_$name.png")
        rule.onNodeWithTag(SELECTED_MEMBERS_CONFIRM_TAG).assertIsDisplayed().performClick()
        assertEquals(1, submits)
    }

    /** Verifies public identity, privacy and logical column order before the golden is captured. */
    private fun assertPublicReviewIdentity(
        npub: String,
        rtl: Boolean,
    ) {
        rule.onNodeWithText("Garden club").assertIsDisplayed()
        rule.onNodeWithText("alice").performScrollTo().assertIsDisplayed()
        rule
            .onNodeWithText(
                dev.ipf.whitenoise.android.core.IdentityFormatter
                    .short(npub, prefix = 10, suffix = 8),
            ).performScrollTo()
            .assertIsDisplayed()
        rule
            .onNode(hasText("Private Alice") and hasAnyAncestor(hasTestTag("group.members.review.member")))
            .assertDoesNotExist()
        val group = rule.onNodeWithTag("group.members.review.destination").fetchSemanticsNode().boundsInRoot
        val person = rule.onNodeWithTag("group.members.review.member").fetchSemanticsNode().boundsInRoot
        assertTrue(if (rtl) person.center.x > group.center.x else person.center.x < group.center.x)
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
