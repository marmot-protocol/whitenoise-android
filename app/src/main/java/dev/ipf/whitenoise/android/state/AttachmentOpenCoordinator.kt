package dev.ipf.whitenoise.android.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Owns navigation-scoped attachment-open intent, while transfers remain independently durable. */
internal class AttachmentOpenCoordinator(
    private val intentStore: AttachmentDownloadIntentStore,
    private val scope: CoroutineScope,
    private val enqueue: (AttachmentTransferRequest, AttachmentDownloadPriority) -> Unit,
    private val visibility: (AttachmentOpenDestination?, AttachmentOpenRequest) -> Boolean,
) {
    @Volatile
    private var destination: AttachmentOpenDestination? = null

    var revision by mutableIntStateOf(0)
        private set

    fun setDestination(next: AttachmentOpenDestination?) {
        if (destination == next) return
        destination = next
        revision += 1
        AttachmentOpenTrace.cancelOutside(next)
        scope.launch {
            withContext(Dispatchers.IO) {
                intentStore.retainOpenIntentsForCurrentDestination { destination }
            }
        }
    }

    fun openRequest(request: AttachmentTransferRequest): AttachmentOpenRequest? =
        destination
            ?.takeIf { it.matches(request) }
            ?.let { AttachmentOpenRequest(request, it.navigationGeneration) }

    fun requestOpen(request: AttachmentTransferRequest): Boolean {
        val openRequest = openRequest(request) ?: return false
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
    suspend fun claim(request: AttachmentOpenRequest): AttachmentOpenIntentClaim? = withContext(Dispatchers.IO) { intentStore.claimOpenIntent(request) }

    @Suppress("MaxLineLength") // Keep this single-argument expression in ktlint's required form.
    suspend fun consume(request: AttachmentOpenRequest): Boolean = withContext(Dispatchers.IO) { intentStore.consumeOpenIntent(request) }

    suspend fun beginInstallPermission(request: AttachmentOpenRequest): Boolean =
        withContext(Dispatchers.IO) { intentStore.beginInstallPermissionRequest(request) }

    suspend fun finishInstallPermission(request: AttachmentOpenRequest): Boolean =
        withContext(Dispatchers.IO) { intentStore.finishInstallPermissionRequest(request) }

    fun abandonInstallPermission(request: AttachmentOpenRequest) {
        intentStore.abandonInstallPermissionRequest(request)
        revision += 1
    }

    fun restore(request: AttachmentOpenRequest) = intentStore.restoreOpenIntent(request)

    fun isVisible(request: AttachmentOpenRequest): Boolean = visibility(destination, request)
}
