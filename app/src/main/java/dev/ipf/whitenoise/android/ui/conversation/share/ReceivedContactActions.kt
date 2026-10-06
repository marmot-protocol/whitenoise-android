package dev.ipf.whitenoise.android.ui.conversation.share

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.runCatchingCancellable
import dev.ipf.whitenoise.android.ui.conversation.media.fileProviderUri
import dev.ipf.whitenoise.android.ui.conversation.media.localAttachmentBytes
import dev.ipf.whitenoise.android.ui.conversation.media.materializeMediaFile
import dev.ipf.whitenoise.android.ui.conversation.media.saveDocumentToDownloads
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class ReceivedContactAction { View, Add, Save }

/**
 * Reads a raw `.vcf` attachment's contact from bytes this device already holds,
 * so a file sent without the name/phone caption still draws the contact card.
 * Never starts a download: the file card's Documents policy owns fetching, and
 * each cache revision re-reads until the bytes land.
 */
@Composable
internal fun rememberLocalVCardContact(
    controller: ConversationController,
    appState: WhiteNoiseAppState,
    messageIdHex: String,
    attachment: IndexedValue<MediaAttachmentReferenceFfi>?,
    mine: Boolean,
): SharedContact? {
    var contact by remember(controller, messageIdHex, attachment) { mutableStateOf<SharedContact?>(null) }
    val cacheRevision by appState.mediaCacheRevision.collectAsState()
    LaunchedEffect(controller, messageIdHex, attachment, cacheRevision) {
        if (attachment == null || contact != null) {
            return@LaunchedEffect
        }
        val bytes =
            runCatchingCancellable {
                localAttachmentBytes(controller, messageIdHex, attachment.index, mine)
            }.getOrNull() ?: return@LaunchedEffect
        contact = withContext(Dispatchers.Default) { parseSingleVCard(bytes) }
    }
    return contact
}

/** The card owns one exact encrypted VCF and starts its download only after an explicit action. */
@Composable
// Compose UI and native fetch share one guarded action state machine.
@Suppress("FunctionNaming", "CyclomaticComplexMethod")
internal fun ReceivedContactActions(
    contact: SharedContact,
    messageIdHex: String,
    attachmentIndex: Int,
    reference: MediaAttachmentReferenceFfi,
    mine: Boolean,
    controller: ConversationController,
    appState: WhiteNoiseAppState,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ownerAccount = controller.boundAccountRef
    val ownerGroup = controller.group.groupIdHex
    var busy by remember(controller, messageIdHex, attachmentIndex, reference.sourceEpoch) { mutableStateOf(false) }
    var error by
        remember(controller, messageIdHex, attachmentIndex, reference.sourceEpoch) {
            mutableStateOf<String?>(null)
        }
    val unavailable = stringResource(R.string.media_couldnt_load)
    val invalid = stringResource(R.string.contact_invalid_vcard)
    val failed = stringResource(R.string.contact_action_failed)
    val noHandler = stringResource(R.string.media_no_app_to_open)
    val saved = stringResource(R.string.contact_vcf_saved)

    /** Recheck the destination after the potentially slow native fetch and before any external activity. */
    fun ownerCurrent(): Boolean =
        controller.boundAccountRef == ownerAccount &&
            controller.group.groupIdHex == ownerGroup &&
            appState.activeAccountRef == ownerAccount

    /** Start one exact-attachment action; cancellation cannot dispatch a stale contact to Android. */
    fun runAction(action: ReceivedContactAction) {
        if (busy || !ownerCurrent()) return
        busy = true
        error = null
        scope.launch {
            try {
                val file = materializeMediaFile(context, controller, messageIdHex, attachmentIndex, reference, mine)
                if (!ownerCurrent()) return@launch
                if (file == null) {
                    error = unavailable
                    return@launch
                }
                if (action == ReceivedContactAction.Save) {
                    val success =
                        withContext(Dispatchers.IO) {
                            saveDocumentToDownloads(context, file, reference.fileName, VCARD_MIME_TYPE)
                        }
                    if (ownerCurrent()) {
                        if (success) appState.present(saved) else error = failed
                    }
                    return@launch
                }
                val parsed = withContext(Dispatchers.IO) { validatedReceivedVCard(file) }
                if (!ownerCurrent()) return@launch
                if (parsed == null) {
                    error = invalid
                    return@launch
                }
                val uri = fileProviderUri(context, file)
                val intent =
                    if (action == ReceivedContactAction.View) {
                        viewReceivedContactIntent(uri)
                    } else {
                        addReceivedContactIntent(uri, parsed)
                    }
                when (launchReceivedContactIntent(intent, context::startActivity)) {
                    ContactLaunchResult.Started -> Unit
                    ContactLaunchResult.NoHandler -> error = noHandler
                    ContactLaunchResult.Failed -> error = failed
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: RuntimeException) {
                if (ownerCurrent()) error = failed
            } finally {
                busy = false
            }
        }
    }

    ContactMessageBubble(
        contact = contact,
        busy = busy,
        error = error,
        onView = { runAction(ReceivedContactAction.View) },
        onAdd = { runAction(ReceivedContactAction.Add) },
        onSave = { runAction(ReceivedContactAction.Save) },
    )
}
