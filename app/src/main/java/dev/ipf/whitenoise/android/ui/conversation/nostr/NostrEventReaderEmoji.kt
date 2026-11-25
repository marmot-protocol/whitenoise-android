package dev.ipf.whitenoise.android.ui.conversation.nostr

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.ipf.marmotkit.MarkdownBlockFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.LocalReceivedEmoji
import dev.ipf.whitenoise.android.ui.MarkdownLinkLayoutRegistry
import dev.ipf.whitenoise.android.ui.MarkdownMessageBody
import dev.ipf.whitenoise.android.ui.ReceivedEmoji
import dev.ipf.whitenoise.android.ui.common.rememberClipboardCopier
import dev.ipf.whitenoise.android.ui.conversation.messages.readerTextSelectionLongPress
import dev.ipf.whitenoise.android.ui.markdownLinkDestinationAt

/** A referenced event can use local artwork, never the containing message's attachment definitions. */
@Composable
@Suppress("FunctionNaming")
internal fun NostrEventReaderBlock(
    block: MarkdownBlockFfi,
    mentionDisplayName: (String) -> String?,
    onNostrProfileTap: (String) -> Unit,
    onWebLinkActivated: ((String) -> Boolean)? = null,
) {
    val layouts = remember(block) { MarkdownLinkLayoutRegistry() }
    val clipboard = rememberClipboardCopier()
    val copyLabel by rememberUpdatedState(stringResource(R.string.copy))
    val copyLink: (String) -> Unit = { destination -> clipboard.copy(destination, copyLabel) }
    CompositionLocalProvider(LocalReceivedEmoji provides ReceivedEmoji.None) {
        MarkdownMessageBody(
            document =
                MarkdownDocumentFfi(
                    blocks = listOf(block),
                    truncated = false,
                    blankLinesBefore = byteArrayOf(),
                ),
            modifier =
                Modifier.readerTextSelectionLongPress(block) { position ->
                    markdownLinkDestinationAt(layouts.values, position)?.let { destination ->
                        copyLink(destination)
                        true
                    } ?: false
                },
            onLinkTextLayoutChanged = layouts.report,
            onCopyLink = copyLink,
            mentionDisplayName = mentionDisplayName,
            onNostrProfileTap = onNostrProfileTap,
            useDecorativeBackgrounds = true,
            onWebLinkActivated = onWebLinkActivated,
        )
    }
}
