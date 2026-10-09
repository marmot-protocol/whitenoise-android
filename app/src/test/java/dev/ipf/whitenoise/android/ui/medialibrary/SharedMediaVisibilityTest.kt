package dev.ipf.whitenoise.android.ui.medialibrary

import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.AttachmentRoleFfi
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MediaAttachmentOutcomeFfi
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.marmotkit.MediaLocatorFfi
import dev.ipf.marmotkit.MessageTagFfi
import dev.ipf.whitenoise.android.core.MessageAttachments
import dev.ipf.whitenoise.android.state.TimelineMessage
import dev.ipf.whitenoise.android.state.projectedTimelineMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    /** Visual media preserves combined newest first order. */
    @Test
    fun visualMediaPreservesCombinedNewestFirstOrder() {
        val messages =
            listOf(
                imageMessage("old-image", recordedAt = 100uL),
                imageMessage("middle-video", recordedAt = 200uL, mediaType = "video/mp4"),
                imageMessage("new-image", recordedAt = 300uL),
            )

        val tiles =
            buildVisibleSharedMediaTiles(
                messages = messages,
                myAccountId = null,
                deletedMessageIds = emptySet(),
                pendingTimelineRemovedMessageIds = emptySet(),
                nowSeconds = 400uL,
            )

        assertEquals(
            listOf("new-image", "middle-video", "old-image"),
            tiles.visuals.map { it.messageIdHex },
        )
        assertFalse(tiles.visuals.first().isVideo)
        assertEquals(true, tiles.visuals[1].isVideo)
        assertEquals(tiles.visuals, tiles.visualsFor(SharedVisualFilter.All))
        assertEquals(tiles.images, tiles.visualsFor(SharedVisualFilter.Images))
        assertEquals(tiles.videos, tiles.visualsFor(SharedVisualFilter.Videos))
        assertEquals(tiles.visuals, tiles.visualSections.flatMap { it.items })
        for (filter in SharedVisualFilter.entries) {
            val source = tiles.visualsFor(filter)
            val pages = source.toGalleryViewerPages()
            assertEquals(source.map { it.messageIdHex }, pages.map { it.messageIdHex })
            assertEquals(source.map { it.attachmentIndex }, pages.map { it.attachmentIndex })
            assertEquals(source.map { it.reference }, pages.map { it.reference })
            assertEquals(source.map { it.mine }, pages.map { it.mine })
        }
    }

    /** Newest-first grids retain authored attachment slots within each album. */
    @Test
    fun newestFirstGridPreservesAlbumAttachmentOrder() {
        val messages = listOf(imageMessage("older", recordedAt = 100uL), imageAlbum("album", recordedAt = 200uL))

        val tiles = buildVisibleSharedMediaTiles(messages, null, emptySet(), emptySet(), 300uL)

        assertEquals(listOf("album", "album", "older"), tiles.visuals.map { it.messageIdHex })
        assertEquals(listOf(0, 1, 0), tiles.visuals.map { it.attachmentIndex })
        assertEquals(listOf("album", "album", "older"), tiles.visuals.toGalleryViewerPages().map { it.messageIdHex })
        assertEquals(listOf(0, 1, 0), tiles.visuals.toGalleryViewerPages().map { it.attachmentIndex })
    }

    /** Conversation traversal reverses message groups but never authored slots inside an album. */
    @Test
    fun conversationViewerUsesChronologicalMessagesAndAuthoredAlbumSlots() {
        val messages = listOf(imageMessage("older", recordedAt = 100uL), imageAlbum("album", recordedAt = 200uL))
        val tiles = buildVisibleSharedMediaTiles(messages, null, emptySet(), emptySet(), 300uL)

        val pages = tiles.visuals.toConversationViewerPages()

        assertEquals(listOf("older", "album", "album"), pages.map { it.messageIdHex })
        assertEquals(listOf(0, 0, 1), pages.map { it.attachmentIndex })
    }

    /** Viewer keeps current page across loading and clears only confirmed removal. */
    @Test
    fun viewerKeepsCurrentPageAcrossLoadingAndClearsOnlyConfirmedRemoval() {
        val tiles =
            buildVisibleSharedMediaTiles(
                listOf(imageMessage("first"), imageMessage("second")),
                null,
                emptySet(),
                emptySet(),
                100uL,
            )
        val pages = tiles.visuals.toGalleryViewerPages()
        val selection = SharedMediaViewerSelection()
        selection.select("first", 0)
        // The real pager reports its new current source through this same owner method.
        selection.select("second", 0)
        selection.reconcile(loading = true, pages = emptyList())
        assertEquals("second" to 0, selection.source)
        selection.reconcile(loading = false, pages = pages)
        assertEquals("second" to 0, selection.source)
        selection.reconcile(loading = false, pages = pages.filter { it.messageIdHex == "second" })
        assertEquals("second" to 0, selection.source)
        selection.reconcile(loading = false, pages = pages.filter { it.messageIdHex == "first" })
        assertEquals(null, selection.source)
    }

    /** Builds one message whose first image is claimed by an emoji tag and whose second is a real photo. */
    private fun emojiMessage(
        id: String,
        claimedUrl: String = "https://blob/party",
        tagUrl: String = claimedUrl,
    ): TimelineMessage {
        val album = imageAlbum(id, recordedAt = 5uL)
        val outcomes =
            MessageAttachments.acceptedOutcomes(
                listOf(
                    reference("$id-0", "image/png").copy(locators = listOf(MediaLocatorFfi("blossom", claimedUrl))),
                    reference("$id-1", "image/jpeg")
                        .copy(locators = listOf(MediaLocatorFfi("blossom", "https://blob/photo"))),
                ),
            )
        return album.copy(
            record = album.record.copy(tags = listOf(MessageTagFfi(listOf("emoji", "party", tagUrl)))),
            projected = requireNotNull(album.projected).copy(media = outcomes),
        )
    }

    /** An image an emoji tag claims as inline artwork is not shared media, in the gallery or the viewer. */
    @Test
    fun emojiArtworkIsExcludedFromSharedMediaAndViewerPages() {
        val tiles = buildVisibleSharedMediaTiles(listOf(emojiMessage("m")), null, emptySet(), emptySet(), 100uL)

        assertEquals(listOf(1), tiles.visuals.map { it.attachmentIndex })
        assertEquals(listOf(1), tiles.images.map { it.attachmentIndex })
        assertEquals(listOf(1), tiles.visuals.toConversationViewerPages().map { it.attachmentIndex })
        assertEquals(listOf(1), tiles.visuals.toGalleryViewerPages().map { it.attachmentIndex })
    }

    /** Recent tiles and native history exclude every duplicate artwork slot without renumbering photos. */
    @Test
    fun duplicateArtworkAgreesWithNativeGalleryRoles() {
        val original = emojiMessage("m")
        val projected = requireNotNull(original.projected)
        val artwork = projected.media[0] as MediaAttachmentOutcomeFfi.Accepted
        val photo = projected.media[1] as MediaAttachmentOutcomeFfi.Accepted
        val media = listOf(artwork, artwork.copy(attachmentIndex = 2u), photo.copy(attachmentIndex = 5u))
        val message = original.copy(projected = projected.copy(media = media))
        val recent = buildVisibleSharedMediaTiles(listOf(message), null, emptySet(), emptySet(), 100uL)
        val entries =
            media.map { slot ->
                historyEntry(
                    1,
                    slot.attachmentIndex,
                    role = if (slot.attachmentIndex == 5u) AttachmentRoleFfi.SHARED else AttachmentRoleFfi.INLINE_EMOJI,
                ).copy(attachment = slot)
            }
        val history = attachmentLibraryTiles(entries, null)
        assertEquals(listOf(5), recent.images.map { it.attachmentIndex })
        assertEquals(listOf(5), history.images.map { it.attachmentIndex })
        assertEquals(listOf(photo.reference), recent.images.map { it.reference })
        assertEquals(listOf(photo.reference), history.images.map { it.reference })
    }

    /** Native Shared roles for malformed long tags must not disagree with the recent-media strip. */
    @Test
    fun malformedEmojiTagLeavesTheImageSharedOnBothSurfaces() {
        val original = emojiMessage("m")
        val tags = listOf(MessageTagFfi(listOf("emoji", "party", "https://blob/party", "set", "extra")))
        val message = original.copy(record = original.record.copy(tags = tags))
        val recent = buildVisibleSharedMediaTiles(listOf(message), null, emptySet(), emptySet(), 100uL)
        val entries =
            requireNotNull(message.projected).media.map { slot ->
                historyEntry(1, (slot as MediaAttachmentOutcomeFfi.Accepted).attachmentIndex).copy(attachment = slot)
            }
        assertEquals(listOf(0, 1), recent.images.map { it.attachmentIndex })
        assertEquals(listOf(0, 1), attachmentLibraryTiles(entries, null).images.map { it.attachmentIndex })
    }

    /** A tag that matches no attachment claims nothing, so every image stays shared media. */
    @Test
    fun unmatchedEmojiTagLeavesEveryImageShared() {
        val message = emojiMessage("m", tagUrl = "https://blob/other")

        val tiles = buildVisibleSharedMediaTiles(listOf(message), null, emptySet(), emptySet(), 100uL)

        assertEquals(listOf(0, 1), tiles.visuals.map { it.attachmentIndex })
    }

    private fun imageMessage(
        id: String,
        kind: ULong = 9uL,
        retentionExpiresAt: ULong? = null,
        projectedDeleted: Boolean = false,
        recordedAt: ULong = 1uL,
        mediaType: String = "image/jpeg",
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
                recordedAt = recordedAt,
                receivedAt = 1uL,
            )
        val message = projectedTimelineMessage(record)
        return message.copy(
            projected =
                requireNotNull(message.projected).copy(
                    media = MessageAttachments.acceptedOutcomes(listOf(reference(id, mediaType))),
                    deleted = projectedDeleted,
                    retentionExpiresAt = retentionExpiresAt,
                ),
        )
    }

    /** Builds one projected message with two authored image slots. */
    private fun imageAlbum(
        id: String,
        recordedAt: ULong,
    ): TimelineMessage {
        val first = imageMessage(id, recordedAt = recordedAt)
        return first.copy(
            projected =
                requireNotNull(first.projected).copy(
                    media =
                        MessageAttachments.acceptedOutcomes(
                            listOf(reference("$id-0", "image/jpeg"), reference("$id-1", "image/jpeg")),
                        ),
                ),
        )
    }

    private fun reference(
        id: String,
        mediaType: String,
    ) = MediaAttachmentReferenceFfi(
        locators = emptyList(),
        ciphertextSha256 = "aa".repeat(32),
        plaintextSha256 = "bb".repeat(32),
        nonceHex = "cc".repeat(12),
        fileName = if (mediaType.startsWith("video/")) "$id.mp4" else "$id.jpg",
        mediaType = mediaType,
        version = EncryptedMediaVersionFfi.V1,
        sourceEpoch = 1uL,
        dim = null,
        thumbhash = null,
    )
}
