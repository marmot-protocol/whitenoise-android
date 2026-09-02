package dev.ipf.whitenoise.android.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Exact Android-owned identity of one received-APK installer request. */
internal data class AttachmentInstallerHandoffRequest(
    val transfer: AttachmentTransferRequest,
    val sourceEpoch: ULong,
)

/**
 * Owns the one app-scoped received-APK installer request independently of any
 * conversation route or attachment composable.
 */
@Suppress("TooManyFunctions") // Cohesive lifecycle boundary for one installer handoff.
internal class AttachmentInstallerHandoffCoordinator(
    private val intentStore: AttachmentDownloadIntentStore,
    private val scope: CoroutineScope,
    private val enqueue: (AttachmentTransferRequest, AttachmentDownloadPriority) -> Unit,
    private val foregroundEligible: () -> Boolean,
    private val persistence: CoroutineDispatcher = Dispatchers.IO,
) {
    private val requests = StalenessGuard()

    @Volatile
    private var cancelledTransfer: AttachmentTransferRequest? = null

    @Volatile
    private var queuedRequest: AttachmentInstallerHandoffRequest? = null

    private val scheduledRequests = mutableSetOf<AttachmentInstallerHandoffRequest>()

    // staleness-exempt: observable handoff version consumed by Compose.
    var revision by mutableIntStateOf(0)
        private set

    /** Persists off the UI thread before admitting work, superseding an older APK tap. */
    fun request(request: AttachmentInstallerHandoffRequest): Boolean {
        // Fence an already-queued cancellation before the new record becomes
        // visible, so it cannot consume this same-identity re-tap.
        val token = requests.advance()
        cancelledTransfer = null
        queuedRequest = request
        revision += 1
        scope.launch {
            val committed =
                withContext(persistence) {
                    intentStore.markInstallerHandoffUnlessSuperseded(request) {
                        !requests.isCurrent(token)
                    }
                }
            if (!requests.isCurrent(token)) return@launch
            queuedRequest = null
            if (committed) {
                scheduledRequests += request
                enqueue(request.transfer, AttachmentDownloadPriority.Interactive)
            }
            revision += 1
        }
        return true
    }

    /** Restarts a persisted handoff's durable transfer once in a replacement process. */
    fun ensureTransfer(request: AttachmentInstallerHandoffRequest) {
        if (
            request.transfer != cancelledTransfer &&
            intentStore.hasInstallerHandoff(request) &&
            scheduledRequests.add(request)
        ) {
            enqueue(request.transfer, AttachmentDownloadPriority.Interactive)
        }
    }

    /** Restores one process-owned observer from the exact persisted identity. */
    fun pending(): AttachmentInstallerHandoffRequest? =
        (queuedRequest ?: intentStore.pendingInstallerHandoff())
            ?.takeUnless { it.transfer == cancelledTransfer }

    /** True while this card owns the current app-scoped pending indication. */
    fun hasPending(request: AttachmentInstallerHandoffRequest): Boolean {
        revision
        return request.transfer != cancelledTransfer &&
            (queuedRequest == request || intentStore.hasInstallerHandoff(request))
    }

    /** Bridges the short interval before WorkManager publishes its generation. */
    @Suppress("MaxLineLength")
    fun hasInteractiveTransfer(request: AttachmentInstallerHandoffRequest): Boolean = intentStore.isInteractive(request.transfer)

    /** Claims the one final external launch boundary. */
    suspend fun claim(request: AttachmentInstallerHandoffRequest): AttachmentOpenIntentClaim? =
        withContext(persistence) {
            if (request.transfer == cancelledTransfer) null else intentStore.claimInstallerHandoff(request)
        }.also {
            if (it != null) {
                revision += 1
            }
        }

    /** Consumes an explicit cancellation or deterministic terminal outcome. */
    suspend fun consume(request: AttachmentInstallerHandoffRequest): Boolean =
        withContext(persistence) { intentStore.consumeInstallerHandoff(request) }
            .also {
                if (it) {
                    scheduledRequests -= request
                    revision += 1
                }
            }

    /** Persists a Settings round trip before White Noise leaves the foreground. */
    suspend fun beginInstallPermission(request: AttachmentInstallerHandoffRequest): Boolean =
        withContext(persistence) { intentStore.beginInstallerPermissionHandoff(request) }

    /** Clears Settings recovery immediately before the installer launch. */
    suspend fun finishInstallPermission(request: AttachmentInstallerHandoffRequest): Boolean =
        withContext(persistence) { intentStore.finishInstallerPermissionHandoff(request) }

    /** Makes an interrupted Settings handoff visible to a replacement owner. */
    fun abandonInstallPermission(request: AttachmentInstallerHandoffRequest) {
        intentStore.abandonInstallerPermissionHandoff(request)
        revision += 1
    }

    /** Re-arms a claim when Android never accepted the external activity. */
    suspend fun restore(request: AttachmentInstallerHandoffRequest) {
        withContext(persistence) {
            if (request.transfer != cancelledTransfer) intentStore.restoreInstallerHandoff(request)
        }
        revision += 1
    }

    /**
     * Revokes a cancelled transfer without deleting a same-identity tap that
     * arrived while the disk mutation was queued.
     */
    fun cancel(transfer: AttachmentTransferRequest) {
        val token = requests.advance()
        cancelledTransfer = transfer
        if (queuedRequest?.transfer == transfer) queuedRequest = null
        scheduledRequests.removeAll { it.transfer == transfer }
        revision += 1
        scope.launch {
            withContext(persistence) {
                intentStore.consumeInstallerHandoffUnlessSuperseded(transfer) {
                    !requests.isCurrent(token)
                }
            }
        }
    }

    /** Final app-state fence in addition to the Activity RESUMED lifecycle. */
    @Suppress("MaxLineLength")
    fun canDispatch(request: AttachmentInstallerHandoffRequest): Boolean = request.transfer != cancelledTransfer && foregroundEligible()
}
