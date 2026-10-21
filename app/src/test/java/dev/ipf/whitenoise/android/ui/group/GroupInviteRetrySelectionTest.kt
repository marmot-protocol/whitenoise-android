package dev.ipf.whitenoise.android.ui.group

import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.GroupMutationResultFfi
import dev.ipf.marmotkit.SendAcceptDispositionFfi
import dev.ipf.marmotkit.SendMaintenanceDispositionFfi
import dev.ipf.marmotkit.SendSummaryFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.BoundedNpubCache
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.ConversationTimelineTestDraftPersistence
import dev.ipf.whitenoise.android.state.ConversationTimelineTestIds
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.conversationTimelineMemberSnapshot
import dev.ipf.whitenoise.android.state.conversationTimelineTestGroup
import dev.ipf.whitenoise.android.state.groupConversationSnapshotFixture
import dev.ipf.whitenoise.android.ui.chats.newchat.REVIEW_FIRST_MEMBER_HEX
import dev.ipf.whitenoise.android.ui.chats.newchat.REVIEW_FIRST_MEMBER_NPUB
import dev.ipf.whitenoise.android.ui.chats.newchat.SELECTED_MEMBERS_CONFIRM_TAG
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class GroupInviteRetrySelectionTest {
    @get:Rule val composeRule = createComposeRule()

    /** Retains the selected recipient and admin role through a pending invitation failure. */
    @Test
    fun pendingThenFailedInvitationKeepsTheRecipientAndAdminRoleForRetry() {
        val fixture = Fixture()
        val peerName =
            dev.ipf.whitenoise.android.core.IdentityFormatter
                .short(REVIEW_FIRST_MEMBER_NPUB, prefix = 10, suffix = 8)
        val controller = fixture.controller
        val reply = fixture.reply
        val calls = fixture.calls
        try {
            composeRule.setContent {
                WhiteNoiseTheme {
                    GroupDetailsScreen(fixture.state, controller, onBack = {}, onLeft = {}, autoOpenAddMember = true)
                }
            }
            selectAdmin(peerName)
            val adminLabel = context.getString(R.string.admin)
            composeRule.onNodeWithTag(SELECTED_MEMBERS_CONFIRM_TAG).performClick()
            composeRule.waitUntil { calls.size == 1 }
            composeRule.onNodeWithTag("group.members.review.dialog").assertDoesNotExist()
            composeRule
                .onNodeWithContentDescription(context.getString(R.string.review_selected_members))
                .assertIsNotEnabled()

            composeRule.runOnIdle { reply.complete(Unit) }
            composeRule.waitUntil { !controller.mutationInFlight }
            composeRule
                .onNodeWithContentDescription(context.getString(R.string.review_selected_members))
                .performClick()
            composeRule.onNodeWithContentDescription("$adminLabel, $peerName").performScrollTo().assertIsOn()
            composeRule.onNodeWithTag(SELECTED_MEMBERS_CONFIRM_TAG).performClick()
            composeRule.waitUntil { calls.size == 2 && !controller.mutationInFlight }
            assertEquals(List(2) { listOf(PEER) to listOf(PEER) }, calls)
        } finally {
            reply.complete(Unit)
            controller.onCleared()
        }
    }

    /** Closes a restored picker after its pending native invitation succeeds. */
    @Test
    fun restoredPickerClosesAfterThePendingNativeInvitationSucceeds() {
        val fixture = Fixture(succeed = true)
        val peerName =
            dev.ipf.whitenoise.android.core.IdentityFormatter
                .short(REVIEW_FIRST_MEMBER_NPUB, prefix = 10, suffix = 8)
        val restoration = StateRestorationTester(composeRule)
        try {
            restoration.setContent {
                WhiteNoiseTheme {
                    GroupDetailsScreen(fixture.state, fixture.controller, onBack = {}, onLeft = {})
                }
            }
            composeRule.onNodeWithTag("chat_info.add_people").performScrollTo().performClick()
            selectAdmin(peerName)
            composeRule.onNodeWithTag(SELECTED_MEMBERS_CONFIRM_TAG).performClick()
            composeRule.waitUntil { fixture.calls.size == 1 }
            restoration.emulateSavedInstanceStateRestore()
            composeRule.runOnIdle { fixture.reply.complete(Unit) }
            composeRule.waitUntil { !fixture.controller.mutationInFlight }
            composeRule.runOnIdle {
                assertNull(fixture.controller.lastMutationError)
                assertEquals(listOf(ConversationTimelineTestIds.ACCOUNT_ID, PEER), fixture.controller.group.admins)
                assertEquals(2, fixture.controller.members.size)
            }
            composeRule.onNodeWithTag("chat_info.add_people").performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithTag(SELECTED_MEMBERS_CONFIRM_TAG).assertDoesNotExist()
            assertEquals(listOf(listOf(PEER) to listOf(PEER)), fixture.calls)
        } finally {
            fixture.reply.complete(Unit)
            fixture.controller.onCleared()
        }
    }

    /** Selects the named candidate and marks the retained invitation role as admin. */
    private fun selectAdmin(publicPeerName: String) {
        composeRule.onNode(hasSetTextAction()).performTextInput(PEER)
        composeRule.onNodeWithContentDescription(context.getString(R.string.review_selected_members)).performClick()
        composeRule.onNodeWithTag("group.members.review.dialog").assertExists()
        composeRule
            .onAllNodes(hasText("Retry peer") and hasAnyAncestor(hasTestTag("group.members.review.dialog")))
            .assertCountEquals(0)
        val rawPeerName =
            dev.ipf.whitenoise.android.core.IdentityFormatter
                .short(PEER, prefix = 10, suffix = 8)
        composeRule
            .onAllNodes(hasText(rawPeerName) and hasAnyAncestor(hasTestTag("group.members.review.dialog")))
            .assertCountEquals(0)
        val adminLabel = context.getString(R.string.admin)
        composeRule.onNodeWithContentDescription("$adminLabel, $publicPeerName").performScrollTo().performClick()
        composeRule.onNodeWithContentDescription("$adminLabel, $publicPeerName").assertIsOn()
    }

    private class Fixture(
        succeed: Boolean = false,
    ) {
        val reply = CompletableDeferred<Unit>()
        val calls = CopyOnWriteArrayList<Pair<List<String>, List<String>>>()
        val state =
            WhiteNoiseAppState(
                context = context,
                draftStore = DraftStore(ConversationTimelineTestDraftPersistence()),
                accountIdHexResolver = { if (it == "alice") ConversationTimelineTestIds.ACCOUNT_ID else it },
                accounts =
                    listOf(
                        AccountSummaryFfi("alice", ConversationTimelineTestIds.ACCOUNT_ID, true, false, false, true),
                    ),
                activeAccountRef = "alice",
                profileReader = { null },
            )
        private var snapshot = groupConversationSnapshotFixture("Timeline group", admin = true)
        val controller =
            ConversationController(
                appState = state,
                initialGroup = conversationTimelineTestGroup(),
                initialMemberSnapshot = conversationTimelineMemberSnapshot(),
                groupSnapshotReader = { _, _ -> snapshot },
                initialAdminInviteWriter = { _, _, members, admins ->
                    calls += members.toList() to admins.toList()
                    reply.await()
                    if (!succeed) error("native commit rejected")
                    val details = snapshot.details
                    val self = details.members.single()
                    snapshot =
                        snapshot.copy(
                            details =
                                details.copy(
                                    group = details.group.copy(admins = details.group.admins + admins),
                                    members =
                                        details.members +
                                            self.copy(
                                                memberIdHex = PEER,
                                                account = null,
                                                local = false,
                                                isSelf = false,
                                                isAdmin = PEER in admins,
                                            ),
                                    mlsState = details.mlsState.copy(memberCount = 2u),
                                ),
                        )
                    GroupMutationResultFfi(
                        SendSummaryFfi(
                            1u,
                            emptyList(),
                            SendAcceptDispositionFfi.PUBLISHED,
                            SendMaintenanceDispositionFfi.READY,
                        ),
                        snapshot.details,
                        snapshot.managementState.copy(isLastAdmin = admins.isEmpty()),
                    )
                },
            )

        init {
            val field = WhiteNoiseAppState::class.java.getDeclaredField("npubs")
            field.isAccessible = true
            (field.get(state) as BoundedNpubCache).put(PEER, REVIEW_FIRST_MEMBER_NPUB)
            state.setContactNickname(PEER, "Retry peer")
        }
    }

    private companion object {
        val context: Context = ApplicationProvider.getApplicationContext()
        const val PEER = REVIEW_FIRST_MEMBER_HEX
    }
}
