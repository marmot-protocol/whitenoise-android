package dev.ipf.whitenoise.android.core

import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.whitenoise.android.search.GlobalSearchContentKind
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * One attachment of one message as the account-wide Files & media library lists it (#2000). The
 * library is flat across chats, so every item carries the chat and sender it came from and the exact
 * message slot it belongs to. [reference] is the native locator the viewer and openers revalidate.
 */
data class GlobalAttachmentItem(
    val groupIdHex: String,
    val chatTitle: String,
    val messageIdHex: String,
    val attachmentIndex: Int,
    val mediaType: String,
    val label: String,
    val timelineAt: ULong,
    val sender: String = "",
    val sourceMessageIdHex: String? = null,
    val reference: MediaAttachmentReferenceFfi? = null,
)

/** How a library card draws an attachment: a square thumbnail, a file row or an audio row. */
enum class GlobalAttachmentPresentation {
    VISUAL,
    FILE,
    AUDIO,
}

/** The date bucket a library item is listed under, newest bucket first. */
sealed interface GlobalAttachmentSectionKey {
    /** The local calendar day the library was opened on, or a later timestamp from a skewed clock. */
    data object Today : GlobalAttachmentSectionKey

    /** The local calendar day before today. */
    data object Yesterday : GlobalAttachmentSectionKey

    /** Earlier days of the current week, which starts on [GlobalAttachmentSections.firstDayOfWeek]. */
    data object ThisWeek : GlobalAttachmentSectionKey

    /** Earlier days of the current month that fall before this week. */
    data object ThisMonth : GlobalAttachmentSectionKey

    /** Any earlier month, named by its year and month. */
    data class Month(
        val month: YearMonth,
    ) : GlobalAttachmentSectionKey
}

/** One run of consecutive items sharing a date bucket, in the order they were listed. */
data class GlobalAttachmentSection(
    val key: GlobalAttachmentSectionKey,
    val items: List<GlobalAttachmentItem>,
)

/** The clock and calendar a library groups its items with. */
data class GlobalAttachmentSections(
    val today: LocalDate,
    val zoneId: ZoneId,
    val firstDayOfWeek: DayOfWeek,
)

/** The single content kind an attachment belongs to, mirroring the search content filter. */
fun globalAttachmentContentKind(mediaType: String): GlobalSearchContentKind =
    when {
        mediaType.startsWith("image/", ignoreCase = true) || mediaType.startsWith("video/", ignoreCase = true) ->
            GlobalSearchContentKind.IMAGES_VIDEO
        mediaType.startsWith("audio/", ignoreCase = true) -> GlobalSearchContentKind.VOICE_AUDIO
        else -> GlobalSearchContentKind.FILES_DOCUMENTS
    }

/** Photos and videos get a thumbnail tile; audio gets the audio row; everything else a file row. */
fun globalAttachmentPresentation(mediaType: String): GlobalAttachmentPresentation =
    when (globalAttachmentContentKind(mediaType)) {
        GlobalSearchContentKind.IMAGES_VIDEO -> GlobalAttachmentPresentation.VISUAL
        GlobalSearchContentKind.VOICE_AUDIO -> GlobalAttachmentPresentation.AUDIO
        else -> GlobalAttachmentPresentation.FILE
    }

/** True when a video should draw the play badge over its tile. */
fun globalAttachmentIsVideo(mediaType: String): Boolean = mediaType.startsWith("video/", ignoreCase = true)

/** Whether a mode selection covers this attachment: the catch-all kind takes everything. */
fun globalAttachmentMatchesSelection(
    mediaType: String,
    kinds: Set<GlobalSearchContentKind>,
): Boolean = GlobalSearchContentKind.ANY_ATTACHMENT in kinds || globalAttachmentContentKind(mediaType) in kinds

/**
 * The library lays itself out as an adaptive grid whenever photos and videos can
 * appear, and as a single column for the file-only and audio-only modes.
 */
fun globalAttachmentGridIsVisual(kinds: Set<GlobalSearchContentKind>): Boolean =
    GlobalSearchContentKind.IMAGES_VIDEO in kinds || GlobalSearchContentKind.ANY_ATTACHMENT in kinds

/**
 * Splits [items] into date buckets without reordering them. The native history is already newest
 * first with a stable tie order, so a bucket starts wherever the key changes. A key seen again later,
 * only possible from a skewed clock, starts a new run rather than pulling items out of order.
 */
fun sectionGlobalAttachments(
    items: List<GlobalAttachmentItem>,
    calendar: GlobalAttachmentSections,
): List<GlobalAttachmentSection> {
    val sections = mutableListOf<GlobalAttachmentSection>()
    var key: GlobalAttachmentSectionKey? = null
    var run = mutableListOf<GlobalAttachmentItem>()
    items.forEach { item ->
        val itemKey = globalAttachmentSectionKey(item.timelineAt, calendar)
        val current = key
        if (current != null && itemKey != current) {
            sections += GlobalAttachmentSection(current, run)
            run = mutableListOf()
        }
        key = itemKey
        run += item
    }
    key?.let { sections += GlobalAttachmentSection(it, run) }
    return sections
}

/** The bucket one timeline second falls in, relative to [GlobalAttachmentSections.today]. */
fun globalAttachmentSectionKey(
    timelineAt: ULong,
    calendar: GlobalAttachmentSections,
): GlobalAttachmentSectionKey {
    val day = globalAttachmentDay(timelineAt, calendar.zoneId)
    val today = calendar.today
    val weekStart = today.with(TemporalAdjusters.previousOrSame(calendar.firstDayOfWeek))
    return when {
        !day.isBefore(today) -> GlobalAttachmentSectionKey.Today
        day == today.minusDays(1) -> GlobalAttachmentSectionKey.Yesterday
        !day.isBefore(weekStart) -> GlobalAttachmentSectionKey.ThisWeek
        YearMonth.from(day) == YearMonth.from(today) -> GlobalAttachmentSectionKey.ThisMonth
        else -> GlobalAttachmentSectionKey.Month(YearMonth.from(day))
    }
}

/** The local calendar day a timeline second falls on. */
fun globalAttachmentDay(
    timelineAt: ULong,
    zoneId: ZoneId,
): LocalDate =
    Instant
        .ofEpochSecond(timelineAt.toLong())
        .atZone(zoneId)
        .toLocalDate()
