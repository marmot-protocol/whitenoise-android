package dev.ipf.whitenoise.android.ui.group

import android.app.Application
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AppBlobEndpointFfi
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.AppProtocolProfileFfi
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.GroupMemberSnapshot
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.advanceLocalDeleteTestRuntime
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #3310 escape path on the real Group Details screen: a Leave that did not finish offers abandonment under the main
 * screen's error banner, for direct chats and groups, and a direct chat shows its own abandonment row.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class GroupDetailsAbandonmentTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()

    /** A direct chat's failed handover-and-leave shows the notice on the main screen, which opens the review. */
    @Test
    fun failedDmLeaveOnTheMainScreenOffersAbandonment() = assertFailedLeaveOffersAbandonment(isDm = true)

    /** A group's failed handover-and-leave shows the reset notice on the main screen, which opens the review. */
    @Test
    fun failedGroupLeaveOnTheMainScreenOffersReset() = assertFailedLeaveOffersAbandonment(isDm = false)

    /** A retained reset callback cannot report an attempt against a replacement runtime. */
    @Test
    fun staleResetReviewStaysUnattemptedAndDisablesMutationControls() {
        val (state, controller) = render(isDm = false)
        composeRule.runOnIdle {
            controller.recordMutationFailure(
                R.string.toast_couldnt_add_members,
                "PRIOR_FAILURE",
                IllegalStateException("Prior unrelated failure"),
            )
        }
        val priorError = controller.lastMutationError
        composeRule.onNodeWithTag("chat_info.reset").performScrollTo().performClick()
        val retained =
            composeRule
                .onNodeWithTag("group.abandon.confirm")
                .fetchSemanticsNode()
                .config[SemanticsActions.OnClick]
                .action!!
        composeRule.runOnIdle {
            state.advanceLocalDeleteTestRuntime()
            retained()
        }
        composeRule.waitForIdle()
        assertFalse(controller.mutationInFlight)
        assertEquals(priorError, controller.lastMutationError)
        composeRule.onNodeWithTag("group.abandon.error").assertDoesNotExist()
        composeRule.onNodeWithTag("group.abandon.confirm").assertDoesNotExist()
        composeRule.onNodeWithTag("group.abandon.cancel").assertDoesNotExist()
        composeRule.onNodeWithTag("chat_info.reset").assertDoesNotExist()
    }

    /** A direct chat's details list the Abandon chat on this device row in the light theme. */
    @Test
    fun dmAbandonRowLight() {
        render(isDm = true)
        composeRule
            .onNodeWithTag("chat_info.abandon")
            .performScrollTo()
            .assertIsDisplayed()
            .captureRoboImage("src/test/snapshots/group_details_dm_abandon_row_light.png")
    }

    /** A failed Leave raises the notice, any later success retires it, and other failures leave it alone. */
    @Test
    fun leaveBlockedNoticeClearsAfterAnyLaterSuccess() {
        val review = GroupAbandonmentReview()
        review.recordMutation(leave = true, succeeded = false)
        assertTrue(review.leaveBlocked)
        review.recordMutation(leave = false, succeeded = false)
        assertTrue("an unrelated failure keeps the escape path", review.leaveBlocked)
        review.recordMutation(leave = false, succeeded = true)
        assertFalse("a successful admin transfer retires the stale notice", review.leaveBlocked)
        review.recordMutation(leave = true, succeeded = false)
        review.recordMutation(leave = true, succeeded = true)
        assertFalse(review.leaveBlocked)
    }

    /**
     * During a notification early open the conversation's account differs from the active one, so no review can
     * open or confirm until they match, and a review opened for one account never confirms for another.
     */
    @Test
    fun abandonmentActsOnlyForTheConversationAccountWhileItIsActive() {
        assertNull("early open: no acting account yet", abandonmentAccount("notified", "active"))
        assertNull("no conversation account", abandonmentAccount(null, "active"))
        assertEquals("matching accounts act for that account", "same", abandonmentAccount("same", "same"))
        val review = GroupAbandonmentReview()
        review.open(
            GroupAbandonmentTarget(
                accountRef = "same",
                accountName = "Same",
                groupIdHex = "ab",
                conversationTitle = "Chat",
                isDm = true,
            ),
        )
        assertTrue(review.targets(abandonmentAccount("same", "same"), "AB"))
        assertFalse(
            "the confirm is refused once the conversation and active accounts diverge",
            review.targets(abandonmentAccount("same", "other"), "ab"),
        )
    }

    /**
     * Leaves as the sole admin through the one-successor handover. Without a native runtime the handover cannot
     * finish, which is the reported dead end, so the notice must appear on the main screen and open the review.
     */
    private fun assertFailedLeaveOffersAbandonment(isDm: Boolean) {
        render(isDm)
        composeRule
            .onNodeWithText(app.getString(if (isDm) R.string.leave_chat else R.string.leave_group))
            .performScrollTo()
            .performClick()
        composeRule.waitUntil(WAIT_MS) { composeRule.onAllNodes(isDialog()).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithTag("confirmation.confirm").performClick()
        composeRule.waitUntil(WAIT_MS) {
            composeRule.onAllNodes(hasTestTag(NOTICE_TAG)).fetchSemanticsNodes().isNotEmpty()
        }
        val hint = if (isDm) R.string.leave_blocked_abandon_hint_dm else R.string.leave_blocked_abandon_hint_group
        composeRule.onNodeWithText(app.getString(hint)).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("$NOTICE_TAG.abandon").performScrollTo().performClick()
        composeRule.onNodeWithTag("group.abandon.dialog").assertIsDisplayed()
        val title = if (isDm) R.string.chat_abandon_dialog_title else R.string.group_reset_dialog_title
        composeRule.onNodeWithText(app.getString(title)).assertIsDisplayed()
    }

    /** A disbanded group still listing this account withdraws Reset, so Details offers the local delete instead. */
    @Test
    fun disbandedGroupOffersLocalDeleteInsteadOfReset() {
        render(isDm = false, disbanded = true)
        composeRule
            .onNodeWithText(app.getString(R.string.chat_row_action_delete_group))
            .performScrollTo()
            .assertIsDisplayed()
        assertTrue(composeRule.onAllNodes(hasTestTag("chat_info.reset")).fetchSemanticsNodes().isEmpty())
    }

    /** Composes the real details screen for a chat this account administers alongside one other member. */
    private fun render(
        isDm: Boolean,
        disbanded: Boolean = false,
    ): Pair<WhiteNoiseAppState, ConversationController> {
        val appState =
            WhiteNoiseAppState(
                context = app,
                draftStore = DraftStore(NoDrafts),
                accountIdHexResolver = { SELF_HEX },
                accounts = listOf(AccountSummaryFfi(ACCOUNT_REF, SELF_HEX, true, false, false, true)),
                activeAccountRef = ACCOUNT_REF,
                profileReader = { null },
            )
        val controller =
            ConversationController(
                appState = appState,
                initialGroup = group(isDm).copy(disbanded = disbanded),
                initialMemberSnapshot = GroupMemberSnapshot(listOf(member(SELF_HEX, local = true), member(PEER_HEX))),
                initialIsDm = isDm,
            )
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                GroupDetailsScreen(
                    appState = appState,
                    controller = controller,
                    onBack = {},
                    onLeft = {},
                    onOpenSearch = {},
                )
            }
        }
        composeRule.waitForIdle()
        return appState to controller
    }

    /** A chat whose only admin is this account, where a direct chat has no name. */
    private fun group(isDm: Boolean): AppGroupRecordFfi =
        AppGroupRecordFfi(
            selfMembership = SelfMembershipFfi.MEMBER,
            groupIdHex = GROUP_HEX,
            protocolProfile = AppProtocolProfileFfi.LEGACY,
            profilePresent = false,
            endpoint = "endpoint",
            name = if (isDm) "" else "Weekend hikers",
            description = "",
            admins = listOf(SELF_HEX),
            relays = emptyList(),
            nostrGroupIdHex = "nostr-$GROUP_HEX",
            avatarUrl = null,
            avatarDim = null,
            avatarThumbhash = null,
            imageHashHex = null,
            encryptedMedia =
                AppGroupEncryptedMediaComponentFfi(
                    componentId = 0x8008u,
                    component = "marmot.group.encrypted-media.v1",
                    required = true,
                    version = EncryptedMediaVersionFfi.V1,
                    mediaFormat = "encrypted-media-v1",
                    allowedLocatorKinds = listOf("blossom-v1"),
                    defaultBlobEndpoints = listOf(AppBlobEndpointFfi("blossom-v1", "https://blossom.example")),
                ),
            archived = false,
            pendingConfirmation = false,
            unrecoverable = false,
            welcomerAccountIdHex = null,
            viaWelcomeMessageIdHex = null,
            disappearingMessageSecs = 0uL,
            leaveRequestPending = false,
            leaveRequestedAtMs = null,
            disbanding = false,
            disbanded = false,
            disbandRequest = null,
        )

    /** One roster entry, local for this account. */
    private fun member(
        memberId: String,
        local: Boolean = false,
    ) = AppGroupMemberRecordFfi(memberIdHex = memberId, account = if (local) ACCOUNT_REF else null, local = local)

    /** Drafts are irrelevant here, so persistence reads empty and discards writes. */
    private object NoDrafts : DraftPersistence {
        /** Reads no drafts. */
        override fun read(): Map<String, String> = emptyMap()

        /** Discards every draft write. */
        override fun write(
            key: String,
            value: String?,
        ) = Unit
    }

    private companion object {
        const val ACCOUNT_REF = "account-a"
        const val SELF_HEX = "self-a"
        const val PEER_HEX = "peer-b"
        const val GROUP_HEX = "group-a"
        const val NOTICE_TAG = "chat_info.leave_blocked"
        const val WAIT_MS = 5_000L
    }
}
