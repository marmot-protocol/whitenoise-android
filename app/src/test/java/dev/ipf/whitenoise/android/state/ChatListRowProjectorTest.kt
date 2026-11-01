package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AppBlobEndpointFfi
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.AppProtocolProfileFfi
import dev.ipf.marmotkit.ChatConversationKindFfi
import dev.ipf.marmotkit.ChatListMessageDeliveryStateFfi
import dev.ipf.marmotkit.ChatListMessagePreviewFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.ConversationPresentationFfi
import dev.ipf.marmotkit.DeletionSourceFfi
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.GroupLifecycleStateFfi
import dev.ipf.marmotkit.PresentationResolutionFfi
import dev.ipf.marmotkit.PresentationSourceFfi
import dev.ipf.marmotkit.PresentationTextFfi
import dev.ipf.marmotkit.PresentedChatRowFfi
import dev.ipf.marmotkit.SelectedAvatarFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.whitenoise.android.core.EMPTY_MARKDOWN_DOCUMENT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the actual retained/off-window projector boundary using the existing controller inputs. */
class ChatListRowProjectorTest {
    /** Retained and off-window rows keep their own selected presentation and optimistic-send context. */
    @Test
    fun retainedAndOffWindowRowsUseTheirOwnSelectedPresentationAndOptimisticContext() {
        val row = row(preview())
        val retained = presentation("Retained")
        val outside = presentation("Outside")
        val optimistic =
            OptimisticChatListPreviewState(row, 0uL).apply {
                reservedActivitySequenceById["send"] = 2uL
            }
        val projector = projector(retained, optimistic)
        val first = projector.retained(row)
        val second =
            projector.presented(
                PresentedChatRowFfi(
                    draftVersion = null,
                    preview = emptyChatRowPreview(),
                    actions = noChatRowActions(),
                    row = row,
                    presentation = outside,
                    avatarAsset = null,
                ),
            )
        assertEquals(retained, first.selectedPresentation)
        assertEquals(outside, second.selectedPresentation)
        assertTrue(first.awaitingSendPreview)
        assertFalse(second.awaitingSendPreview)
        assertEquals(7uL, first.activitySequence)
        assertEquals(first.activitySequence, second.activitySequence)
        assertEquals(members(), first.memberSnapshot?.members)
    }

    /** Applying the archive overlay preserves the pure projection and leaves the authoritative row unchanged. */
    @Test
    fun projectorPreservesTheExistingPureProjectionAndArchiveOverlay() {
        val row = row(preview())
        val archives = archiveActions(row)
        archives.begin(listOf(GROUP_ID), archived = true, epoch = 1L)
        val actual = projector(null, null, archives).retained(row)
        val expected =
            chatListItemFromProjection(
                row = row.copy(archived = true),
                group = group().copy(archived = true),
                activeAccountIdHex = ACTIVE_ACCOUNT,
                members = members(),
                activitySequence = 7uL,
            )
        assertEquals(expected, actual)
        assertFalse(row.archived)
        archives.clear()
        assertFalse(projector(null, null, archives).retained(row).group.archived)
    }

    /** Selected actions, preview, avatar and fallback inputs reach the retained row unchanged. */
    @Test
    fun selectedCapabilitiesPreviewAndFallbackInputsSurviveProjection() {
        val row = row(preview())
        val selected = presentation("Selected")
        val preview = emptyChatRowPreview()
        val actions = noChatRowActions().copy(canMarkRead = true, canArchive = true)
        val fallback =
            dev.ipf.whitenoise.android.core
                .MediaPreviewFallback(filename = "report.pdf")
        val context =
            ChatListRowProjector(
                activeAccountIdHex = ACTIVE_ACCOUNT,
                groups = mapOf(GROUP_ID to group()),
                members = emptyMap(),
                presentationMembers = { chatListMemberPresentation(members(), ACTIVE_ACCOUNT) },
                tokens = mapOf("hello" to EMPTY_MARKDOWN_DOCUMENT),
                mediaFallbacks = mapOf(row.lastMessage!!.messageIdHex to fallback),
                removed = setOf(GROUP_ID),
                activitySequences = mapOf(GROUP_ID to 11uL),
                optimisticPreviews = emptyMap(),
                archiveActions = archiveActions(row),
                localGroupNames = LocalGroupNameAuthority(),
                presentations = mapOf(GROUP_ID to selected),
                avatars = emptyMap(),
                previews = mapOf(GROUP_ID to preview),
                actions = mapOf(GROUP_ID to actions),
            )
        val expected =
            chatListItemFromProjection(
                row = row,
                selectedPresentation = selected,
                selectedPreview = preview,
                actions = actions,
                group = group(),
                activeAccountIdHex = ACTIVE_ACCOUNT,
                presentationMembers = chatListMemberPresentation(members(), ACTIVE_ACCOUNT),
                previewTokens = EMPTY_MARKDOWN_DOCUMENT,
                resolvedMediaPreviewFallback = fallback,
                removed = true,
                activitySequence = 11uL,
            )
        assertEquals(expected, context.retained(row))
    }

    /** Builds a projector with a fixed roster and activity order plus the supplied selection and send overlay. */
    private fun projector(
        presentation: ConversationPresentationFfi?,
        optimistic: OptimisticChatListPreviewState?,
        archives: ChatListArchiveActions = archiveActions(row(preview())),
    ) = ChatListRowProjector(
        activeAccountIdHex = ACTIVE_ACCOUNT,
        groups = mapOf(GROUP_ID to group()),
        members = mapOf(GROUP_ID to members()),
        presentationMembers = { null },
        tokens = emptyMap(),
        mediaFallbacks = emptyMap(),
        removed = emptySet(),
        activitySequences = mapOf(GROUP_ID to 7uL),
        optimisticPreviews = optimistic?.let { mapOf(GROUP_ID to it) }.orEmpty(),
        archiveActions = archives,
        localGroupNames = LocalGroupNameAuthority(),
        presentations = presentation?.let { mapOf(GROUP_ID to it) }.orEmpty(),
        avatars = emptyMap(),
        previews = emptyMap(),
        actions = emptyMap(),
    )

