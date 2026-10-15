package dev.ipf.whitenoise.android.ui.group

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.ConversationTimelineTestDraftPersistence
import dev.ipf.whitenoise.android.state.ConversationTimelineTestIds
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.conversationTimelineMemberSnapshot
import dev.ipf.whitenoise.android.state.conversationTimelineTestGroup
import dev.ipf.whitenoise.android.state.groupConversationSnapshotFixture
import dev.ipf.whitenoise.android.ui.chats.newchat.SELECTED_MEMBERS_CONFIRM_TAG
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class GroupInviteRetrySelectionTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun pendingThenFailedInvitationKeepsTheRecipientAndAdminRoleForRetry() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val peer = "cc".repeat(32)
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
        state.setContactNickname(peer, "Retry peer")
        val controller =
            ConversationController(
                appState = state,
                initialGroup = conversationTimelineTestGroup(),
                initialMemberSnapshot = conversationTimelineMemberSnapshot(),
                groupSnapshotReader = { _, _ -> groupConversationSnapshotFixture("Timeline group", admin = true) },
                initialAdminInviteWriter = { _, _, members, admins ->
                    calls += members.toList() to admins.toList()
                    reply.await()
                    error("native commit rejected")
                },
            )
        try {
            composeRule.setContent {
                WhiteNoiseTheme {
                    GroupDetailsScreen(state, controller, onBack = {}, onLeft = {}, autoOpenAddMember = true)
                }
            }
            composeRule.onNode(hasSetTextAction()).performTextInput(peer)
            composeRule.onNodeWithContentDescription(context.getString(R.string.review_selected_members)).performClick()
            val memberLabel = context.getString(R.string.member)
            val adminLabel = context.getString(R.string.admin)
            composeRule.onNodeWithContentDescription("Retry peer, $memberLabel").performClick()
            composeRule.onNodeWithText(adminLabel).performClick()
            composeRule.onNodeWithTag(SELECTED_MEMBERS_CONFIRM_TAG).performClick()
            composeRule.waitUntil { calls.size == 1 }
            composeRule.onNodeWithContentDescription("Retry peer, $adminLabel").assertIsNotEnabled()
            composeRule.onNodeWithTag(SELECTED_MEMBERS_CONFIRM_TAG).assertIsNotEnabled()

            composeRule.runOnIdle { reply.complete(Unit) }
            composeRule.waitUntil { !controller.mutationInFlight }
            composeRule.onNodeWithContentDescription("Retry peer, $adminLabel").assertIsDisplayed()
            composeRule.onNodeWithTag(SELECTED_MEMBERS_CONFIRM_TAG).performClick()
            composeRule.waitUntil { calls.size == 2 && !controller.mutationInFlight }
            assertEquals(List(2) { listOf(peer) to listOf(peer) }, calls)
        } finally {
            reply.complete(Unit)
            controller.onCleared()
        }
    }
}
