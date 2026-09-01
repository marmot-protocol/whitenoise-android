package dev.ipf.whitenoise.android.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Owns navigation-scoped attachment-open intent, while transfers remain independently durable. */
@Suppress("TooManyFunctions") // Cohesive lifecycle boundary for one attachment-open intent.
internal class AttachmentOpenCoordinator(
    private val intentStore: AttachmentDownloadIntentStore,
    private val scope: CoroutineScope,
    private val enqueue: (AttachmentTransferRequest, AttachmentDownloadPriority) -> Unit,
    private val visibility: (AttachmentOpenDestination?, AttachmentOpenRequest) -> Boolean,
    // Every persisted intent mutation shares one injectable dispatcher so tests
    // can observe a revocation that normally settles after the click returns.
    private val persistence: CoroutineDispatcher = Dispatchers.IO,
) {
    @Volatile
    private var destination: AttachmentOpenDestination? = null

    private val openRequests = StalenessGuard()

    // staleness-exempt: observable open-intent version consumed by Compose.
    var revision by mutableIntStateOf(0)
        private set

    fun setDestination(next: AttachmentOpenDestination?) {
        if (destination == next) return
        destination = next
        revision += 1
        AttachmentOpenTrace.cancelOutside(next)
        scope.launch {
            withContext(persistence) {
                intentStore.retainOpenIntentsForCurrentDestination { destination }
            }
        }
    }

    /** Binds a transfer request to the currently visible destination generation. */
    fun openRequest(request: AttachmentTransferRequest): AttachmentOpenRequest? =
        destination
            ?.takeIf { it.matches(request) }
            ?.let { AttachmentOpenRequest(request, it.navigationGeneration) }

    /** Persists a fresh viewer intent and supersedes any older cancellation cleanup. */
    fun requestOpen(request: AttachmentTransferRequest): Boolean {
        val openRequest = openRequest(request) ?: return false
        // A fresh tap supersedes a cancel whose durable revocation has not
        // reached disk yet, so that revocation must not remove this new intent.
        openRequests.advance()
        AttachmentOpenTrace.begin(openRequest)
        intentStore.markOpenIntent(openRequest)
        AttachmentOpenTrace.phase(openRequest, AttachmentOpenPhase.RequestPersisted)
        enqueue(request, AttachmentDownloadPriority.Interactive)
        AttachmentOpenTrace.phase(openRequest, AttachmentOpenPhase.InteractiveQueueAdmitted)
        revision += 1
        return true
    }

    fun hasIntent(request: AttachmentOpenRequest): Boolean = intentStore.hasDispatchableOpenIntent(request)

    @Suppress("MaxLineLength") // Keep this single-argument expression in ktlint's required form.
    suspend fun claim(request: AttachmentOpenRequest): AttachmentOpenIntentClaim? = withContext(persistence) { intentStore.claimOpenIntent(request) }

    @Suppress("MaxLineLength") // Keep this single-argument expression in ktlint's required form.
    suspend fun consume(request: AttachmentOpenRequest): Boolean = withContext(persistence) { intentStore.consumeOpenIntent(request) }

    suspend fun beginInstallPermission(request: AttachmentOpenRequest): Boolean =
        withContext(persistence) { intentStore.beginInstallPermissionRequest(request) }

    suspend fun finishInstallPermission(request: AttachmentOpenRequest): Boolean =
        withContext(persistence) { intentStore.finishInstallPermissionRequest(request) }

    fun abandonInstallPermission(request: AttachmentOpenRequest) {
        intentStore.abandonInstallPermissionRequest(request)
        revision += 1
    }

    /**
     * Drops a pending viewer handoff the user cancelled. The revision bump
     * restarts the composition effect, which then finds no intent, so a
     * cancelled transfer cannot leave the card stuck in its opening state.
     * Clearing the persisted intent needs disk work, and it runs on this
     * coordinator's scope so a card that leaves the screen mid-cancel still
     * finishes the revocation.
     */
    fun cancelOpen(request: AttachmentOpenRequest) {
        AttachmentOpenTrace.finish(request, "cancelled_by_user")
        val openToken = openRequests.capture()
        scope.launch {
            withContext(persistence) {
                intentStore.consumeOpenIntentUnlessSuperseded(request) { !openRequests.isCurrent(openToken) }
            }
            revision += 1
        }
    }

    fun restore(request: AttachmentOpenRequest) = intentStore.restoreOpenIntent(request)

    fun isVisible(request: AttachmentOpenRequest): Boolean = visibility(destination, request)
}
