package dev.ipf.whitenoise.android.core

import dev.ipf.whitenoise.android.search.GlobalSearchContentKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

/**
 * The rules behind the chat-list search library: which mode shows which attachment,
 * which modes tile, and how cards fall into day sections.
 */
class GlobalAttachmentLibraryTest {
    /** Images and video share one kind, audio its own, everything else counts as a file. */
    @Test
    fun classifiesAttachmentsByMediaType() {
        assertEquals(GlobalSearchContentKind.IMAGES_VIDEO, globalAttachmentContentKind("image/png"))
        assertEquals(GlobalSearchContentKind.IMAGES_VIDEO, globalAttachmentContentKind("video/mp4"))
        assertEquals(GlobalSearchContentKind.VOICE_AUDIO, globalAttachmentContentKind("audio/ogg"))
        assertEquals(GlobalSearchContentKind.FILES_DOCUMENTS, globalAttachmentContentKind("application/pdf"))
    }

    /** Only photos and videos take the square tile; audio and files get a card row. */
    @Test
    fun choosesTilePresentationForVisualMediaOnly() {
        assertEquals(GlobalAttachmentPresentation.VISUAL, globalAttachmentPresentation("image/jpeg"))
        assertEquals(GlobalAttachmentPresentation.AUDIO, globalAttachmentPresentation("audio/mpeg"))
        assertEquals(GlobalAttachmentPresentation.FILE, globalAttachmentPresentation("text/plain"))
    }

    /** The catch-all mode admits every attachment; a narrow mode admits only its own kind. */
    @Test
    fun matchesSelectionByKindOrCatchAll() {
        val photos = setOf(GlobalSearchContentKind.IMAGES_VIDEO)
        val everything = setOf(GlobalSearchContentKind.ANY_ATTACHMENT)

        assertTrue(globalAttachmentMatchesSelection("image/png", photos))
        assertFalse(globalAttachmentMatchesSelection("application/pdf", photos))
        assertTrue(globalAttachmentMatchesSelection("application/pdf", everything))
    }

    /** Modes that can surface a photo tile into a grid; file and audio modes stay one column. */
    @Test
    fun tilesOnlyWhenVisualMediaCanAppear() {
        assertTrue(globalAttachmentGridIsVisual(setOf(GlobalSearchContentKind.IMAGES_VIDEO)))
        assertTrue(globalAttachmentGridIsVisual(setOf(GlobalSearchContentKind.ANY_ATTACHMENT)))
        assertFalse(globalAttachmentGridIsVisual(setOf(GlobalSearchContentKind.FILES_DOCUMENTS)))
        assertFalse(globalAttachmentGridIsVisual(setOf(GlobalSearchContentKind.VOICE_AUDIO)))
    }

    /** Days run newest first, and so do the cards inside a day. */
    @Test
    fun groupsByDayNewestFirst() {
        val older = item("older", secondsAt(day = 1, hour = 9))
        val earlierSameDay = item("earlier", secondsAt(day = 3, hour = 8))
        val laterSameDay = item("later", secondsAt(day = 3, hour = 20))

        val days = groupGlobalAttachmentsByDay(listOf(older, earlierSameDay, laterSameDay), ZoneOffset.UTC)

        assertEquals(2, days.size)
        assertEquals(listOf("later", "earlier"), days.first().items.map { it.messageIdHex })
        assertEquals(listOf("older"), days.last().items.map { it.messageIdHex })
    }

    /** Two attachments of one message stay in index order inside their day. */
    @Test
    fun keepsAttachmentOrderWithinAMessage() {
        val first = item("msg", secondsAt(day = 2, hour = 10), attachmentIndex = 0)
        val second = item("msg", secondsAt(day = 2, hour = 10), attachmentIndex = 1)

        val days = groupGlobalAttachmentsByDay(listOf(second, first), ZoneOffset.UTC)

        assertEquals(listOf(0, 1), days.single().items.map { it.attachmentIndex })
    }

    private fun item(
        messageIdHex: String,
        timelineAt: ULong,
        attachmentIndex: Int = 0,
    ) = GlobalAttachmentItem(
        groupIdHex = "group",
        chatTitle = "Design review",
        messageIdHex = messageIdHex,
        attachmentIndex = attachmentIndex,
        mediaType = "image/png",
        label = "photo.png",
        timelineAt = timelineAt,
    )

    /** Seconds since the epoch at the given day-of-January and hour, in UTC. */
    private fun secondsAt(
        day: Int,
        hour: Int,
    ): ULong = ((day - 1) * SECONDS_PER_DAY + hour * SECONDS_PER_HOUR).toULong()

    private companion object {
        const val SECONDS_PER_DAY = 86_400
        const val SECONDS_PER_HOUR = 3_600
    }
}
