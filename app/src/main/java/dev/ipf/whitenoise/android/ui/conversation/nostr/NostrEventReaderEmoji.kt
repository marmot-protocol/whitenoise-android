package dev.ipf.whitenoise.android.ui.conversation.nostr

import android.content.ClipData
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import dev.ipf.marmotkit.MarkdownBlockFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.whitenoise.android.ui.LocalReceivedEmoji
import dev.ipf.whitenoise.android.ui.MarkdownLinkLayoutRegistry
import dev.ipf.whitenoise.android.ui.MarkdownMessageBody
import dev.ipf.whitenoise.android.ui.ReceivedEmoji
import dev.ipf.whitenoise.android.ui.conversation.messages.readerTextSelectionLongPress
import dev.ipf.whitenoise.android.ui.markdownLinkDestinationAt
import kotlinx.coroutines.launch

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
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val copyLink: (String) -> Unit = { destination ->
        scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Link", destination))) }
    }
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
