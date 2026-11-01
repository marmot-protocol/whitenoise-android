@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.chats

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.audio.VoicePlaybackController
import dev.ipf.whitenoise.android.core.GlobalAttachmentItem
import dev.ipf.whitenoise.android.core.GlobalAttachmentPresentation
import dev.ipf.whitenoise.android.core.GroupTitleCopy
import dev.ipf.whitenoise.android.core.globalAttachmentPresentation
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.presentFailure
import dev.ipf.whitenoise.android.state.runCatchingCancellable
import dev.ipf.whitenoise.android.state.voicePlaybackSource
import dev.ipf.whitenoise.android.ui.common.rememberGroupTitleCopy
import dev.ipf.whitenoise.android.ui.conversation.media.AttachmentOpener
import dev.ipf.whitenoise.android.ui.conversation.media.FullScreenMediaViewer
import dev.ipf.whitenoise.android.ui.conversation.media.MediaViewerPage
import dev.ipf.whitenoise.android.ui.conversation.media.materializeDocumentFile
import dev.ipf.whitenoise.android.ui.conversation.media.materializeVoiceAttachment
import dev.ipf.whitenoise.android.ui.conversation.media.rememberAttachmentOpener
import dev.ipf.whitenoise.android.ui.conversation.media.voicePlaybackKey
import dev.ipf.whitenoise.android.ui.medialibrary.downloadLibraryFile
import dev.ipf.whitenoise.android.ui.medialibrary.presentLibraryFileOpenOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** One open full-screen viewer: the chat's pages from the current results and where to start. */
internal data class GlobalAttachmentViewerRequest(
    val controller: ConversationController,
    val pages: List<MediaViewerPage>,
    val startIndex: Int,
)

/**
 * Opens library items through the conversation pipelines a chat already uses (#2000). Each chat gets one
 * non-started conversation controller, created on first use and released with the library session, so a
 * tap downloads only the item it names: photos and videos open the viewer over that chat's listed visual
 * items, files download on the first tap and open on the next, and audio plays through the shared player.
 */
