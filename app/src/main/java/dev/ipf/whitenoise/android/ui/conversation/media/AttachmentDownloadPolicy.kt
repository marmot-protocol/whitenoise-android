package dev.ipf.whitenoise.android.ui.conversation.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import dev.ipf.whitenoise.android.state.AttachmentDownloadPriority
import dev.ipf.whitenoise.android.state.AutomaticBacklogStoppedException
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

/** One source of truth for policy-granted and user-granted materialization. */
internal enum class AttachmentMaterializationIntent {
    Idle,
    Automatic,
    Interactive,
    ;

    val shouldMaterialize: Boolean
        get() = this != Idle

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

    /** Only the gate's explicit queued-automatic cancellation revokes accepted automatic work. */
    fun afterProducerCancellation(cancellation: CancellationException): AttachmentMaterializationIntent {
        val stoppedByBacklogControl =
            generateSequence<Throwable>(cancellation) { it.cause }
                .any { it is AutomaticBacklogStoppedException }
        if (!stoppedByBacklogControl) throw cancellation
        return Idle
    }
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
): MutableState<AttachmentMaterializationIntent> {
    val intent =
        remember(identity) {
            mutableStateOf(
                AttachmentMaterializationIntent.Idle.withPolicyAllowed(policyAllowsMaterialization),
            )
        }
    LaunchedEffect(identity, policyAllowsMaterialization) {
        intent.value = intent.value.withPolicyAllowed(policyAllowsMaterialization)
    }
    return intent
}
