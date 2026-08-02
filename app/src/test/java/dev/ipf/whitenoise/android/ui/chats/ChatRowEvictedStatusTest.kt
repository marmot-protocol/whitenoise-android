package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AppBlobEndpointFfi
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.ChatConversationKindFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.chatListItemEvicted
import dev.ipf.whitenoise.android.state.ChatListItem
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ChatRowEvictedStatusTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val removedLabel by lazy { context.getString(R.string.chat_row_removed_badge) }
    private val removedDescription by lazy { context.getString(R.string.chat_row_removed_description) }

    @Test
    fun evictionShowsAQuietStatusLabelWithATalkBackDescription() {
        render(evicted = true)

        composeRule.onNodeWithText(removedLabel).assertExists()
        composeRule.onNodeWithContentDescription(removedDescription).assertExists()
    }

    @Test
    fun anActiveMembershipShowsNoStatusLabel() {
        render(evicted = false)

        composeRule.onNodeWithText(removedLabel).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(removedDescription).assertDoesNotExist()
    }

    @Test
    fun evictionStatusCoexistsWithThePinnedMarker() {
        render(evicted = true, pinned = true)

        composeRule.onNodeWithText(removedLabel).assertExists()
        composeRule.onNodeWithContentDescription(context.getString(R.string.chat_pinned_badge)).assertExists()
    }

    @Test
    fun aPendingInviteKeepsTheInvitedBadgeAndNoEvictionStatus() {
        render(evicted = true, pendingConfirmation = true)

        composeRule.onNodeWithText(context.getString(R.string.invited)).assertExists()
        composeRule.onNodeWithText(removedLabel).assertDoesNotExist()
    }

    @Test
    fun onlyAnEvictionCountsAsRemoved() {
        assertTrue(chatListItemEvicted(item(group = SelfMembershipFfi.REMOVED)))
        assertTrue(chatListItemEvicted(item(row = SelfMembershipFfi.REMOVED)))
        assertFalse(chatListItemEvicted(item(group = SelfMembershipFfi.LEFT, row = SelfMembershipFfi.LEFT)))
        assertFalse(chatListItemEvicted(item()))
    }

    @Test
    fun anUnprojectedRowFallsBackToTheReconciledGroupMembership() {
        assertTrue(chatListItemEvicted(item(group = SelfMembershipFfi.REMOVED, projected = false)))
        assertFalse(chatListItemEvicted(item(projected = false)))
    }

    private fun render(
        evicted: Boolean,
        pinned: Boolean = false,
        pendingConfirmation: Boolean = false,
    ) {
        composeRule.setContent {
            MaterialTheme {
                Surface {
                    Row {
                        ChatRowSupportingMetadata(
                            pendingConfirmation = pendingConfirmation,
                            rowHasUnread = false,
                            rowUnreadCount = 0uL,
                            unreadMention = false,
                            actionColors = null,
                            pinned = pinned,
                            evicted = evicted,
                        )
                    }
                }
            }
        }
    }

    private fun item(
        group: SelfMembershipFfi = SelfMembershipFfi.MEMBER,
        row: SelfMembershipFfi = SelfMembershipFfi.MEMBER,
        projected: Boolean = true,
    ): ChatListItem =
        ChatListItem(
            group = groupRecord(group),
            latest = null,
            otherMemberAccount = null,
            memberCount = 3,
            memberSnapshot = null,
            projection = if (projected) projectedRow(row) else null,
        )

    private fun projectedRow(selfMembership: SelfMembershipFfi) =
        ChatListRowFfi(
            selfMembership = selfMembership,
            unreadMentionCount = 0uL,
            unreadMention = false,
            groupIdHex = "g1",
            archived = false,
            pendingConfirmation = false,
            title = "Group",
            groupName = "",
            avatarUrl = null,
            avatar = null,
            lastMessage = null,
            unreadCount = 0uL,
            hasUnread = false,
            firstUnreadMessageIdHex = null,
            lastReadMessageIdHex = null,
            lastReadTimelineAt = null,
            conversationCreatedAt = 0uL,
            activitySortAt = 0uL,
            updatedAt = 1uL,
            leaveRequestPending = false,
            leaveRequestedAtMs = null,
            manuallyMarkedUnread = false,
            conversationKind = ChatConversationKindFfi.UNKNOWN,
            muted = false,
            mutedUntilMs = null,
            pinned = false,
            pinnedPosition = null,
            lifecycleState = dev.ipf.marmotkit.GroupLifecycleStateFfi.STABLE,
            disbanding = false,
            disbandRequest = null,
        )

    private fun groupRecord(selfMembership: SelfMembershipFfi) =
        AppGroupRecordFfi(
            selfMembership = selfMembership,
            groupIdHex = "g1",
            protocolProfile = dev.ipf.marmotkit.AppProtocolProfileFfi.LEGACY,
            profilePresent = false,
            endpoint = "endpoint-g1",
            name = "",
            description = "",
            admins = emptyList(),
            relays = emptyList(),
            nostrGroupIdHex = "nostr-g1",
            avatarUrl = null,
            avatarDim = null,
            avatarThumbhash = null,
            imageHashHex = null,
            encryptedMedia = encryptedMedia(),
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

    private fun encryptedMedia() =
        AppGroupEncryptedMediaComponentFfi(
            componentId = 0x8008u,
            component = "marmot.group.encrypted-media.v1",
            required = true,
            version = dev.ipf.marmotkit.EncryptedMediaVersionFfi.V1,
            mediaFormat = "encrypted-media-v1",
            allowedLocatorKinds = listOf("blossom-v1"),
            defaultBlobEndpoints =
                listOf(
                    AppBlobEndpointFfi(locatorKind = "blossom-v1", baseUrl = "https://blossom.primal.net"),
                ),
        )
}
