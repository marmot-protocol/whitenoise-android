package dev.ipf.whitenoise.android.ui.conversation.nostr

import android.content.ClipData
import android.os.Build
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import dev.ipf.marmotkit.MarkdownBlockFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.runCatchingCancellable
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
    val context = LocalContext.current
    val copyLabel by rememberUpdatedState(stringResource(R.string.copy))
    val scope = rememberCoroutineScope()
    val copyLink: (String) -> Unit = { destination ->
        scope.launch {
            runCatchingCancellable {
                clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(copyLabel, destination)))
            }.onSuccess {
                // Android 13+ already confirms clipboard writes; older supported versions need feedback.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show()
                }
            }.onFailure {
                Toast.makeText(context, R.string.not_copied, Toast.LENGTH_SHORT).show()
            }
        }
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
