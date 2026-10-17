package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow

/**
 * Attachment observation and opening for one immutable account/group binding.
 *
 * The coordinator owns only UI waiters and presentation state. Download admission, transfer work,
 * retry and explicit native cancellation retain their existing app/native owners. Disposing this
 * observer scope cannot cancel an MDK-accepted operation, even before its acknowledgement arrives.
 */
@Suppress("TooManyFunctions") // Cohesive adapters for one attachment presentation owner.
internal class ConversationAttachmentPresentation(
    private val appState: WhiteNoiseAppState,
    private val accountRef: String?,
    private val groupIdHex: String,
    private val sourceMessageId: (String) -> String?,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val transfers = AttachmentTransferCoordinator(scope)

    /** Releases UI waiters; explicit native cancellation is a separate user action. */
    fun dispose() = scope.cancel()

    /** Controller-local slot key; durable/native keys additionally include account and group. */
    private fun transferKey(
        messageIdHex: String,
        attachmentIndex: Int,
    ): String = "$messageIdHex#$attachmentIndex"

    /** Cache identity is fixed to the account/group captured when this controller was constructed. */
    private fun mediaCacheKey(
        account: String,
        messageIdHex: String,
        attachmentIndex: Int,
    ): String =
        mediaCacheKey(account, groupIdHex, messageIdHex, attachmentIndex)

    /** Preserves native source identity instead of guessing from the displayed row id. */
    private fun attachmentRequest(
        account: String,
        messageIdHex: String,
        attachmentIndex: Int,
    ): AttachmentTransferRequest =
        AttachmentTransferRequest(account, groupIdHex, messageIdHex, attachmentIndex, sourceMessageId(messageIdHex))

    /**
     * Yes/no probe: are the decrypted bytes for [messageIdHex] /
     * [attachmentIndex] already resident in either cache tier? Lets a file
     * bubble decide whether to surface the download chevron without firing
     * an FFI hop. Strictly peek — never schedules a download or seeds the
     * cache. Returns false when there's no active account.
     */
    fun hasCachedAttachment(
        messageIdHex: String,
        attachmentIndex: Int,
    ): Boolean {
        val account = accountRef ?: return false
        val cacheKey = mediaCacheKey(account, messageIdHex, attachmentIndex)
        return appState.cachedMediaPlaintext(cacheKey) != null || appState.diskMediaCache.contains(cacheKey)
    }

    /** Resolve a cold disk-index state without performing main-thread I/O. */
    suspend fun hasCachedAttachmentAfterHydration(
        messageIdHex: String,
        attachmentIndex: Int,
    ): Boolean {
        val account = accountRef ?: return false
        return appState.hasCachedAttachmentAfterHydration(
            attachmentRequest(account, messageIdHex, attachmentIndex),
        )
    }

    /** Observable presentation state owned outside the attachment composable. */
    fun attachmentTransferState(
        messageIdHex: String,
        attachmentIndex: Int,
        initiallyAvailable: Boolean,
    ): StateFlow<AttachmentTransferState> =
        transfers.acquireState(
            key = transferKey(messageIdHex, attachmentIndex),
            initiallyAvailable = initiallyAvailable,
        )

    /** Release presentation state when its attachment composable leaves composition. */
    fun releaseAttachmentTransferState(
        messageIdHex: String,
        attachmentIndex: Int,
    ) {
        transfers.releaseState(transferKey(messageIdHex, attachmentIndex))
    }

    /** Suspends until this attachment, rather than any process-wide cache key, becomes available. */
    suspend fun awaitNextAttachmentAvailability(
        messageIdHex: String,
        attachmentIndex: Int,
    ) {
        transfers.awaitNextAvailability(transferKey(messageIdHex, attachmentIndex))
    }

    /**
     * Request one controller-owned transfer. Auto-download and tap-to-open
     * callers receive the same [Deferred], while truthful post-load probing
     * decides whether the result survived in either local cache tier.
     */
    fun requestAttachmentTransfer(
        messageIdHex: String,
        attachmentIndex: Int,
        reference: MediaAttachmentReferenceFfi,
        retainedPlaintext: ByteArray? = null,
        priority: AttachmentDownloadPriority = AttachmentDownloadPriority.Interactive,
    ): Deferred<ByteArray> {
        val account = accountRef
        if (retainedPlaintext == null && reference.sourceEpoch != 0uL && account != null) {
            appState.enqueueAttachmentDownload(
                attachmentRequest(account, messageIdHex, attachmentIndex),
                priority,
            )
        }
        return transfers.request(
            key = transferKey(messageIdHex, attachmentIndex),
            load = {
                retainedPlaintext
                    ?: downloadAttachment(messageIdHex, attachmentIndex, reference, priority)
            },
            availableAfterLoad = {
                retainedPlaintext != null ||
                    hasCachedAttachmentAfterHydration(messageIdHex, attachmentIndex)
            },
        )
    }

    /** Captures a visible open request through the app-owned delivery coordinator. */
    fun requestAttachmentOpen(
        messageIdHex: String,
        attachmentIndex: Int,
    ): Boolean {
        val account = accountRef ?: return false
        return appState.attachmentOpens.requestOpen(
            attachmentRequest(account, messageIdHex, attachmentIndex),
        )
    }

    /** Finds the current visible destination for this exact attachment identity. */
    fun attachmentOpenRequest(
        messageIdHex: String,
        attachmentIndex: Int,
    ): AttachmentOpenRequest? {
        val account = accountRef ?: return null
        return appState.attachmentOpens.openRequest(
            attachmentRequest(account, messageIdHex, attachmentIndex),
        )
    }

    /** Whether an unconsumed open intent still belongs to this attachment. */
    fun hasAttachmentOpenIntent(
        messageIdHex: String,
        attachmentIndex: Int,
    ): Boolean {
        val request = attachmentOpenRequest(messageIdHex, attachmentIndex) ?: return false
        return appState.attachmentOpens.hasIntent(request)
    }

    /** Consumes one open intent after native bytes become available. */
    suspend fun consumeAttachmentOpenIntent(
        messageIdHex: String,
        attachmentIndex: Int,
    ): Boolean {
        val request = attachmentOpenRequest(messageIdHex, attachmentIndex) ?: return false
        return appState.attachmentOpens.consume(request)
    }

    /** Restores an interrupted platform open under its existing delivery owner. */
    fun restoreAttachmentOpenIntent(
        messageIdHex: String,
        attachmentIndex: Int,
    ) {
        val request = attachmentOpenRequest(messageIdHex, attachmentIndex) ?: return
        appState.attachmentOpens.restore(request)
    }

    /** Upgrade an optimistic imeta fallback before download/save uses its epoch. */
    suspend fun authoritativeAttachmentReference(
        messageIdHex: String,
        attachmentIndex: Int,
        fallback: MediaAttachmentReferenceFfi,
    ): MediaAttachmentReferenceFfi {
        if (fallback.sourceEpoch != 0uL) return fallback
        val account = accountRef ?: error("no active account")
        val request = attachmentRequest(account, messageIdHex, attachmentIndex)
        repeat(ATTACHMENT_REFERENCE_RESOLVE_ATTEMPTS) { attempt ->
            appState
                .resolveAttachmentReference(request)
                ?.takeIf { it.sourceEpoch != 0uL }
                ?.let { return it }
            if (attempt < ATTACHMENT_REFERENCE_RESOLVE_ATTEMPTS - 1) {
                delay(ATTACHMENT_REFERENCE_RESOLVE_BACKOFF_MILLIS)
            }
        }
        throw AttachmentReferenceNotReadyException()
    }

    /**
     * Resolves one attachment's plaintext as an array of at most [maxBytes], which defaults to the presentation
     * budget so a preview cannot allocate an unbounded array, explicit Save and Share name their own budget.
     */
    suspend fun downloadAttachment(
        messageIdHex: String,
        attachmentIndex: Int,
        reference: MediaAttachmentReferenceFfi,
        priority: AttachmentDownloadPriority,
        maxBytes: Long = ATTACHMENT_PRESENTATION_MAX_BYTES,
    ): ByteArray {
        val account = accountRef ?: error("no active account")
        val request = attachmentRequest(account, messageIdHex, attachmentIndex)
        if (priority == AttachmentDownloadPriority.Interactive) {
            appState.enqueueAttachmentDownload(request, priority)
        }
        return appState.downloadAttachmentPlaintext(
            request = request,
            reference = reference,
            priority = priority,
            maxBytes = maxBytes,
        )
    }

    /** Decoded thumbnail for [messageIdHex] if one is cached (renders with no
     *  spinner). Null when unanchored or not yet decoded. */
    fun thumbnailFor(
        messageIdHex: String,
        attachmentIndex: Int,
    ): android.graphics.Bitmap? {
        val account = accountRef ?: return null
        return appState.cachedMediaThumbnail(mediaCacheKey(account, messageIdHex, attachmentIndex))
    }

    /** Cache a decoded thumbnail so re-renders / re-entry skip the decode. */
    fun cacheThumbnail(
        messageIdHex: String,
        attachmentIndex: Int,
        bitmap: android.graphics.Bitmap,
    ) {
        val account = accountRef ?: return
        appState.cacheMediaThumbnail(mediaCacheKey(account, messageIdHex, attachmentIndex), bitmap)
    }

    private companion object {
        const val ATTACHMENT_REFERENCE_RESOLVE_ATTEMPTS = 3
        const val ATTACHMENT_REFERENCE_RESOLVE_BACKOFF_MILLIS = 150L
    }
}
