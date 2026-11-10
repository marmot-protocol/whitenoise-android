package dev.ipf.whitenoise.android.ui.chats.newchat

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.UserProfileMetadataFfi
import dev.ipf.whitenoise.android.core.AvatarImageLoader
import dev.ipf.whitenoise.android.core.IdentityFormatter
import dev.ipf.whitenoise.android.core.RecipientSearch
import dev.ipf.whitenoise.android.state.BoundedNpubCache
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.share.emptyAppState
import dev.ipf.whitenoise.android.ui.share.testAccount
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/** Existing synthetic public key used by the group header fixtures; no native formatter is needed. */
internal fun WhiteNoiseAppState.withGroupReviewActorNpub(): WhiteNoiseAppState {
    val field = WhiteNoiseAppState::class.java.getDeclaredField("npubs")
    field.isAccessible = true
    val cache = field.get(this) as BoundedNpubCache
    cache.put(GROUP_REVIEW_ACTOR_HEX, GROUP_REVIEW_ACTOR_NPUB)
    // Match the shared picker fixture's a0 public key as well.
    cache.put("a0".repeat(32), "npub15zs2pg9q5zs2pg9q5zs2pg9q5zs2pg9q5zs2pg9q5zs2pg9q5zsqun85q9")
    return this
}

internal const val GROUP_REVIEW_ACTOR_HEX = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
internal const val GROUP_REVIEW_ACTOR_NPUB = "npub1424242424242424242424242424242424242424242424242424qamrcaj"

internal const val REVIEW_FIRST_MEMBER_HEX = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
internal const val REVIEW_FIRST_MEMBER_NPUB = "npub1qqqsyqcyq5rqwzqfpg9scrgwpugpzysnzs23v9ccrydpk8qarc0st5hsmq"
internal const val REVIEW_SECOND_MEMBER_HEX = "202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f"
internal const val REVIEW_SECOND_MEMBER_NPUB = "npub1yqsjygeyy5nzw2pf9g4jctfw9ucrzv3nxs6nvdec8yark0pa8clst3m4tg"

/** Opens the existing picker and review with the same synthetic identity, avatars and theme. */
internal fun ComposeContentTestRule.showGroupMembersReview(
    dark: Boolean,
    amoled: Boolean,
    rtl: Boolean,
    onConfirm: () -> Unit,
) {
    val state =
        emptyAppState(accounts = listOf(testAccount("Owner", "a".repeat(64))), activeAccountRef = "Owner")
            .withGroupReviewActorNpub()
    val selected = mutableStateListOf(groupReviewMember(rtl))
    val admins = mutableStateListOf<String>()
    val groupImage = groupReviewAvatar(0xff1565c0.toInt())
    setContent {
        CompositionLocalProvider(
            LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
        ) {
            WhiteNoiseTheme(darkTheme = dark, amoled = amoled, fontScale = if (rtl) 2f else 1f) {
                ContactPickerScreen(
                    appState = state,
                    title = "Choose people",
                    selected = selected,
                    onBack = {},
                    onConfirm = onConfirm,
                    confirmLabel = "Add member",
                    reviewGroup = MemberReviewGroup("garden", "Garden club", image = groupImage),
                    initialAdminIds = admins.toSet(),
                    onInitialAdminChange = { person, admin ->
                        if (admin) admins.add(person.accountIdHex) else admins.remove(person.accountIdHex)
                    },
                )
            }
        }
    }
    onNodeWithContentDescription("Review selected members").performClick()
}

private fun groupReviewMember(rtl: Boolean): RecipientSearch.Candidate {
    val pictureUrl = "https://example.com/alice-review.png"
    AvatarImageLoader.putCached(pictureUrl, groupReviewAvatar(0xff008080.toInt()))
    return RecipientSearch.Candidate(
        REVIEW_SECOND_MEMBER_HEX,
        "Private Alice",
        REVIEW_SECOND_MEMBER_NPUB,
        searchProfile =
            UserProfileMetadataFfi(
                if (rtl) "Alice Public with a long profile name" else "alice",
                "Alice Public",
                null,
                pictureUrl,
                null,
                null,
                null,
            ),
    )
}

private fun groupReviewAvatar(color: Int): ImageBitmap {
    val bitmap = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
    bitmap.eraseColor(color)
    return bitmap.asImageBitmap()
}

/** Retains the identity, privacy, geometry, action and full-copy checks for the owning screenshots. */
internal fun ComposeContentTestRule.assertGroupMembersReview(rtl: Boolean) {
    onNode(isDialog()).assertExists()
    onNodeWithTag("group.action.acting_account").assertExists()
    assertPublicReviewIdentity(REVIEW_SECOND_MEMBER_NPUB, rtl)
    onAllNodesWithText("Remove member").assertCountEquals(0)
    onAllNodesWithText("Public key").assertCountEquals(0)
    onNodeWithTag(SELECTED_MEMBERS_CONFIRM_TAG).assertIsDisplayed()
    if (!rtl) {
        val bounds = onNodeWithTag("group.members.review.dialog").fetchSemanticsNode().boundsInRoot
        assertTrue("Single-member review should leave comfortable room on a 780dp screen", bounds.height <= 460f)
        onAllNodesWithText("Add member").assertCountEquals(2)
    }
    // The actor is a picture announced by role and name; it still copies the full npub.
    assertReviewNpubCopy(onNodeWithContentDescription("Acting account, ", substring = true), GROUP_REVIEW_ACTOR_NPUB)
    assertReviewNpubCopy(
        onNodeWithContentDescription(", Member, $REVIEW_SECOND_MEMBER_NPUB", substring = true),
        REVIEW_SECOND_MEMBER_NPUB,
        scroll = true,
    )
}

/** Checks a 48dp target that announces a Copy action and puts exactly [npub] on the clipboard when tapped. */
private fun assertReviewNpubCopy(
    node: SemanticsNodeInteraction,
    npub: String,
    scroll: Boolean = false,
) {
    if (scroll) node.performScrollTo()
    node.assert(
        SemanticsMatcher("announces Copy") { it.config.getOrNull(SemanticsActions.OnClick)?.label == "Copy" },
    )
    val bounds = node.fetchSemanticsNode().boundsInRoot
    assertTrue(bounds.height >= 48f && bounds.width >= 48f)
    node.performClick()
    val clipboard =
        ApplicationProvider
            .getApplicationContext<Context>()
            .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    assertEquals(
        npub,
        clipboard.primaryClip!!
            .getItemAt(0)
            .text
            .toString(),
    )
}

/** Verifies public identity, privacy and destination-above-members order before the golden is captured. */
private fun ComposeContentTestRule.assertPublicReviewIdentity(
    npub: String,
    rtl: Boolean,
) {
    onNodeWithText("to Garden club").assertIsDisplayed()
    val name = if (rtl) "Alice Public with a long profile name" else "alice"
    onNodeWithText(name).performScrollTo().assertIsDisplayed()
    onNodeWithTag("group.members.review.member").assert(hasContentDescription("$name, Member, $npub"))
    onNodeWithText(IdentityFormatter.short(npub, prefix = 10, suffix = 8)).performScrollTo().assertIsDisplayed()
    onNode(hasText("Private Alice") and hasAnyAncestor(hasTestTag("group.members.review.member"))).assertDoesNotExist()
    val group = onNodeWithTag("group.members.review.destination").fetchSemanticsNode().boundsInRoot
    val person = onNodeWithTag("group.members.review.member").fetchSemanticsNode().boundsInRoot
    assertTrue("The destination heads the member rows", group.bottom <= person.top)
}
