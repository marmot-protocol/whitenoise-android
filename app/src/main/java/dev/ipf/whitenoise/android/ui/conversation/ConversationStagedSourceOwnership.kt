package dev.ipf.whitenoise.android.ui.conversation

import android.content.Context
import android.net.Uri
import dev.ipf.whitenoise.android.share.PrivateShareSendLease
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.runCatchingCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Hold platform bytes before preparation can suspend and a route exit can release the shelf. */
internal suspend fun acquireStagedSources(
    context: Context,
    uris: List<Uri>,
    account: String?,
): Result<PrivateShareSendLease?> =
    runCatchingCancellable {
        withContext(Dispatchers.IO) { PrivateShareSendLease.acquire(context, uris, account) }
    }

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
