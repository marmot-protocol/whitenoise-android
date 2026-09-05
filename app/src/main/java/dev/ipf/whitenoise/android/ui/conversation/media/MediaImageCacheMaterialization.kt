package dev.ipf.whitenoise.android.ui.conversation.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.whitenoise.android.state.AttachmentDownloadPriority
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.cachedAttachmentPlaintext
import dev.ipf.whitenoise.android.state.runCatchingCancellable

/** Resolves cold cache metadata off-main and lets a rejected payload revoke its stale availability hint. */
@Composable
internal fun rememberImageAttachmentCacheAvailability(
    controller: ConversationController,
    messageIdHex: String,
    attachmentIndex: Int,
    sourceEpoch: ULong,
    alreadyDecoded: Boolean,
): MutableState<Boolean> {
    val cached =
        remember(controller, messageIdHex, attachmentIndex, sourceEpoch) {
            mutableStateOf(alreadyDecoded || controller.hasCachedAttachment(messageIdHex, attachmentIndex))
        }
    LaunchedEffect(controller, messageIdHex, attachmentIndex, sourceEpoch) {
        if (!cached.value) {
            cached.value =
                runCatchingCancellable {
                    controller.hasCachedAttachmentAfterHydration(messageIdHex, attachmentIndex)
                }.getOrDefault(false)
        }
    }
    return cached
}

/** Keeps cache-only materialization local even when an index entry is stale or fails authentication. */
internal suspend fun imageAttachmentBytes(
    controller: ConversationController,
    messageIdHex: String,
    attachmentIndex: Int,
    reference: MediaAttachmentReferenceFfi,
    mine: Boolean,
    priority: AttachmentDownloadPriority,
    allowNetwork: Boolean,
): ByteArray? {
    if (allowNetwork) return attachmentBytes(controller, messageIdHex, attachmentIndex, reference, mine, priority)
    val retained =
        if (mine) {
            controller
                .pendingAttachmentsList(messageIdHex)
                .getOrNull(attachmentIndex)
                ?.plaintextBytes
                ?.takeIf { it.isNotEmpty() }
        } else {
            null
        }
    return retained ?: controller.cachedAttachmentPlaintext(messageIdHex, attachmentIndex)
}