internal class GlobalAttachmentOpener(
    private val appState: WhiteNoiseAppState,
    private val account: String,
    private val context: Context,
    private val scope: CoroutineScope,
    private val openFile: AttachmentOpener,
    private val titleCopy: GroupTitleCopy,
) : AutoCloseable {
    var viewer by mutableStateOf<GlobalAttachmentViewerRequest?>(null)
        private set

    /** Chats the library can name; an item from a chat missing here falls back to opening its message. */
    var chats: List<ChatListItem> = emptyList()

    /** The current results, in display order, so the viewer pages through what the user is looking at. */
    var items: List<GlobalAttachmentItem> = emptyList()

    /** Opens the item's message instead, when no controller can own the item. */
    var onViewInChat: (GlobalAttachmentItem) -> Unit = {}

    private val controllers = mutableMapOf<String, ConversationController>()
    private var closed = false

    /** Routes one tap by presentation; an item whose chat or reference is gone opens its message instead. */
    fun open(item: GlobalAttachmentItem) {
        val reference = item.reference
        val controller = controllerFor(item.groupIdHex)
        if (reference == null || controller == null) {
            onViewInChat(item)
            return
        }
        when (globalAttachmentPresentation(item.mediaType)) {
            GlobalAttachmentPresentation.VISUAL -> showViewer(item, controller)
            GlobalAttachmentPresentation.FILE -> scope.launch { openDocument(item, controller) }
            GlobalAttachmentPresentation.AUDIO -> scope.launch { playAudio(item, controller) }
        }
    }

    /** Closes the viewer without releasing the chat's controller, which later taps reuse. */
    fun dismissViewer() {
        viewer = null
    }

    /** Pages through the chat's listed photos and videos, starting at the tapped one. */
    private fun showViewer(
        item: GlobalAttachmentItem,
        controller: ConversationController,
    ) {
        val pages =
            items
                .filter {
                    it.groupIdHex == item.groupIdHex &&
                        it.reference != null &&
                        globalAttachmentPresentation(it.mediaType) == GlobalAttachmentPresentation.VISUAL
                }.map { it.viewerPage() }
        val start =
            pages.indexOfFirst { page ->
                page.messageIdHex == item.messageIdHex && page.attachmentIndex == item.attachmentIndex
            }
        if (start >= 0) viewer = GlobalAttachmentViewerRequest(controller, pages, start)
    }

    /** A file not on the device yet is downloaded by the first tap and opened by the next, as in a chat. */
    @Suppress("ReturnCount") // A missing reference, a first-tap download and a failed open each end the tap.
    private suspend fun openDocument(
        item: GlobalAttachmentItem,
        controller: ConversationController,
    ) {
        val reference = item.reference ?: return
        val message = item.messageIdHex
        val index = item.attachmentIndex
        val fetch = suspend { materializeDocumentFile(context, controller, message, index, reference, retained = null) }
        if (!controller.hasCachedAttachmentAfterHydration(item.messageIdHex, item.attachmentIndex)) {
            downloadLibraryFile(appState) { fetch() }
            return
        }
        val outcome =
            runCatchingCancellable { openFile(fetch(), reference.mediaType, reference.fileName, null, null) }
                .getOrElse { error ->
                    appState.presentFailure(R.string.media_couldnt_open, "GLOBAL_LIBRARY_FILE_OPEN", error)
                    return
                }
        if (!closed) appState.presentLibraryFileOpenOutcome(outcome)
    }

    /** Downloads the clip if needed, then plays it through the app's one voice player. */
    @Suppress("ReturnCount") // Each suspension can lose the reference, the clip, the opener or the chat's owner.
    private suspend fun playAudio(
        item: GlobalAttachmentItem,
        controller: ConversationController,
    ) {
        val reference = item.reference ?: return
        val file =
            runCatchingCancellable {
                val mine = item.mine()
                val index = item.attachmentIndex
                materializeVoiceAttachment(context, controller, item.messageIdHex, index, reference, mine)
            }.getOrElse { error ->
                appState.presentFailure(R.string.shared_media_voice_failed, "GLOBAL_LIBRARY_VOICE_LOAD", error)
                return
            }
        if (closed) return
        val source = controller.voicePlaybackSource(appState, item.messageIdHex, titleCopy) ?: return
        val key = voicePlaybackKey(item.messageIdHex, item.attachmentIndex, reference.sourceEpoch)
        VoicePlaybackController.play(key, file, source = source)
    }

    /** The chat's controller, created once per chat while the active account is still the library's. */
    private fun controllerFor(groupIdHex: String): ConversationController? {
        if (closed || appState.activeAccountRef != account) return null
        return controllers[groupIdHex] ?: chats
            .firstOrNull { it.group.groupIdHex.equals(groupIdHex, ignoreCase = true) }
            ?.let { ConversationController(appState = appState, initialGroup = it.group, accountRefOverride = account) }
            ?.also { controllers[groupIdHex] = it }
    }

    /** True for the active account's own attachment. */
    private fun GlobalAttachmentItem.mine(): Boolean {
        val own = appState.activeAccount?.accountIdHex
        return sender.equals(own, ignoreCase = true)
    }

    /** The viewer's page for a listed visual item. */
    private fun GlobalAttachmentItem.viewerPage(): MediaViewerPage =
        MediaViewerPage(
            messageIdHex = messageIdHex,
            attachmentIndex = attachmentIndex,
            reference = checkNotNull(reference),
            mine = mine(),
            sender = sender,
            recordedAt = timelineAt,
        )

    /** Releases every per-chat controller; a closed opener ignores later taps and late results. */
    override fun close() {
        closed = true
        viewer = null
        controllers.values.forEach { it.onCleared() }
        controllers.clear()
    }
}

/** One opener per account and runtime, closed when the library leaves composition. */
@Composable
internal fun rememberGlobalAttachmentOpener(
    appState: WhiteNoiseAppState,
    account: String?,
    chats: List<ChatListItem>,
    items: List<GlobalAttachmentItem>,
    onViewInChat: (GlobalAttachmentItem) -> Unit,
): GlobalAttachmentOpener? {
    val active = account?.takeIf { it == appState.activeAccountRef } ?: return null
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val openFile = rememberAttachmentOpener()
    val titleCopy = rememberGroupTitleCopy()
    val currentViewInChat by rememberUpdatedState(onViewInChat)
    val opener =
        remember(active, appState.runtimeGeneration) {
            GlobalAttachmentOpener(appState, active, context, scope, openFile, titleCopy)
        }
    opener.chats = chats
    opener.items = items
    opener.onViewInChat = { currentViewInChat(it) }
    DisposableEffect(opener) { onDispose { opener.close() } }
    return opener
}

/** The full-screen viewer for a library tap, with Go to Message leaving for the item's chat. */
@Composable
internal fun GlobalAttachmentViewerHost(
    opener: GlobalAttachmentOpener,
    appState: WhiteNoiseAppState,
    onGoToMessage: (groupIdHex: String, messageIdHex: String) -> Unit,
) {
    val request = opener.viewer ?: return
    FullScreenMediaViewer(
        controller = request.controller,
        appState = appState,
        pages = request.pages,
        startIndex = request.startIndex,
        onDismiss = opener::dismissViewer,
        onGoToMessage = { page ->
            opener.dismissViewer()
            onGoToMessage(request.controller.group.groupIdHex, page.messageIdHex)
        },
    )
}
