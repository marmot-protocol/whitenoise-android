package dev.ipf.whitenoise.android.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

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

    private val dispatchLifetimes = ConcurrentHashMap<AttachmentOpenRequest, StalenessGuard>()
    private val revokedRequests = ConcurrentHashMap.newKeySet<AttachmentOpenRequest>()
    private val userActions = StalenessGuard()

    // staleness-exempt: observable open-intent version consumed by Compose.
    var revision by mutableIntStateOf(0)
        private set

    fun setDestination(next: AttachmentOpenDestination?) {
        if (destination == next) return
        userActions.advance()
        dispatchLifetimes.values.forEach { it.advance() }
        dispatchLifetimes.clear()
        revokedRequests.clear()
        destination = next
        revision += 1
        AttachmentOpenTrace.cancelOutside(next)
        scope.launch {
            withContext(persistence) {
                intentStore.retainOpenIntentsForCurrentDestination { destination }
            }
        }
    }

    /** Reserves the newest viewer gesture while native admission runs, without creating a persisted open intent. */
    fun beginUserAction(): Long = userActions.advance()

    /** A newer tap or navigation invalidates delayed admission callbacks from older gestures. */
    fun isCurrentUserAction(token: Long): Boolean = userActions.isCurrent(token)

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
        userActions.advance()
        dispatchLifetimes.getOrPut(openRequest, ::StalenessGuard).advance()
        revokedRequests.remove(openRequest)
        AttachmentOpenTrace.begin(openRequest)
        intentStore.markOpenIntent(openRequest)
        AttachmentOpenTrace.phase(openRequest, AttachmentOpenPhase.RequestPersisted)
        enqueue(request, AttachmentDownloadPriority.Interactive)
        AttachmentOpenTrace.phase(openRequest, AttachmentOpenPhase.InteractiveQueueAdmitted)
        revision += 1
        return true
    }

    /** A cancelled gesture becomes undispatchable before its durable removal reaches disk. */
    fun hasIntent(request: AttachmentOpenRequest): Boolean {
        if (request in revokedRequests) return false
        return intentStore.hasDispatchableOpenIntent(request)
    }

    /** Captures this file gesture so cancellation or a newer tap fences an already-claimed external launch. */
    fun captureDispatchGuard(request: AttachmentOpenRequest): () -> Boolean {
        val lifetime = dispatchLifetimes.getOrPut(request, ::StalenessGuard)
        val token = lifetime.capture()
        return {
            dispatchLifetimes[request] === lifetime &&
                request !in revokedRequests &&
                lifetime.isCurrent(token) &&
                isVisible(request)
        }
    }

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
     * Fences this file immediately, then durably drops its pending handoff. The revision bump
     * restarts the composition effect, which then finds no intent, so a
     * cancelled transfer cannot leave the card stuck in its opening state.
     * Clearing the persisted intent needs disk work, and it runs on this
     * coordinator's scope so a card that leaves the screen mid-cancel still
     * finishes the revocation.
     */
    fun cancelOpen(request: AttachmentOpenRequest) {
        AttachmentOpenTrace.finish(request, "cancelled_by_user")
        val lifetime = dispatchLifetimes.getOrPut(request, ::StalenessGuard)
        val openToken = lifetime.advance()
        revokedRequests.add(request)
        revision += 1
        scope.launch {
            withContext(persistence) {
                intentStore.consumeOpenIntentUnlessSuperseded(request) { !lifetime.isCurrent(openToken) }
            }
            revision += 1
        }
    }

    /** Failed dispatch cannot restore an intent that cancellation has already revoked. */
    fun restore(request: AttachmentOpenRequest) {
        if (request !in revokedRequests) intentStore.restoreOpenIntent(request)
    }

    fun isVisible(request: AttachmentOpenRequest): Boolean = visibility(destination, request)
}
