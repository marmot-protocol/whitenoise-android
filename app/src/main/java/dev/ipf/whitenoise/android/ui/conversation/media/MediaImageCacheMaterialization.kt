package dev.ipf.whitenoise.android.ui.conversation.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.whitenoise.android.state.AttachmentDownloadPriority
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.cachedAttachmentPlaintext
import dev.ipf.whitenoise.android.state.retainedNativeAttachmentBytes
import dev.ipf.whitenoise.android.state.runCatchingCancellable

/** Resolves cold cache metadata off-main and lets a rejected payload revoke its stale availability hint. */
@Composable
internal fun rememberImageAttachmentCacheAvailability(
    controller: ConversationController,
    messageIdHex: String,
    attachmentIndex: Int,
    sourceEpoch: ULong,
    alreadyDecoded: Boolean,
): ImageCacheAvailability {
    val cached =
        remember(controller, messageIdHex, attachmentIndex, sourceEpoch) {
            mutableStateOf(alreadyDecoded || controller.hasCachedAttachment(messageIdHex, attachmentIndex))
        }
    val resolved =
        remember(controller, messageIdHex, attachmentIndex, sourceEpoch) { mutableStateOf(cached.value) }
    LaunchedEffect(controller, messageIdHex, attachmentIndex, sourceEpoch) {
        if (!cached.value) {
            cached.value =
                runCatchingCancellable {
                    controller.hasCachedAttachmentAfterHydration(messageIdHex, attachmentIndex)
                }.getOrDefault(false)
        }
        resolved.value = true
    }
    // Stable across recompositions: the readiness effect below keys on this holder.
    return remember(cached, resolved) { ImageCacheAvailability(cached, resolved) }
}

/**
 * True once the retained-bytes probes have answered and the materialization intent has caught up with the policy they
 * produced. The intent follows policy one effect pass later, so without this a file MDK holds would flash its
 * Download action for a frame between the probe's answer and the intent's grant.
 *
 * Call it after [rememberAttachmentMaterializationIntent]: effects run in declaration order, so both writes land in
 * the same pass.
 */
@Composable
internal fun rememberDownloadActionReady(
    availability: ImageCacheAvailability,
    policyAllowsMaterialization: Boolean,
): Boolean {
    val resolved by availability.resolved
    var ready by remember(availability) { mutableStateOf(false) }
    LaunchedEffect(availability, resolved, policyAllowsMaterialization) { ready = resolved }
    return ready
}

/**
 * Keeps cache-only materialization local even when an index entry is stale or fails authentication, reading host
 * copies first and MDK's own retention last.
 */
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
    // Host copies come first. MDK's own retention is the last local source, so media it holds still renders when
    // automatic downloads are off or the network is unavailable, without starting any transfer.
    return retained
        ?: controller.cachedAttachmentPlaintext(messageIdHex, attachmentIndex)
        ?: controller.retainedNativeAttachmentBytes(messageIdHex, attachmentIndex)
}
