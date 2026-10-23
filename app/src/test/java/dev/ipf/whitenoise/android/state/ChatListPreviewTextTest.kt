package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.ChatListAttachmentKindFfi
import dev.ipf.whitenoise.android.core.MessageTextCopy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatListPreviewTextTest {
    @Test
    fun projectedArmsPreservePrecedenceWithoutEvaluatingFallback() {
        val copy = MessageTextCopy.Default

        fun render(
            kind: ULong,
            text: String = "",
            deleted: Boolean = false,
        ): String =
            projectedChatListPreviewText(
                ChatListPreviewInput(text, kind, deleted, ChatListAttachmentKindFfi.PHOTO, 1u, null),
                latestFallback = { error("higher priority arm must not evaluate fallback") },
            )
        assertEquals(copy.deleted, render(9uL, "hidden", true))
        assertEquals(copy.poll, render(1068uL, "wire"))
        assertEquals(copy.agentStreamStarted, render(1200uL))
        assertEquals("stream body", render(1200uL, "stream body"))
        assertEquals(copy.giphyMedia, render(9uL, "https://media.giphy.com/media/abc/giphy.gif\nvia GIPHY"))
        assertEquals(
            dev.ipf.whitenoise.android.core.GroupSystemEvents
                .previewText("{}", copy.groupSystem),
            render(1210uL, "{}"),
        )
        assertEquals("visible body", render(9uL, "visible body"))
        assertEquals(copy.attachmentLabel(ChatListAttachmentKindFfi.PHOTO, 1u), render(9uL))
    }

    @Test
    fun missingEditAndGenericProjectionKeepDistinctFallbackCopy() {
        val received = mutableListOf<String>()

        fun render(input: ChatListPreviewInput?): String =
            projectedChatListPreviewText(input, empty = "empty", latestFallback = {
                received += it
                it
            })
        assertEquals("empty", render(null))
        assertEquals("empty", render(ChatListPreviewInput("edit", 1009uL, false, null, 0u, null)))
        assertEquals(MessageTextCopy.Default.message, render(ChatListPreviewInput("", 9uL, false, null, 0u, null)))
        assertEquals(listOf("empty", "empty", MessageTextCopy.Default.message), received)
    }

    @Test
    fun explicitMediaFallbackWinsOnlyAfterBlankBodyAndMissingTypedAttachment() {
        val fallback =
            dev.ipf.whitenoise.android.core
                .MediaPreviewFallback(filename = "report.pdf")
        val input = ChatListPreviewInput("", 9uL, false, null, 0u, null)
        assertEquals(
            fallback.text(MessageTextCopy.Default),
            projectedChatListPreviewText(input, mediaFallback = fallback, latestFallback = { error("unexpected") }),
        )
        assertEquals(
            "body",
            projectedChatListPreviewText(
                input.copy(plaintext = "body"),
                mediaFallback = fallback,
                latestFallback = { error("unexpected") },
            ),
        )
        assertEquals("ChatListPreviewInput(redacted)", input.toString())
    }

    @Test
    fun authenticatedStructuredGroupPreviewForwardsLocalizedSubjectNames() {
        val subject = "b2".repeat(32)
        val event =
            dev.ipf.marmotkit.GroupSystemEventFfi(
                provenance = dev.ipf.marmotkit.GroupSystemEventProvenanceFfi.AUTHENTICATED_GROUP_STATE,
                actorDisplayName = null,
                subjectDisplayName = null,
                systemType = "member_added",
                text = "",
                actorAccountIdHex = "a1".repeat(32),
                subjectAccountIdHex = subject,
                name = null,
                oldName = null,
                oldRetentionSeconds = null,
                newRetentionSeconds = null,
            )
        val names =
            dev.ipf.whitenoise.android.core.GroupSystemPreviewNames(null) {
                assertEquals(subject, it)
                "Local member"
            }
        val copy = MessageTextCopy.Default
        val expected =
            dev.ipf.whitenoise.android.core.GroupSystemEvents.previewText(
                "untrusted raw payload",
                copy.groupSystem,
                structured = event,
                names = names,
            )
        assertTrue(expected.contains("Local member"))
        assertEquals(
            expected,
            projectedChatListPreviewText(
                ChatListPreviewInput("untrusted raw payload", 1210uL, false, null, 0u, event),
                groupSystemNames = names,
                latestFallback = { error("authenticated structured preview must not fall back") },
            ),
        )
    }
}
