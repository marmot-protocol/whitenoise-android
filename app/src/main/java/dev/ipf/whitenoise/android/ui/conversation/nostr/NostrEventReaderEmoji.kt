package dev.ipf.whitenoise.android.ui.conversation.nostr

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import dev.ipf.marmotkit.MarkdownBlockFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.whitenoise.android.ui.LocalReceivedEmoji
import dev.ipf.whitenoise.android.ui.MarkdownMessageBody
import dev.ipf.whitenoise.android.ui.ReceivedEmoji

/** A referenced event can use local artwork, never the containing message's attachment definitions. */
@Composable
@Suppress("FunctionNaming")
internal fun NostrEventReaderBlock(
    block: MarkdownBlockFfi,
    mentionDisplayName: (String) -> String?,
    onNostrProfileTap: (String) -> Unit,
) {
    CompositionLocalProvider(LocalReceivedEmoji provides ReceivedEmoji.None) {
        MarkdownMessageBody(
            document =
                MarkdownDocumentFfi(
                    blocks = listOf(block),
                    truncated = false,
                    blankLinesBefore = byteArrayOf(),
                ),
            mentionDisplayName = mentionDisplayName,
            onNostrProfileTap = onNostrProfileTap,
            useDecorativeBackgrounds = true,
        )
    }
}
