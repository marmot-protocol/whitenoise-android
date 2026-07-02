package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AppBlobEndpointFfi
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.ChatListMessagePreviewFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.whitenoise.android.core.MessageTextCopy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Covers every arm of [ChatListItem.projectedPreviewText] — the chat row's
 * one-line preview. The special-kind arms encode real regressions: kind-1009
 * edits must not replace the preview with the edit payload, kind-1210 group
 * system rows must never leak their raw JSON content (issue #577), and a
 * kind-1200 agent stream start falls back to derived copy while its body is
 * still empty.
 */
class ProjectedPreviewTextTest {
    private val copy = MessageTextCopy.Default

    @Test
    fun liveChatBodyRendersVerbatim() {
        val item = item(preview = preview(plaintext = "hello *world*"))

        assertEquals("hello *world*", item.projectedPreviewText(copy))
    }

    @Test
    fun deletedLastMessageShowsDeletedCopy() {
        val item = item(preview = preview(plaintext = "gone", deleted = true))

        assertEquals(copy.deleted, item.projectedPreviewText(copy))
    }

    @Test
    fun agentStreamStartWithBlankBodyShowsStreamStartedCopy() {
        val item = item(preview = preview(plaintext = "", kind = 1200uL))

        assertEquals(copy.agentStreamStarted, item.projectedPreviewText(copy))
    }

    @Test
    fun agentStreamStartWithBodyShowsTheStreamedText() {
        val item = item(preview = preview(plaintext = "thinking...", kind = 1200uL))

        assertEquals("thinking...", item.projectedPreviewText(copy))
    }

    @Test
    fun editRowKeepsTheOriginalLatestBodyNotTheEditPayload() {
        val item =
            item(
                preview = preview(plaintext = "edited body payload", kind = 1009uL),
                latest = latest(plaintext = "original body"),
            )

        assertEquals("original body", item.projectedPreviewText(copy))
    }

    @Test
    fun editRowWithoutALatestRecordFallsBackToEmptyCopy() {
        val item = item(preview = preview(plaintext = "edited body payload", kind = 1009uL))

        assertEquals("nothing here", item.projectedPreviewText(copy, empty = "nothing here"))
    }

    @Test
    fun groupSystemRowNeverLeaksRawJsonIntoThePreview() {
        val rawJson = """{"type":"avatar_changed","actor":"deadbeef"}"""
        val item = item(preview = preview(plaintext = rawJson, kind = 1210uL))

        val text = item.projectedPreviewText(copy)

        assertFalse("raw kind-1210 JSON must not surface", text.contains("{"))
        // An unparseable payload resolves to the localized fallback copy.
        assertEquals(copy.groupSystem.fallback, text)
    }

    @Test
    fun blankBodyOnAGenericKindFallsBackToMessageCopy() {
        val item = item(preview = preview(plaintext = "   ", kind = 9uL))

        assertEquals(copy.message, item.projectedPreviewText(copy))
    }

    @Test
    fun withoutAProjectionThePreviewComesFromTheLatestRecord() {
        val item = item(preview = null, latest = latest(plaintext = "from latest"))

        assertEquals("from latest", item.projectedPreviewText(copy))
    }

    @Test
    fun withoutProjectionOrLatestThePreviewIsTheEmptyPlaceholder() {
        val item = item(preview = null, latest = null)

        assertEquals("No messages yet", item.projectedPreviewText(copy))
        assertEquals("custom empty", item.projectedPreviewText(copy, empty = "custom empty"))
    }

    // ---- helpers ------------------------------------------------------------

    private fun item(
        preview: ChatListMessagePreviewFfi?,
        latest: AppMessageRecordFfi? = null,
    ): ChatListItem =
        ChatListItem(
            group = group("group-a"),
            latest = latest,
            otherMemberAccount = null,
            memberCount = 0,
            memberSnapshot = null,
            projection = preview?.let { row("group-a", it) },
        )

    private fun preview(
        plaintext: String,
        kind: ULong = 9uL,
        deleted: Boolean = false,
    ) = ChatListMessagePreviewFfi(
        messageIdHex = "preview-message",
        sender = "sender",
        senderDisplayName = "Sender",
        plaintext = plaintext,
        contentTokens = MarkdownDocumentFfi(truncated = false, blocks = emptyList()),
        kind = kind,
        timelineAt = 10uL,
        deleted = deleted,
    )

    private fun latest(plaintext: String) =
        AppMessageRecordFfi(
            messageIdHex = "latest-message",
            direction = "received",
            groupIdHex = "group-a",
            sender = "sender",
            plaintext = plaintext,
            contentTokens = MarkdownDocumentFfi(truncated = false, blocks = emptyList()),
            kind = 9uL,
            tags = emptyList(),
            recordedAt = 5uL,
            receivedAt = 5uL,
        )

    private fun row(
        groupId: String,
        preview: ChatListMessagePreviewFfi,
    ) = ChatListRowFfi(
        unreadMentionCount = 0uL,
        unreadMention = false,
        groupIdHex = groupId,
        archived = false,
        pendingConfirmation = false,
        title = "Group $groupId",
        groupName = "",
        avatarUrl = null,
        avatar = null,
        lastMessage = preview,
        unreadCount = 0uL,
        hasUnread = false,
        firstUnreadMessageIdHex = null,
        lastReadMessageIdHex = null,
        lastReadTimelineAt = null,
        updatedAt = 10uL,
    )

    private fun group(id: String) =
        AppGroupRecordFfi(
            groupIdHex = id,
            endpoint = "endpoint-$id",
            name = "",
            description = "",
            admins = emptyList(),
            relays = emptyList(),
            nostrGroupIdHex = "nostr-$id",
            avatarUrl = null,
            avatarDim = null,
            avatarThumbhash = null,
            encryptedMedia = encryptedMedia(),
            archived = false,
            pendingConfirmation = false,
            welcomerAccountIdHex = null,
            viaWelcomeMessageIdHex = null,
            disappearingMessageSecs = 0uL,
        )

    private fun encryptedMedia() =
        AppGroupEncryptedMediaComponentFfi(
            componentId = 0x8008u,
            component = "marmot.group.encrypted-media.v1",
            required = true,
            mediaFormat = "encrypted-media-v1",
            allowedLocatorKinds = listOf("blossom-v1"),
            defaultBlobEndpoints = listOf(AppBlobEndpointFfi(locatorKind = "blossom-v1", baseUrl = "https://blossom.primal.net")),
        )
}
