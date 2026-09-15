package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.ipf.marmotkit.AppMessageRecordFfi
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
): Map<KeptMessageKey, KeptMessagePresentation> {
    val formatter = remember { DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT) }
    val zone = remember { ZoneId.systemDefault() }
    return remember(entries, chatTitle, youLabel, formatter, zone) {
        entries.associate { entry ->
            val record = entry.message.record
            entry.key to
                KeptMessagePresentation(
                    authorName = if (isMine(record)) youLabel else senderName(record.sender),
                    body = record.plaintext,
                    chatTitle = chatTitle,
                    timeLabel = keptMessageTimeLabel(record.recordedAt, formatter, zone),
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
