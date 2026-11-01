@file:Suppress("MaxLineLength")

package dev.ipf.whitenoise.android.core

import dev.ipf.whitenoise.android.search.GlobalSearchContentKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
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

    /** Buckets follow the opened day: today, yesterday, this week, this month, then named months. */
    @Test
    fun sectionsNameRecentDaysThenMonths() {
        // Friday 13 February 1970 (day 44); weeks start on Monday 9 February (day 40).
        val calendar = GlobalAttachmentSections(LocalDate.of(1970, 2, 13), ZoneOffset.UTC, DayOfWeek.MONDAY)

        assertEquals(GlobalAttachmentSectionKey.Today, globalAttachmentSectionKey(secondsAt(44, 1), calendar))
        assertEquals(GlobalAttachmentSectionKey.Yesterday, globalAttachmentSectionKey(secondsAt(43, 23), calendar))
        assertEquals(GlobalAttachmentSectionKey.ThisWeek, globalAttachmentSectionKey(secondsAt(40, 0), calendar))
        assertEquals(GlobalAttachmentSectionKey.ThisMonth, globalAttachmentSectionKey(secondsAt(39, 23), calendar))
        assertEquals(
            GlobalAttachmentSectionKey.Month(YearMonth.of(1970, 1)),
            globalAttachmentSectionKey(secondsAt(20, 12), calendar),
        )
    }

    /** A timestamp ahead of the device clock still lists under Today rather than vanishing. */
    @Test
    fun futureTimestampsListUnderToday() {
        val calendar = GlobalAttachmentSections(LocalDate.of(1970, 2, 13), ZoneOffset.UTC, DayOfWeek.MONDAY)

        assertEquals(GlobalAttachmentSectionKey.Today, globalAttachmentSectionKey(secondsAt(50, 0), calendar))
    }

    /** Sectioning keeps the native order, including same-second album slots, and never re-sorts. */
    @Test
    fun sectionsKeepNativeOrderAcrossSameSecondSlots() {
        val calendar = GlobalAttachmentSections(LocalDate.of(1970, 2, 13), ZoneOffset.UTC, DayOfWeek.MONDAY)
        val second = secondsAt(44, 10)
        val items =
            listOf(
                item("b", second, attachmentIndex = 1, group = "g2"),
                item("b", second, attachmentIndex = 0, group = "g2"),
                item("a", second, attachmentIndex = 0, group = "g1"),
                item("old", secondsAt(43, 9)),
            )

        val sections = sectionGlobalAttachments(items, calendar)

        assertEquals(listOf(GlobalAttachmentSectionKey.Today, GlobalAttachmentSectionKey.Yesterday), sections.map { it.key })
        assertEquals(items.take(3), sections.first().items)
    }

    /** The section a timeline second falls in follows the device's zone, not UTC. */
    @Test
    fun sectionsFollowTheZone() {
        val tokyo = ZoneId.of("Asia/Tokyo")
        val calendar = GlobalAttachmentSections(LocalDate.of(1970, 2, 13), tokyo, DayOfWeek.MONDAY)

        // 20:00 UTC on 12 February is 05:00 on 13 February in Tokyo.
        assertEquals(GlobalAttachmentSectionKey.Today, globalAttachmentSectionKey(secondsAt(43, 20), calendar))
    }

    private fun item(
        messageIdHex: String,
        timelineAt: ULong,
        attachmentIndex: Int = 0,
        group: String = "group",
    ) = GlobalAttachmentItem(
        groupIdHex = group,
        chatTitle = "Design review",
        messageIdHex = messageIdHex,
        attachmentIndex = attachmentIndex,
        mediaType = "image/png",
        label = "photo.png",
        timelineAt = timelineAt,
    )

    /** Seconds since the epoch at the given day of 1970, counted from 1 January as day 1, and hour, in UTC. */
    private fun secondsAt(
        day: Int,
        hour: Int,
    ): ULong = ((day - 1) * SECONDS_PER_DAY + hour * SECONDS_PER_HOUR).toULong()

    private companion object {
        const val SECONDS_PER_DAY = 86_400
        const val SECONDS_PER_HOUR = 3_600
    }
}
