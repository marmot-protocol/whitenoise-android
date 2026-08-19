package dev.ipf.whitenoise.android.state

import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import dev.ipf.marmotkit.AppBlobEndpointFfi
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.ChatConversationKindFfi
import dev.ipf.marmotkit.ChatListMessageDeliveryStateFfi
import dev.ipf.marmotkit.ChatListMessagePreviewFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.GroupLifecycleStateFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.notifications.LocalNotificationFormatter
import dev.ipf.whitenoise.android.notifications.NotificationChannelSpec
import dev.ipf.whitenoise.android.notifications.NotificationChannels
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LauncherBadgeUnreadReconciliationTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val manager: NotificationManager = context.getSystemService(NotificationManager::class.java)

    @Test
    fun finalUnreadMarkAllReadCancelsTheActiveConversationCard() {
        manager.cancelAll()
        NotificationChannels.ensureChannels(context)
        val unreadRow = chatRow(unreadCount = 1uL, lastReadMessageIdHex = null)
        val readRow = chatRow(unreadCount = 0uL, lastReadMessageIdHex = MESSAGE_ID)
        val notificationKey = LocalNotificationFormatter.conversationDismissalKey(ACCOUNT_REF, GROUP_ID)
        manager.notify(
            notificationKey.tag,
            notificationKey.id,
            NotificationCompat
                .Builder(context, NotificationChannelSpec.GROUP_MESSAGES.id)
                .setSmallIcon(R.drawable.ic_stat_whitenoise)
                .setContentTitle("Unread message")
                .build(),
        )
        val fixture = NotificationBootstrapTestFixture(context, markReadRow = readRow)
        val controller =
            ChatsController(
                appState = fixture.appState,
                initialAccountRef = ACCOUNT_REF,
                memberSnapshotLoader = { _, _ -> emptyList() },
            )

        try {
            runBlocking {
                fixture.ensureNotificationRuntimeStarted()
                assertTrue(controller.markAllRead(chatListItem(unreadRow)))
            }

            assertEquals(1, fixture.markReadCalls.get())
            assertTrue(manager.activeNotifications.isEmpty())
        } finally {
            controller.onCleared()
            fixture.close()
            manager.cancelAll()
        }
    }

    private fun chatListItem(row: ChatListRowFfi) =
        ChatListItem(
            group = group(),
            latest = null,
            otherMemberAccount = null,
            memberCount = 1,
            memberSnapshot = null,
            projection = row,
        )

    private fun chatRow(
        unreadCount: ULong,
        lastReadMessageIdHex: String?,
    ) = ChatListRowFfi(
        selfMembership = SelfMembershipFfi.MEMBER,
        unreadMentionCount = 0uL,
        unreadMention = false,
        groupIdHex = GROUP_ID,
        archived = false,
        pendingConfirmation = false,
        title = "Chat",
        groupName = "Chat",
        avatarUrl = null,
        avatar = null,
        lastMessage =
            ChatListMessagePreviewFfi(
                messageIdHex = MESSAGE_ID,
                sender = "sender",
                senderDisplayName = "Sender",
                plaintext = "hello",
                contentTokens =
                    MarkdownDocumentFfi(
                        truncated = false,
                        blocks = emptyList(),
                        blankLinesBefore = ByteArray(0),
                    ),
                kind = 9uL,
                timelineAt = 100uL,
                deleted = false,
                attachmentKind = null,
                attachmentCount = 0u,
                deliveryState = ChatListMessageDeliveryStateFfi.NOT_APPLICABLE,
            ),
        unreadCount = unreadCount,
        hasUnread = unreadCount > 0uL,
        firstUnreadMessageIdHex = MESSAGE_ID.takeIf { unreadCount > 0uL },
        lastReadMessageIdHex = lastReadMessageIdHex,
        lastReadTimelineAt = 100uL.takeIf { lastReadMessageIdHex != null },
        conversationCreatedAt = 0uL,
        activitySortAt = 100uL,
        updatedAt = 100uL,
        leaveRequestPending = false,
        leaveRequestedAtMs = null,
        manuallyMarkedUnread = false,
        conversationKind = ChatConversationKindFfi.GROUP,
        muted = false,
        mutedUntilMs = null,
        pinned = false,
        pinnedPosition = null,
        lifecycleState = GroupLifecycleStateFfi.STABLE,
        disbanding = false,
        disbandRequest = null,
    )

    private fun group() =
        AppGroupRecordFfi(
            selfMembership = SelfMembershipFfi.MEMBER,
            groupIdHex = GROUP_ID,
            protocolProfile = dev.ipf.marmotkit.AppProtocolProfileFfi.LEGACY,
            profilePresent = false,
            endpoint = "endpoint-$GROUP_ID",
            name = "Chat",
            description = "",
            admins = emptyList(),
            relays = emptyList(),
            nostrGroupIdHex = "nostr-$GROUP_ID",
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
                    defaultBlobEndpoints =
                        listOf(
                            AppBlobEndpointFfi(
                                locatorKind = "blossom-v1",
                                baseUrl = "https://blossom.primal.net",
                            ),
                        ),
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

    private companion object {
        const val ACCOUNT_REF = "account-a"
        val GROUP_ID = "2".repeat(64)
        val MESSAGE_ID = "3".repeat(64)
    }
}
