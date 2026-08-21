package dev.ipf.whitenoise.android.ui.medialibrary

import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.whitenoise.android.state.TimelineMessage
import dev.ipf.whitenoise.android.state.projectedTimelineMessage
import org.junit.Assert.assertEquals
import org.junit.Test

class SharedMediaVisibilityTest {
    @Test
    fun sharedMediaExcludesRowsHiddenFromTheConversation() {
        val messages =
            listOf(
                imageMessage("live"),
                imageMessage("edit", kind = 1009uL),
                imageMessage("delete-record", kind = 5uL),
                imageMessage("projected-deleted", projectedDeleted = true),
                imageMessage("local-deleted"),
                imageMessage("pending-removal"),
                imageMessage("expired", retentionExpiresAt = 100uL),
                imageMessage("future", retentionExpiresAt = 101uL),
                imageMessage("zero-expiry", retentionExpiresAt = 0uL),
            )

        val tiles =
            buildVisibleSharedMediaTiles(
                messages = messages,
                myAccountId = null,
                deletedMessageIds = setOf("local-deleted"),
                pendingTimelineRemovedMessageIds = setOf("pending-removal"),
                nowSeconds = 100uL,
            )

        assertEquals(
            listOf("zero-expiry", "future", "live"),
            tiles.images.map { it.messageIdHex },
        )
    }

    private fun imageMessage(
        id: String,
        kind: ULong = 9uL,
        retentionExpiresAt: ULong? = null,
        projectedDeleted: Boolean = false,
    ): TimelineMessage {
        val record =
            AppMessageRecordFfi(
                messageIdHex = id,
                direction = "received",
                groupIdHex = "group",
                sender = "alice",
                plaintext = "",
                contentTokens =
                    MarkdownDocumentFfi(
                        truncated = false,
                        blocks = emptyList(),
                        blankLinesBefore = ByteArray(0),
                    ),
                kind = kind,
                tags = emptyList(),
                sourceEpoch = null,
                retentionSeconds = null,
                retentionExpiresAt = retentionExpiresAt,
                recordedAt = 1uL,
                receivedAt = 1uL,
            )
        val message = projectedTimelineMessage(record)
        return message.copy(
            projected =
                requireNotNull(message.projected).copy(
                    media = listOf(reference(id)),
                    deleted = projectedDeleted,
                    retentionExpiresAt = retentionExpiresAt,
                ),
        )
    }

    private fun reference(id: String) =
        MediaAttachmentReferenceFfi(
            locators = emptyList(),
            ciphertextSha256 = "aa".repeat(32),
            plaintextSha256 = "bb".repeat(32),
            nonceHex = "cc".repeat(12),
            fileName = "$id.jpg",
            mediaType = "image/jpeg",
            version = EncryptedMediaVersionFfi.V1,
            sourceEpoch = 1uL,
            dim = null,
            thumbhash = null,
        )
}