    /** Provides an in-memory archive overlay for the supplied authoritative row without persisting changes. */
    private fun archiveActions(row: ChatListRowFfi) =
        ChatListArchiveActions(
            rowForGroup = { row.takeIf { requested -> requested.groupIdHex == it } },
            isCurrent = { true },
            onChanged = {},
            updateGroup = { _, _, _, _ -> false },
            onFailure = {},
            onSuccess = {},
        )

    /** Creates an MDK fallback title and placeholder avatar for the test group. */
    private fun presentation(title: String) =
        ConversationPresentationFfi(
            title = PresentationTextFfi.Literal(title),
            avatar = SelectedAvatarFfi.Placeholder(GROUP_ID, PresentationSourceFfi.GROUP_FALLBACK),
            titleSource = PresentationSourceFfi.GROUP_FALLBACK,
            avatarSource = PresentationSourceFfi.GROUP_FALLBACK,
            peerId = null,
            resolution = PresentationResolutionFfi.FALLBACK,
        )

    /** Returns a local member and a remote peer to exercise account-aware roster projection. */
    private fun members(): List<AppGroupMemberRecordFfi> =
        listOf(
            AppGroupMemberRecordFfi(memberIdHex = ACTIVE_ACCOUNT, account = null, local = true),
            AppGroupMemberRecordFfi(memberIdHex = PEER_ACCOUNT, account = null, local = false),
        )

    /** Returns an immutable preview that isolates row equality from message-content changes. */
    private fun preview() =
        ChatListMessagePreviewFfi(
            retentionSeconds = null,
            retentionExpiresAt = null,
            messageIdHex = "b".repeat(64),
            sender = PEER_ACCOUNT,
            senderDisplayName = null,
            plaintext = "hello",
            contentTokens = EMPTY_MARKDOWN_DOCUMENT,
            kind = 9uL,
            timelineAt = 1_700_000_000uL,
            deleted = false,
            deletionSource = DeletionSourceFfi.UNKNOWN,
            attachmentKind = null,
            attachmentCount = 0u,
            groupSystem = null,
            deliveryState = ChatListMessageDeliveryStateFfi.DELIVERED,
        )

    /** Builds a stable group row with the supplied last-message preview and no unread or archive state. */
    private fun row(lastMessage: ChatListMessagePreviewFfi?) =
        ChatListRowFfi(
            groupIdHex = GROUP_ID,
            pinned = false,
            pinnedPosition = null,
            archived = false,
            pendingConfirmation = false,
            lifecycleState = GroupLifecycleStateFfi.STABLE,
            disbanding = false,
            disbandRequest = null,
            title = "Conversation",
            groupName = "Conversation",
            avatarUrl = null,
            avatar = null,
            lastMessage = lastMessage,
            unreadCount = 0uL,
            hasUnread = false,
            manuallyMarkedUnread = false,
            unreadMentionCount = 0uL,
            unreadMention = false,
            firstUnreadMessageIdHex = null,
            lastReadMessageIdHex = null,
            lastReadTimelineAt = null,
            conversationCreatedAt = 1_699_999_000uL,
            activitySortAt = 1_700_000_000uL,
            updatedAt = 1_700_000_000uL,
            selfMembership = SelfMembershipFfi.MEMBER,
            conversationKind = ChatConversationKindFfi.GROUP,
            muted = false,
            mutedUntilMs = null,
            leaveRequestPending = false,
            leaveRequestedAtMs = null,
        )

    /** Builds the matching authoritative group record with default encrypted-media capabilities. */
    private fun group() =
        AppGroupRecordFfi(
            groupIdHex = GROUP_ID,
            protocolProfile = AppProtocolProfileFfi.LEGACY,
            endpoint = "endpoint",
            profilePresent = true,
            name = "Conversation",
            description = "",
            admins = emptyList(),
            relays = emptyList(),
            nostrGroupIdHex = "nostr-$GROUP_ID",
            avatarUrl = null,
            avatarDim = null,
            avatarThumbhash = null,
            imageHashHex = null,
            encryptedMedia = encryptedMedia(),
            disappearingMessageSecs = 0uL,
            archived = false,
            pendingConfirmation = false,
            unrecoverable = false,
            selfMembership = SelfMembershipFfi.MEMBER,
            leaveRequestPending = false,
            leaveRequestedAtMs = null,
            welcomerAccountIdHex = null,
            viaWelcomeMessageIdHex = null,
            disbanding = false,
            disbanded = false,
            disbandRequest = null,
        )

    /** Supplies the fixture group's supported encrypted-media version and Blossom locator capabilities. */
    private fun encryptedMedia() =
        AppGroupEncryptedMediaComponentFfi(
            componentId = 0x8008u,
            component = "marmot.group.encrypted-media.v1",
            required = true,
            version = EncryptedMediaVersionFfi.V1,
            mediaFormat = "encrypted-media-v1",
            allowedLocatorKinds = listOf("blossom-v1"),
            defaultBlobEndpoints =
                listOf(AppBlobEndpointFfi(locatorKind = "blossom-v1", baseUrl = "https://blossom.primal.net")),
        )

    private companion object {
        val GROUP_ID = "a".repeat(64)
        val ACTIVE_ACCOUNT = "c".repeat(64)
        val PEER_ACCOUNT = "d".repeat(64)
    }
}
