package dev.ipf.whitenoise.android.state

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.marmotkit.GroupMemberDetailsFfi
import dev.ipf.marmotkit.GroupRecoveryStatusFfi
import dev.ipf.marmotkit.GroupSystemEventFfi
import dev.ipf.marmotkit.GroupSystemEventProvenanceFfi
import dev.ipf.marmotkit.TimelineMessageChangeFfi
import dev.ipf.marmotkit.TimelineUpdateTriggerFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.diagnostics.PerformanceDiagnostics
import dev.ipf.whitenoise.android.ui.conversation.CONVERSATION_BOTTOM_BAR_TAG
import dev.ipf.whitenoise.android.ui.conversation.ConversationScreen
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
                assertPendingAboveComposer(pendingLabel(app, peer.memberIdHex))
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

    @Test
    fun pendingRemovalIsFullyAboveComposerWhenInsertedIntoVisibleTranscript() = heldVisibleRemoval(rowCount = 100)

    @Test
    fun emptyReadyConversationShowsPendingRemovalAboveComposer() = heldVisibleRemoval(rowCount = 0)

    private fun heldVisibleRemoval(rowCount: Int) =
        runBlocking {
            val controller = controller(rowCount)
            val app = controller.appState
            val peer = AppGroupMemberRecordFfi(ConversationTimelineTestIds.SENDER_ID, account = null, local = false)
            val release = CompletableDeferred<Unit>()
            val holder =
                async(start = CoroutineStart.UNDISPATCHED) {
                    app.withGroupCommitLock(ConversationTimelineTestIds.ACCOUNT_REF, controller.group.groupIdHex) {
                        release.await()
                    }
                }
            showConversation(controller)
            rule.waitForIdle()
            val removal = async(start = CoroutineStart.UNDISPATCHED) { controller.removeMember(peer) }
            try {
                assertPendingAboveComposer(pendingLabel(app, peer.memberIdHex))
                removal.cancelAndJoin()
                rule.onNodeWithText(pendingLabel(app, peer.memberIdHex)).assertDoesNotExist()
            } finally {
                removal.cancelAndJoin()
                release.complete(Unit)
                holder.await()
                controller.onCleared()
            }
        }

    private fun assertPendingAboveComposer(label: String) {
        val pending = rule.onNodeWithText(label).assertIsDisplayed().getUnclippedBoundsInRoot()
        val composer = rule.onNodeWithTag(CONVERSATION_BOTTOM_BAR_TAG).getUnclippedBoundsInRoot()
        assertTrue("The full pending row must clear the composer: $pending vs $composer", pending.bottom <= composer.top)
        assertTrue("The pending row must remain inside the screen", pending.top.value >= 0f)
    }

    @Test
    fun historyAndRefreshDoNotEmitLiveMembershipLatency() =
        runBlocking {
            PerformanceDiagnostics.stop()
            assertTrue(PerformanceDiagnostics.start().active)
            val controller = controller(initialMembership = true)
            try {
                assertEquals(0, membershipArrivalCount())
                controller.applyTimelinePage(timelinePage(remoteEvent()), replaceWindow = true, updatePagination = true)
                assertEquals(0, membershipArrivalCount())
                controller.testApplyLiveTimelineChangesAndRegisterStreams(
                    listOf(
                        TimelineMessageChangeFfi.Upsert(
                            TimelineUpdateTriggerFfi.SNAPSHOT_REFRESH,
                            remoteEvent().copy(messageIdHex = "refresh-event"),
                        ),
                    ),
                )
                assertEquals(0, membershipArrivalCount())
                controller.testApplyLiveTimelineChangesAndRegisterStreams(
                    listOf(
                        TimelineMessageChangeFfi.Upsert(
                            TimelineUpdateTriggerFfi.GROUP_SYSTEM,
                            remoteEvent().copy(
                                messageIdHex = "new-live-event",
                            ),
                        ),
                    ),
                )
                assertEquals(1, membershipArrivalCount())
                controller.testApplyLiveTimelineChangesAndRegisterStreams(
                    listOf(
                        TimelineMessageChangeFfi.Upsert(
                            TimelineUpdateTriggerFfi.GROUP_SYSTEM,
                            remoteEvent().copy(
                                messageIdHex = "new-live-event",
                            ),
                        ),
                    ),
                )
                assertEquals(1, membershipArrivalCount())
                controller.testApplyLiveTimelineChangesAndRegisterStreams(
                    listOf(
                        TimelineMessageChangeFfi.Upsert(
                            TimelineUpdateTriggerFfi.NEW_MESSAGE,
                            remoteEvent().copy(messageIdHex = "compat-live-event"),
                        ),
                    ),
                )
                assertEquals(2, membershipArrivalCount())
            } finally {
                controller.onCleared()
                PerformanceDiagnostics.stop()
            }
        }

    private fun membershipArrivalCount() =
        PerformanceDiagnostics.exportLines().count {
            "op=group_membership_projection" in it && "phase=timeline_subscription_received" in it
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

    private fun controller(
        rowCount: Int = 100,
        initialMembership: Boolean = false,
    ): ConversationController {
        val group = conversationTimelineTestGroup()
        val subscription =
            ScriptedConversationTimelineSubscription(
                timelinePage(
                    *(
                        (1..rowCount).map { timelineRecord("row-$it", it.toULong()) } +
                            if (initialMembership) listOf(remoteEvent()) else emptyList()
                    ).toTypedArray(),
                ),
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
                groupRecoveryStatusReader = { _, groupIdHex ->
                    GroupRecoveryStatusFfi(
                        groupIdHex = groupIdHex,
                        automaticRecoveryFailed = false,
                        pendingReinvites = 0u,
                        failedReinvites = 0u,
                        rejoinInvitations = emptyList(),
                    )
                },
                startOnConstruction = true,
            )
        awaitConversationCondition {
            controller.memberRosterState == GroupRosterLoadState.READY &&
                controller.timeline.size == rowCount + (if (initialMembership) 1 else 0) &&
                controller.hasPublishedAuthoritativeTimeline &&
                controller.groupRecoveryStatus != null
        }
        assertTrue("The fixture must have a healthy recovery read", !controller.groupRecoveryReadFailed)
        return controller
    }

    private fun pendingLabel(
        app: WhiteNoiseAppState,
        memberId: String,
    ): String {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return context.getString(R.string.member_removal_pending, app.networkDisplayName(memberId))
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
