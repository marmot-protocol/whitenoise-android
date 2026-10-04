package dev.ipf.whitenoise.android.state

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.marmotkit.GroupMemberDetailsFfi
import dev.ipf.marmotkit.GroupSystemEventFfi
import dev.ipf.marmotkit.GroupSystemEventProvenanceFfi
import dev.ipf.marmotkit.TimelineMessageChangeFfi
import dev.ipf.marmotkit.TimelineUpdateTriggerFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.conversation.ConversationScreen
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Production controller and transcript retain one local action across the details destination. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class ConversationPendingMembershipIntegrationTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun returningFromDetailsShowsHeldRemovalAndRemoteEventCannotSettleIt() =
        runBlocking {
            val controller = controller()
            val app = controller.appState
            val group = controller.group
            val peer = AppGroupMemberRecordFfi(ConversationTimelineTestIds.SENDER_ID, account = null, local = false)
            val release = CompletableDeferred<Unit>()
            val holder =
                async(start = CoroutineStart.UNDISPATCHED) {
                    app.withGroupCommitLock(
                        ConversationTimelineTestIds.ACCOUNT_REF,
                        group.groupIdHex,
                    ) { release.await() }
                }
            showConversation(controller)
            rule.onNodeWithText("body-row-100").assertIsDisplayed()
            rule.onNodeWithText(group.name).performClick()
            val removal = async(start = CoroutineStart.UNDISPATCHED) { controller.removeMember(peer) }
            val identity = requireNotNull(controller.pendingMembershipActivity).id
            try {
                val context = ApplicationProvider.getApplicationContext<Context>()
                rule.onNodeWithContentDescription(context.getString(R.string.back)).performClick()
                rule.onNodeWithText(pendingLabel(app, peer.memberIdHex)).assertIsDisplayed()
                val changes =
                    listOf(
                        TimelineMessageChangeFfi.Upsert(
                            TimelineUpdateTriggerFfi.NEW_MESSAGE,
                            remoteEvent(),
                        ),
                    )
                rule.runOnIdle {
                    controller.testApplyLiveTimelineChangesAndRegisterStreams(changes)
                    controller.testApplyLiveTimelineChangesAndRegisterStreams(changes)
                }
                assertEquals(identity, controller.pendingMembershipActivity?.id)
                assertEquals(1, controller.timeline.count { it.record.messageIdHex == REMOTE_ROW_ID })
                assertEquals("row-100", controller.timeline[controller.timeline.lastIndex - 1].record.messageIdHex)
                removal.cancelAndJoin()
                rule.onNodeWithText(pendingLabel(app, peer.memberIdHex)).assertDoesNotExist()
                assertNull(controller.pendingMembershipActivity)
                assertEquals(1, controller.timeline.count { it.record.messageIdHex == REMOTE_ROW_ID })
            } finally {
                removal.cancelAndJoin()
                release.complete(Unit)
                holder.await()
                controller.onCleared()
            }
        }

    private fun showConversation(controller: ConversationController) {
        rule.setContent {
            WhiteNoiseTheme {
                ConversationScreen(
                    appState = controller.appState,
                    chat =
                        ChatListItem(
                            group = controller.group,
                            latest = null,
                            otherMemberAccount = null,
                            memberCount = 2,
                            memberSnapshot = controller.initialMemberSnapshot,
                            projection =
                                notificationChatListRow().copy(
                                    unreadCount = 0uL,
                                    hasUnread = false,
                                    firstUnreadMessageIdHex = null,
                                    lastReadMessageIdHex = "row-100",
                                    lastReadTimelineAt = 100uL,
                                ),
                        ),
                    controller = controller,
                    onBack = {},
                )
            }
        }
    }

    private fun controller(): ConversationController {
        val group = conversationTimelineTestGroup()
        val subscription =
            ScriptedConversationTimelineSubscription(
                timelinePage(*(1..100).map { timelineRecord("row-$it", it.toULong()) }.toTypedArray()),
            )
        val scripted = ScriptedConversationLiveSubscriptions(listOf(subscription), group)
        val roster =
            conversationTimelineGroupRoster().let {
                it.copy(
                    memberCount = 2u,
                    members =
                        it.members +
                            GroupMemberDetailsFfi(
                                memberIdHex = ConversationTimelineTestIds.SENDER_ID,
                                account = null,
                                local = false,
                                isAdmin = false,
                                isSelf = false,
                                npub = "npub-peer",
                                displayName = "Peer",
                            ),
                )
            }
        val controller =
            ConversationController(
                appState = conversationTimelineTestAppState(scripted.subscriptions),
                initialGroup = group,
                initialMemberSnapshot = conversationTimelineMemberSnapshot(),
                groupRosterReader = { _, _ -> roster },
                startOnConstruction = true,
            )
        awaitConversationCondition {
            controller.memberRosterState == GroupRosterLoadState.READY && controller.timeline.size == 100
        }
        return controller
    }

    private fun pendingLabel(
        app: WhiteNoiseAppState,
        memberId: String,
    ): String {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return context.getString(R.string.remove_member_named, app.networkDisplayName(memberId)) +
            " · " + context.getString(R.string.message_status_pending)
    }

    private fun remoteEvent() =
        timelineRecord(REMOTE_ROW_ID, 101uL).copy(
            sourceMessageIdHex = null,
            direction = "system",
            kind = 1210uL,
            groupSystem =
                GroupSystemEventFfi(
                    provenance = GroupSystemEventProvenanceFfi.AUTHENTICATED_GROUP_STATE,
                    actorDisplayName = "Other admin",
                    subjectDisplayName = "Other peer",
                    systemType = "member_added",
                    text = "member added",
                    actorAccountIdHex = "dd".repeat(32),
                    subjectAccountIdHex = "ee".repeat(32),
                    name = null,
                    oldName = null,
                    oldRetentionSeconds = null,
                    newRetentionSeconds = null,
                ),
        )

    private companion object {
        const val REMOTE_ROW_ID = "remote-member-event"
    }
}
