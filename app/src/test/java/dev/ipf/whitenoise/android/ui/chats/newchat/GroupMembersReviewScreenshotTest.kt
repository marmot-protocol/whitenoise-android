package dev.ipf.whitenoise.android.ui.chats.newchat

import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.UserProfileMetadataFfi
import dev.ipf.whitenoise.android.core.AvatarImageLoader
import dev.ipf.whitenoise.android.core.RecipientSearch
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
        val state = emptyAppState()
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

    /** Keeps review actions and the final member reachable when the list overflows. */
    @Test
    fun overflowingReviewKeepsActionsAndLastMemberReachable() {
        val state = emptyAppState()
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
        val state = emptyAppState()
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

    /** Retires a removed member's role menu instead of transferring it to another row. */
    @Test
    fun removingMemberDoesNotMoveTheirOpenRoleMenuToAnotherPerson() {
        val state = emptyAppState()

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
                    onInitialAdminChange = { _, _ -> },
                )
            }
        }
        rule.onNodeWithContentDescription("Alice, Member").performClick()
        rule.onNodeWithText("Admin").assertExists()
        rule.runOnIdle { selected.removeAt(0) }
        rule.onNodeWithText("Admin").assertDoesNotExist()
        rule.onNodeWithContentDescription("Bob, Member").assertIsDisplayed()
    }

    /** Renders the production member-review dialog in the requested visual variant. */
    private fun capture(
        name: String,
        dark: Boolean = false,
        amoled: Boolean = false,
        rtl: Boolean = false,
    ) {
        val state = emptyAppState(accounts = listOf(testAccount("Owner", "a".repeat(64))), activeAccountRef = "Owner")
        val id = "b".repeat(64)
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
                        onInitialAdminChange = { _, _ -> },
                    )
                }
            }
        }
        rule.onNodeWithContentDescription("Review selected members").performClick()
        rule.onNode(isDialog()).assertExists()
        rule.onNodeWithText("Garden club").assertIsDisplayed()
        rule.onNodeWithText("alice").assertIsDisplayed()
        rule.onNodeWithText(npub).assertIsDisplayed()
        rule
            .onNode(hasText("Private Alice") and hasAnyAncestor(hasTestTag("group.members.review.member")))
            .assertDoesNotExist()
        val group = rule.onNodeWithTag("group.members.review.destination").fetchSemanticsNode().boundsInRoot
        val person = rule.onNodeWithTag("group.members.review.member").fetchSemanticsNode().boundsInRoot
        assertTrue(if (rtl) group.center.x > person.center.x else group.center.x < person.center.x)
        rule
            .onNodeWithTag("group.members.review.dialog")
            .captureRoboImage("src/test/snapshots/group_member_review_$name.png")
        rule.onNodeWithTag(SELECTED_MEMBERS_CONFIRM_TAG).assertIsDisplayed().performClick()
        assertEquals(1, submits)
    }
}
