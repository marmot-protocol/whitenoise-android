package dev.ipf.whitenoise.android.ui.medialibrary

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.media.AttachmentPlaintext
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.runCatchingCancellable
import dev.ipf.whitenoise.android.ui.conversation.media.attachmentSource
import dev.ipf.whitenoise.android.ui.conversation.media.presentMediaLaunchFailure
import dev.ipf.whitenoise.android.ui.conversation.media.shareAttachmentExternally
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The attachment a library row stands for, which stays the same while the row recomposes with fresh data. */
internal fun SharedMediaRow.attachmentKey() = messageIdHex to attachmentIndex

/**
 * The Files tab's shares, owned by the tab rather than by its rows: a row scrolled out of the list keeps its share
 * staging, and the row shows it as running again when it returns. [scope] belongs to the tab, so leaving the library
 * still cancels every share and deletes what it staged. One attachment stages at most one share at a time.
 */
@Stable
internal class LibraryFileShares(
    private val scope: CoroutineScope,
    private val share: suspend (SharedMediaRow) -> Unit,
) {
    private var running by mutableStateOf<Set<Pair<String, Int>>>(emptySet())

    /** Whether a share of [row]'s attachment is being staged. */
    fun isSharing(row: SharedMediaRow): Boolean = row.attachmentKey() in running

    /** Starts sharing [row]'s attachment unless a share of it is already being staged. */
    fun start(row: SharedMediaRow) {
        val key = row.attachmentKey()
        if (key in running) return
        // Recorded before it starts, so the row shows the share as running before it can finish or fail.
        val job =
            scope.launch(start = CoroutineStart.LAZY) {
                try {
                    share(row)
                } finally {
                    running = running - key
                }
            }
        running = running + key
        job.start()
    }
}

/** Returns the library's current row for a shared file's slot and reference, or null once it is gone. */
internal typealias LibraryFileResolver = suspend (SharedMediaRow) -> SharedMediaRow?

/** Resolves a shared file from MDK's attachment history, not from the rows the tab shows, which a reload empties. */
internal fun GroupAttachmentPager.libraryFileResolver(accountId: () -> String?): LibraryFileResolver =
    { row ->
        val slot = row.messageIdHex to row.attachmentIndex.toUInt()
        projectSlots(setOf(slot)) { entries ->
            val account = accountId()
            withContext(Dispatchers.Default) { attachmentLibraryTiles(entries, account).files }
        }.firstOrNull { it.attachmentKey() == row.attachmentKey() && it.reference == row.reference }
    }

/**
 * The Files tab's shares for [controller]'s conversation. Each streams the verified file under its original name and
 * opens the share sheet only while the conversation still owns its foreground presentation and, when [resolveFile]
 * is given, the file is still in the attachment history. A failure is reported only while the conversation still
 * owns its presentation.
 */
@Composable
internal fun rememberLibraryFileShares(
    controller: ConversationController,
    appState: WhiteNoiseAppState,
    resolveFile: LibraryFileResolver? = null,
): LibraryFileShares {
    val context = LocalContext.current
    val chooserTitle by rememberUpdatedState(stringResource(R.string.shared_media_share))
    // The route builds a fresh resolver on each composition, so it is read at launch rather than keyed on.
    val currentResolver by rememberUpdatedState(resolveFile)
    val scope = rememberCoroutineScope()
    return remember(scope, context, controller, appState) {
        LibraryFileShares(scope) { row ->
            runCatchingCancellable {
                shareLibraryFile(
                    context = context,
                    accountRef = checkNotNull(controller.conversationAccountRef),
                    row = row,
                    chooserTitle = chooserTitle,
                    currentRow = {
                        val owned = { controller.ownsRetainedForegroundPresentation }
                        val resolve = currentResolver
                        if (resolve == null) row.takeIf { owned() } else resolve(row)?.takeIf { owned() }
                    },
                ) {
                    attachmentSource(controller, row.messageIdHex, row.attachmentIndex, row.reference, row.mine)
                }
            }.onFailure { error ->
                if (controller.ownsRetainedForegroundPresentation) {
                    appState.presentMediaLaunchFailure(R.string.media_couldnt_open, "MEDIA_LIBRARY_FILE_SHARE", error)
                }
            }
        }
    }
}

/** Streams a verified file under its original name while [currentRow] still identifies [row]. */
internal suspend fun shareLibraryFile(
    context: Context,
    accountRef: String,
    row: SharedMediaRow,
    chooserTitle: String,
    currentRow: suspend () -> SharedMediaRow?,
    source: suspend () -> AttachmentPlaintext,
) = shareAttachmentExternally(
    context = context,
    accountRef = accountRef,
    attachmentIndex = row.attachmentIndex,
    reference = row.reference,
    chooserTitle = chooserTitle,
    isCurrent = { currentRow()?.attachmentKey() == row.attachmentKey() },
    plaintext = source,
)
