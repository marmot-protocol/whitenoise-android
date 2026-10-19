package dev.ipf.whitenoise.android.notifications

import kotlinx.coroutines.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

private const val NOTIFICATION_TAG_LOG_LENGTH = 16

/**
 * Runs optional card-picture work inline or through the presenter's existing process-owned launcher.
 *
 * Detached work retains the original show reference until its body finishes or launch fails. The caller
 * keeps ownership of the initial registration; content, eligibility and platform writes stay with it.
 */
internal class NotificationEnrichmentRunner(
    private val enrichmentLauncher: (suspend () -> Unit) -> Unit,
) {
    /** Keeps ready-picture work in the caller and retains a show reference only for detached work. */
    suspend fun runOrLaunch(
        requiresRemoteAvatarResolution: Boolean,
        showToken: ConversationCardShowToken,
        enrich: suspend () -> Unit,
    ) {
        if (requiresRemoteAvatarResolution) {
            launchEnrichment(showToken, enrich)
        } else {
            enrich()
        }
    }

    /** Settles the detached reference once, including a launcher that executes the body before throwing. */
    @Suppress("TooGenericExceptionCaught") // Preserve the existing detached-work failure boundary.
    private fun launchEnrichment(
        showToken: ConversationCardShowToken,
        enrich: suspend () -> Unit,
    ) {
        if (!ConversationCardPostSynchronizer.retainShow(showToken)) return
        val showReferenceReleased = AtomicBoolean(false)
        val releaseShowReferenceOnce = {
            if (showReferenceReleased.compareAndSet(false, true)) {
                ConversationCardPostSynchronizer.releaseShow(showToken)
            }
        }
        try {
            enrichmentLauncher {
                try {
                    enrich()
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    notificationDebug {
                        "enrichment failed tag=${showToken.notificationTag.take(NOTIFICATION_TAG_LOG_LENGTH)} " +
                            "type=${failure.javaClass.simpleName}"
                    }
                } finally {
                    releaseShowReferenceOnce()
                }
            }
        } catch (cancellation: CancellationException) {
            releaseShowReferenceOnce()
            throw cancellation
        } catch (failure: RuntimeException) {
            releaseShowReferenceOnce()
            notificationDebug {
                "enrichment launch failed tag=${showToken.notificationTag.take(NOTIFICATION_TAG_LOG_LENGTH)} " +
                    "type=${failure.javaClass.simpleName}"
            }
        }
    }
}
