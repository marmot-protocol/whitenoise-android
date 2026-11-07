package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.whitenoise.android.core.IdentityFormatter
import dev.ipf.whitenoise.android.core.MessageProjector
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.ui.common.currentDateTimeDisplay
import dev.ipf.whitenoise.android.ui.conversation.messages.KeptMessageEntry
import dev.ipf.whitenoise.android.ui.conversation.messages.KeptMessageKey
import dev.ipf.whitenoise.android.ui.conversation.messages.KeptMessagePresentation
import java.time.ZoneId

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
    val locale = LocalConfiguration.current.locales[0]
    val use24Hour = currentDateTimeDisplay().use24Hour
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
                    timeLabel = IdentityFormatter.clockTime(record.recordedAt, locale, zone, use24Hour),
                    attachments = keptAttachmentPresentations(entry.message, controller, thumbnailRevision),
                )
        }
    }
}
