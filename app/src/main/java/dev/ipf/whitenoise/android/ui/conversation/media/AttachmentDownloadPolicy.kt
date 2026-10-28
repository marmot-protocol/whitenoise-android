package dev.ipf.whitenoise.android.ui.conversation.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import dev.ipf.whitenoise.android.state.AttachmentDownloadPriority
import dev.ipf.whitenoise.android.state.AttachmentTransferCancelledByUserException
import dev.ipf.whitenoise.android.state.AttachmentTransferState
import dev.ipf.whitenoise.android.state.AutomaticBacklogStoppedException
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.runCatchingCancellable
import kotlinx.coroutines.CancellationException

/**
 * Local bytes may always be materialized. A cache-missing own attachment may
 * bypass the per-media matrix, but not an explicit account backlog pause.
 */
internal fun shouldMaterializeAttachmentAutomatically(
    mine: Boolean,
    mediaAutoDownloadAllowed: Boolean,
    automaticDownloadsPaused: Boolean,
    hasCachedAttachment: Boolean = false,
    hasMaterializedFile: Boolean = false,
    hasRetainedPlaintext: Boolean = false,
): Boolean =
    hasCachedAttachment ||
        hasMaterializedFile ||
        hasRetainedPlaintext ||
        (!automaticDownloadsPaused && (mediaAutoDownloadAllowed || mine))

/**
 * An own file whose reference MDK has not projected yet (epoch 0) has no native target, so a fetch would only fail
 * and offer Retry. It stays preparing rather than downloadable, which also holds back its automatic fetch until the
 * projected reference arrives and the card, keyed by that epoch, asks once.
 */
internal fun ownFileAwaitingProjection(
    transferState: AttachmentTransferState,
    sourceEpoch: ULong,
    mine: Boolean,
): AttachmentTransferState =
    if (mine && sourceEpoch == 0uL && transferState == AttachmentTransferState.Remote) {
        AttachmentTransferState.Resolving
    } else {
        transferState
    }

/**
 * The one decision that lets a materialization reach the network (#2699).
 *
 * A request the reader made themselves always may. Automatic work is re-asked here, at the
 * transfer boundary, against the live policy — [automaticNetworkAllowed] is read when the transfer
 * starts, not when the row was composed — so a queued or retried automatic request cannot begin a
 * remote fetch once the connection has become metered. Every remote message-media renderer,
 * animated images included, routes its automatic bytes through this decision instead of inferring
 * safety from composition state.
 */
internal fun attachmentTransferAllowsNetwork(
    intent: AttachmentMaterializationIntent,
    automaticNetworkAllowed: Boolean,
): Boolean = intent == AttachmentMaterializationIntent.Interactive || automaticNetworkAllowed

/** One source of truth for policy-granted and user-granted materialization. */
internal enum class AttachmentMaterializationIntent {
    Idle,
    Automatic,
    Interactive,
    ;

    val shouldMaterialize: Boolean
        get() = this != Idle

    /** The reader asked for this download themselves, rather than automatic-download policy granting it. */
    val readerRequested: Boolean
        get() = this == Interactive

    val priority: AttachmentDownloadPriority
        get() =
            when (this) {
                Automatic -> AttachmentDownloadPriority.Automatic
                Interactive -> AttachmentDownloadPriority.Interactive
                Idle -> error("idle attachment intent has no download priority")
            }

    /** Policy can grant an idle intent, but tightening policy cannot revoke work already accepted. */
    fun withPolicyAllowed(allowed: Boolean): AttachmentMaterializationIntent =
        if (this == Idle && allowed) {
            Automatic
        } else {
            this
        }

    fun afterInteractiveRequest(): AttachmentMaterializationIntent = Interactive

    /**
     * MDK still holds the body parked from the reader's earlier download, so that download resumes as the reader's own.
     * Joining the park demands nothing new.
     */
    fun afterParkedDownloadFound(): AttachmentMaterializationIntent = Interactive

    /**
     * Only an explicit stop revokes accepted work: the gate's queued-automatic cancellation or the reader's own Cancel.
     * Any other cancellation is the composition leaving and keeps propagating.
     */
    fun afterProducerCancellation(cancellation: CancellationException): AttachmentMaterializationIntent {
        val stoppedExplicitly =
            generateSequence<Throwable>(cancellation) { it.cause }
                .any { it is AutomaticBacklogStoppedException || it is AttachmentTransferCancelledByUserException }
        if (!stoppedExplicitly) throw cancellation
        return Idle
    }
}

/**
 * Whether this device already holds an attachment's bytes, for the video and voice bubbles' "local bytes" input. It
 * starts from the Android-side caches, read synchronously, and then asks off the main thread whether MDK retains the
 * bytes, as a background automatic download leaves them (#3308). A bubble that finds them that way materializes from
 * local data even when its automatic network policy is off, so it plays offline instead of showing Download, as the
 * image and file bubbles already do. [key] restarts the check when the bubble's identity or source epoch changes.
 */
@Composable
internal fun rememberLocalAttachmentAvailability(
    controller: ConversationController,
    messageIdHex: String,
    attachmentIndex: Int,
    key: Any?,
): State<Boolean> {
    val local =
        remember(controller, messageIdHex, attachmentIndex, key) {
            mutableStateOf(controller.hasCachedAttachment(messageIdHex, attachmentIndex))
        }
    LaunchedEffect(controller, messageIdHex, attachmentIndex, key) {
        if (!local.value) {
            local.value =
                runCatchingCancellable {
                    controller.hasCachedAttachmentAfterHydration(messageIdHex, attachmentIndex)
                }.getOrDefault(false)
        }
    }
    return local
}

/**
 * Keeps accepted visual-media work monotonic across policy recomposition.
 * Restarting policy grants previously idle work; pausing never cancels an
 * active UI waiter. The gate reports queued automatic cancellation explicitly.
 */
@Composable
internal fun rememberAttachmentMaterializationIntent(
    identity: String,
    policyAllowsMaterialization: Boolean,
    onPolicyApplied: (Boolean) -> Unit = {},
): MutableState<AttachmentMaterializationIntent> {
    val intent =
        remember(identity) {
            mutableStateOf(
                AttachmentMaterializationIntent.Idle.withPolicyAllowed(policyAllowsMaterialization),
            )
        }
    val applied by rememberUpdatedState(onPolicyApplied)
    LaunchedEffect(identity, policyAllowsMaterialization) {
        intent.value = intent.value.withPolicyAllowed(policyAllowsMaterialization)
        // Reported in the same pass as the grant, so a caller can tell the intent has caught up with the policy.
        applied(policyAllowsMaterialization)
    }
    return intent
}
