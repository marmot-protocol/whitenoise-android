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

    @Volatile
    private var persistedRequest: AttachmentInstallerHandoffRequest? = null

    private val scheduledRequests = mutableSetOf<AttachmentInstallerHandoffRequest>()

    // staleness-exempt: observable handoff version consumed by Compose.
    var revision by mutableIntStateOf(0)
        private set

    init {
        val token = requests.capture()
        scope.launch {
            if (!requests.isCurrent(token)) return@launch
            val pending = withContext(persistence) { intentStore.pendingInstallerHandoff() }
            requests.runIfCurrent(token) {
                persistedRequest = pending
                revision += 1
            }
        }
    }

    /** Persists off the UI thread before admitting work and reports a failed durable commit. */
    fun request(
        request: AttachmentInstallerHandoffRequest,
        onPersistenceFailure: () -> Unit,
    ) {
        // Fence an already-queued cancellation before the new record becomes
        // visible, so it cannot consume this same-identity re-tap.
        val token = requests.advance()
        cancelledTransfer = null
        queuedRequest = request
        revision += 1
        scope.launch {
            val (committed, pending) =
                withContext(persistence) {
                    val persisted =
                        intentStore.markInstallerHandoffUnlessSuperseded(request) {
                            !requests.isCurrent(token)
                        }
                    persisted to intentStore.pendingInstallerHandoff()
                }
            val published =
                requests.runIfCurrent(token) {
                    queuedRequest = null
                    persistedRequest = pending
                    revision += 1
                }
            if (!published) return@launch
            if (committed) {
                scheduledRequests += request
                enqueue(request.transfer, AttachmentDownloadPriority.Interactive)
            } else {
                onPersistenceFailure()
            }
        }
    }

    /** Restarts a persisted handoff's durable transfer once in a replacement process. */
    fun ensureTransfer(request: AttachmentInstallerHandoffRequest) {
        if (
            request.transfer != cancelledTransfer &&
            persistedRequest == request &&
            scheduledRequests.add(request)
        ) {
            enqueue(request.transfer, AttachmentDownloadPriority.Interactive)
        }
    }

    /** Restores one process-owned observer from the exact persisted identity. */
    @Suppress("MaxLineLength")
    fun pending(): AttachmentInstallerHandoffRequest? = persistedRequest?.takeUnless { it.transfer == cancelledTransfer }

    /** True while this card owns the current app-scoped pending indication. */
    fun hasPending(request: AttachmentInstallerHandoffRequest): Boolean {
        revision
        return request.transfer != cancelledTransfer &&
            (queuedRequest == request || persistedRequest == request)
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
                if (persistedRequest == request) persistedRequest = null
                revision += 1
            }
        }

    /** Consumes an explicit cancellation or deterministic terminal outcome. */
    suspend fun consume(request: AttachmentInstallerHandoffRequest): Boolean =
        withContext(persistence) { intentStore.consumeInstallerHandoff(request) }
            .also {
                if (it) {
                    if (persistedRequest == request) persistedRequest = null
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
        persistedRequest = request
        revision += 1
    }

    /** Re-arms a claim when Android never accepted the external activity. */
    suspend fun restore(request: AttachmentInstallerHandoffRequest) {
        val token = requests.capture()
        val restored =
            withContext(persistence) {
                if (request.transfer != cancelledTransfer) intentStore.restoreInstallerHandoff(request) else false
            }
        if (restored) {
            requests.runIfCurrent(token) {
                persistedRequest = request
                revision += 1
            }
        }
    }

    /**
     * Revokes a cancelled transfer without deleting a same-identity tap that
     * arrived while the disk mutation was queued.
     */
    fun cancel(transfer: AttachmentTransferRequest) {
        val token = requests.advance()
        cancelledTransfer = transfer
        if (queuedRequest?.transfer == transfer) queuedRequest = null
        if (persistedRequest?.transfer == transfer) persistedRequest = null
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

/** Creates an app-owned installer request that survives conversation navigation. */
internal fun ConversationController.requestAttachmentInstallerHandoff(
    messageIdHex: String,
    attachmentIndex: Int,
    sourceEpoch: ULong,
    onPersistenceFailure: () -> Unit,
) {
    val transfer =
        attachmentTransferRequest(messageIdHex, attachmentIndex)
            ?: return onPersistenceFailure()
    val request = AttachmentInstallerHandoffRequest(transfer, sourceEpoch)
    appState.attachmentInstallerHandoffs.request(request, onPersistenceFailure)
}

/** True while this APK card owns the app-scoped one-shot installer request. */
internal fun ConversationController.hasAttachmentInstallerHandoff(
    messageIdHex: String,
    attachmentIndex: Int,
    sourceEpoch: ULong,
): Boolean {
    val transfer = attachmentTransferRequest(messageIdHex, attachmentIndex) ?: return false
    val request = AttachmentInstallerHandoffRequest(transfer, sourceEpoch)
    return appState.attachmentInstallerHandoffs.hasPending(request)
}

/** Stable Android scheduling identity for this controller-owned attachment. */
internal fun ConversationController.attachmentTransferRequest(
    messageIdHex: String,
    attachmentIndex: Int,
): AttachmentTransferRequest? {
    val account = boundAccountRef ?: return null
    return AttachmentTransferRequest(account, group.groupIdHex, messageIdHex, attachmentIndex)
}
