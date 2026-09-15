package dev.ipf.whitenoise.android.core

import dev.ipf.whitenoise.android.search.GlobalSearchContentKind
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * One attachment of one message as the chat-list search library lists it. The library
 * is flat across chats, so every item carries the chat it came from and the message it
 * belongs to; tapping a card jumps to that message.
 */
data class GlobalAttachmentItem(
    val groupIdHex: String,
    val chatTitle: String,
    val messageIdHex: String,
    val attachmentIndex: Int,
    val mediaType: String,
    val label: String,
    val timelineAt: ULong,
)

/** How a library card draws an attachment: a square thumbnail, a file row or an audio row. */
enum class GlobalAttachmentPresentation {
    VISUAL,
    FILE,
    AUDIO,
}

/** One day of the library, newest first, as the prototype's headers separate them. */
data class GlobalAttachmentDay(
    val day: LocalDate,
    val items: List<GlobalAttachmentItem>,
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
 * The prototype lays the library out as an adaptive grid whenever photos and videos can
 * appear, and as a single column for the file-only and audio-only modes.
 */
fun globalAttachmentGridIsVisual(kinds: Set<GlobalSearchContentKind>): Boolean =
    GlobalSearchContentKind.IMAGES_VIDEO in kinds || GlobalSearchContentKind.ANY_ATTACHMENT in kinds

/** Buckets items into day sections, newest day first and newest item first inside a day. */
fun groupGlobalAttachmentsByDay(
    items: List<GlobalAttachmentItem>,
    zoneId: ZoneId,
): List<GlobalAttachmentDay> =
    items
        .sortedWith(
            compareByDescending<GlobalAttachmentItem> { it.timelineAt }
                .thenBy { it.groupIdHex }
                .thenBy { it.messageIdHex }
                .thenBy { it.attachmentIndex },
        ).groupBy { item -> globalAttachmentDay(item.timelineAt, zoneId) }
        .map { (day, dayItems) -> GlobalAttachmentDay(day, dayItems) }

/** The local calendar day a timeline second falls on. */
fun globalAttachmentDay(
    timelineAt: ULong,
    zoneId: ZoneId,
): LocalDate =
    Instant
        .ofEpochSecond(timelineAt.toLong())
        .atZone(zoneId)
        .toLocalDate()
