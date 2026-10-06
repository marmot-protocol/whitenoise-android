package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.whitenoise.android.core.MessageProjector
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.ui.conversation.messages.KeptMessageEntry
import dev.ipf.whitenoise.android.ui.conversation.messages.KeptMessageKey
import dev.ipf.whitenoise.android.ui.conversation.messages.KeptMessagePresentation
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Presentation strings for every kept message currently on the stack, resolved once per
 * entry list. The card asks for these through a plain lambda, so they are computed here
 * rather than inside it, where no composable lookup would be allowed.
 */
@Composable
internal fun keptMessagePresentations(
    entries: List<KeptMessageEntry>,
    chatTitle: String,
    youLabel: String,
    isMine: (AppMessageRecordFfi) -> Boolean,
    senderName: (String) -> String,
    controller: ConversationController? = null,
    thumbnailRevision: Long = 0,
): Map<KeptMessageKey, KeptMessagePresentation> {
    val formatter = remember { DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT) }
    val zone = remember { ZoneId.systemDefault() }
    return entries.associate { entry ->
        key(entry.key) {
            val record = entry.message.record
            entry.key to
                KeptMessagePresentation(
                    authorName = if (isMine(record)) youLabel else senderName(record.sender),
                    body =
                        if (MessageProjector.isPendingMedia(record)) {
                            MessageProjector.mediaCaption(record).orEmpty()
                        } else {
                            record.plaintext
                        },
                    chatTitle = chatTitle,
                    timeLabel = keptMessageTimeLabel(record.recordedAt, formatter, zone),
                    attachments = keptAttachmentPresentations(entry.message, controller, thumbnailRevision),
                )
        }
    }
}

/** Wall-clock time of a kept message in the viewer's locale and zone. */
private fun keptMessageTimeLabel(
    recordedAt: ULong,
    formatter: DateTimeFormatter,
    zone: ZoneId,
): String =
    Instant
        .ofEpochSecond(recordedAt.toLong())
        .atZone(zone)
        .format(formatter)
