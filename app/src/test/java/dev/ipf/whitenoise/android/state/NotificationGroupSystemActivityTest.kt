package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.ChatListUpdateTriggerFfi
import dev.ipf.marmotkit.GroupSystemEventFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.NotificationTriggerFfi
import dev.ipf.marmotkit.RuntimeProjectionUpdateFfi
import dev.ipf.marmotkit.TimelineMessageChangeFfi
import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.marmotkit.TimelineProjectionUpdateFfi
import dev.ipf.marmotkit.TimelineReactionSummaryFfi
import dev.ipf.marmotkit.TimelineUpdateTriggerFfi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationGroupSystemActivityTest {
    private val me = "aa".repeat(32)
    private val actor = "bb".repeat(32)
    private val other = "cc".repeat(32)

    @Test
    fun membershipProjectionProducesSelfTargetedAdminNotification() {
        val updates =
            groupSystemActivityNotificationUpdates(
                projection(record(systemType = "admin_added", actor = actor, subject = me)),
            )

        assertEquals(1, updates.size)
        val update = updates.single()
        assertEquals(NotificationTriggerFfi.NEW_MESSAGE, update.trigger)
        assertEquals("account-a", update.accountRef)
        assertEquals(me, update.accountIdHex)
        assertEquals("group-1", update.groupIdHex)
        assertEquals("msg-admin_added", update.messageIdHex)
        assertEquals(actor, update.sender.accountIdHex)
        assertEquals(me, update.receiver.accountIdHex)
        assertEquals(false, update.isDm)
        assertEquals(false, update.isFromSelf)
        assertEquals(42_000L, update.timestampMs)
        assertEquals("Someone was made an admin", update.previewText)
    }

    @Test
    fun membershipProjectionDoesNotNotifyWhenCurrentUserIsTheActor() {
        val updates =
            groupSystemActivityNotificationUpdates(
                projection(record(systemType = "admin_added", actor = me, subject = me)),
            )

        assertTrue(updates.isEmpty())
    }

    @Test
    fun membershipProjectionDoesNotNotifyWhenAnotherMemberIsTheSubject() {
        val updates =
            groupSystemActivityNotificationUpdates(
                projection(record(systemType = "admin_removed", actor = actor, subject = other)),
            )

        assertTrue(updates.isEmpty())
    }

    @Test
    fun nonMembershipChatListTriggerDoesNotSynthesizeNotification() {
        val updates =
            groupSystemActivityNotificationUpdates(
                projection(
                    record(systemType = "admin_added", actor = actor, subject = me),
                    trigger = ChatListUpdateTriggerFfi.NEW_LAST_MESSAGE,
                ),
            )

        assertTrue(updates.isEmpty())
    }

    @Test
    fun duplicateProjectionRecordsProduceOneNotification() {
        val record = record(systemType = "member_removed", actor = actor, subject = me)
        val updates =
            groupSystemActivityNotificationUpdates(
                projection(record = record, messages = listOf(record)),
            )

        assertEquals(1, updates.size)
        assertEquals("msg-member_removed", updates.single().messageIdHex)
    }

    private fun projection(
        record: TimelineMessageRecordFfi,
        messages: List<TimelineMessageRecordFfi> = emptyList(),
        trigger: ChatListUpdateTriggerFfi = ChatListUpdateTriggerFfi.MEMBERSHIP_CHANGED,
    ) = RuntimeProjectionUpdateFfi(
        accountIdHex = me,
        accountLabel = "account-a",
        update =
            TimelineProjectionUpdateFfi(
                groupIdHex = record.groupIdHex,
                messages = messages,
                changes = listOf(TimelineMessageChangeFfi.Upsert(TimelineUpdateTriggerFfi.GROUP_SYSTEM, record)),
                chatListRow = null,
                chatListTrigger = trigger,
            ),
    )

    private fun record(
        systemType: String,
        actor: String?,
        subject: String?,
    ) = TimelineMessageRecordFfi(
        messageIdHex = "msg-$systemType",
        sourceMessageIdHex = "source-$systemType",
        direction = "received",
        groupIdHex = "group-1",
        sender = actor.orEmpty(),
        plaintext = "{\"system_type\":\"$systemType\",\"data\":{}}",
        contentTokens = MarkdownDocumentFfi(truncated = false, blocks = emptyList()),
        kind = 1210uL,
        tags = emptyList(),
        timelineAt = 42uL,
        receivedAt = 42uL,
        replyToMessageIdHex = null,
        replyPreview = null,
        mediaJson = null,
        media = emptyList(),
        agentTextStreamJson = null,
        groupSystem =
            GroupSystemEventFfi(
                systemType = systemType,
                text = "Group updated",
                actorAccountIdHex = actor,
                subjectAccountIdHex = subject,
                name = null,
                oldName = null,
                oldRetentionSeconds = null,
                newRetentionSeconds = null,
            ),
        reactions = TimelineReactionSummaryFfi(byEmoji = emptyList(), userReactions = emptyList()),
        deleted = false,
        deletedByMessageIdHex = null,
        invalidationStatus = null,
    )
}
