package dev.ipf.whitenoise.android.ui.conversation

import dev.ipf.whitenoise.android.share.PrivateShareSendLease
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Keeps private platform sources alive for each retained queue owner until acceptance or discard. */
internal fun retainStagedSources(
    appState: WhiteNoiseAppState,
    controller: ConversationController,
    sourceLease: PrivateShareSendLease?,
    seeded: List<ConversationController.QueuedAttachmentSend>,
): List<() -> Unit>? {
    val releases =
        sourceLease?.ownerReleases(seeded.size) { lease ->
            appState.launchMutation { withContext(Dispatchers.IO) { lease.release() } }
        }
    seeded.forEachIndexed { index, queued ->
        releases?.get(index)?.let { release ->
            if (!controller.retainQueuedAttachmentSource(queued, release)) release()
        }
    }
    return releases
}
