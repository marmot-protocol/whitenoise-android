@file:Suppress("FunctionNaming") // Jetpack Compose functions intentionally use PascalCase.

package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.GlobalAttachmentItem
import dev.ipf.whitenoise.android.state.AttachmentTransferRequest
import dev.ipf.whitenoise.android.state.StoredAttachment
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.findNativeAttachment
import dev.ipf.whitenoise.android.state.runCatchingCancellable
import dev.ipf.whitenoise.android.ui.chats.GlobalAttachmentViewerHost
import dev.ipf.whitenoise.android.ui.chats.rememberGlobalAttachmentOpener
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Opens a stored attachment so it can be reviewed before removal, through the same pipelines the Files & media library
 * uses: photos and videos in the full-screen viewer, files in the app chosen for them, audio in the shared player.
 * Nothing is downloaded, since every listed attachment is already on the device. It returns the action a row's tap
 * calls; a tap while an earlier one is still resolving is ignored, and a result that arrives after the account changed
 * is dropped.
 */
@Composable
internal fun rememberStoredAttachmentPreview(appState: WhiteNoiseAppState): (StoredAttachment) -> Unit {
    val account = appState.activeAccountRef
    val chats = appState.chatListItems + appState.archivedChatListItems
    var shown by remember(account) { mutableStateOf<List<GlobalAttachmentItem>>(emptyList()) }
    val unavailable = { _: GlobalAttachmentItem -> appState.present(R.string.storage_open_failed) }
    val opener = rememberGlobalAttachmentOpener(appState, account, chats, shown, unavailable)
    opener?.let { GlobalAttachmentViewerHost(it, appState, shown, loading = false, onGoToMessage = null) }
    val scope = rememberCoroutineScope()
    val pending = remember(account) { arrayOfNulls<Job>(1) }
    return remember(opener, account) {
        { attachment ->
            if (opener != null && account != null && pending[0]?.isActive != true) {
                pending[0] =
                    scope.launch {
                        val item = resolveStoredAttachment(appState, account, attachment)
                        if (appState.activeAccountRef != account) return@launch
                        if (item == null) {
                            appState.present(R.string.storage_open_failed)
                        } else {
                            shown = listOf(item)
                            opener.items = shown
                            opener.open(item)
                        }
                    }
            }
        }
    }
}

/** The library item for [attachment], with the message reference MarmotKit holds for it, or null once it is gone. */
private suspend fun resolveStoredAttachment(
    appState: WhiteNoiseAppState,
    account: String,
    attachment: StoredAttachment,
): GlobalAttachmentItem? {
    val request =
        AttachmentTransferRequest(
            accountRef = account,
            groupIdHex = attachment.groupIdHex,
            messageIdHex = attachment.messageIdHex,
            attachmentIndex = attachment.attachmentIndex,
            sourceMessageIdHex = attachment.sourceMessageIdHex,
        )
    val match = runCatchingCancellable { appState.findNativeAttachment(request) }.getOrNull() ?: return null
    return GlobalAttachmentItem(
        groupIdHex = attachment.groupIdHex,
        chatTitle = appState.chatTitle(account, attachment.groupIdHex).orEmpty(),
        messageIdHex = attachment.messageIdHex,
        attachmentIndex = attachment.attachmentIndex,
        mediaType = match.reference.mediaType.ifBlank { attachment.mediaType },
        label = storageDisplayName(attachment.fileName).orEmpty(),
        timelineAt = match.timelineAt,
        sender = match.sender,
        sourceMessageIdHex = attachment.sourceMessageIdHex,
        reference = match.reference,
    )
}
