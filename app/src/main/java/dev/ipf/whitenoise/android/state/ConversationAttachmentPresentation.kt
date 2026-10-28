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

    /** Cache identity is fixed to the account/group captured when this controller was constructed. */
    fun mediaCacheKey(
        account: String,
        messageIdHex: String,
        attachmentIndex: Int,
    ): String = mediaCacheKey(account, groupIdHex, messageIdHex, attachmentIndex)

    /** Preserves native source identity instead of guessing from the displayed row id. */
    private fun attachmentRequest(
        account: String,
        messageIdHex: String,
        attachmentIndex: Int,
    ): AttachmentTransferRequest =
        AttachmentTransferRequest(
            account,
            groupIdHex,
            messageIdHex,
            attachmentIndex,
            sourceMessageId(messageIdHex),
        )

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
            key = attachmentTransferSlotKey(messageIdHex, attachmentIndex),
            initiallyAvailable = initiallyAvailable,
        )

    /** Release presentation state when its attachment composable leaves composition. */
    fun releaseAttachmentTransferState(
        messageIdHex: String,
        attachmentIndex: Int,
    ) {
        transfers.releaseState(attachmentTransferSlotKey(messageIdHex, attachmentIndex))
    }

    /** Suspends until this attachment, rather than any process-wide cache key, becomes available. */
    suspend fun awaitNextAttachmentAvailability(
        messageIdHex: String,
        attachmentIndex: Int,
    ) {
        transfers.awaitNextAvailability(attachmentTransferSlotKey(messageIdHex, attachmentIndex))
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
            key = attachmentTransferSlotKey(messageIdHex, attachmentIndex),
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

    /**
     * Records a durable, interactive download of a visible attachment that is not yet on the device. The intent keeps
     * the in-place download armed across rotation and process recreation and is retired once the bytes are ready. It
     * is stored apart from open intents, so no open dispatcher can claim it: a download never opens what it fetched.
     */
    fun requestAttachmentDownload(
        messageIdHex: String,
        attachmentIndex: Int,
    ): Boolean {
        val account = accountRef ?: return false
        return appState.attachmentOpens.requestDownload(attachmentRequest(account, messageIdHex, attachmentIndex))
    }

    /** Whether an in-place download of this attachment is still armed. */
    fun hasAttachmentDownloadIntent(
        messageIdHex: String,
        attachmentIndex: Int,
    ): Boolean {
        val request = attachmentOpenRequest(messageIdHex, attachmentIndex) ?: return false
        return appState.attachmentOpens.hasDownloadIntent(request)
    }

    /** Retires an in-place download once its bytes are ready, without opening anything. */
    suspend fun consumeAttachmentDownloadIntent(
        messageIdHex: String,
        attachmentIndex: Int,
    ): Boolean {
        val request = attachmentOpenRequest(messageIdHex, attachmentIndex) ?: return false
        return appState.attachmentOpens.consumeDownload(request)
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

    /** Holds only the cache identity currently displayed by a kept card; no acquisition is admitted. */
    fun retainThumbnail(
        messageIdHex: String,
        attachmentIndex: Int,
    ): AutoCloseable? {
        val account = accountRef ?: return null
        return appState.retainMediaThumbnail(mediaCacheKey(account, messageIdHex, attachmentIndex))
    }

    /** Returns pixels with their decode ceiling under the same immutable account/group/attachment identity. */
    fun thumbnailEntryFor(
        messageIdHex: String,
        attachmentIndex: Int,
        requestedMaxEdgePx: Int,
    ): dev.ipf.whitenoise.android.media.MediaThumbnail? {
        val account = accountRef ?: return null
        return appState.cachedMediaThumbnailEntry(
            mediaCacheKey(account, messageIdHex, attachmentIndex),
            requestedMaxEdgePx,
        )
    }

    /** Cache a decoded thumbnail so re-renders / re-entry skip the decode. */
    fun cacheThumbnail(
        messageIdHex: String,
        attachmentIndex: Int,
        bitmap: android.graphics.Bitmap,
        maxEdgePx: Int,
    ) {
        val account = accountRef ?: return
        appState.cacheMediaThumbnail(mediaCacheKey(account, messageIdHex, attachmentIndex), bitmap, maxEdgePx)
    }

    private companion object {
        const val ATTACHMENT_REFERENCE_RESOLVE_ATTEMPTS = 3
        const val ATTACHMENT_REFERENCE_RESOLVE_BACKOFF_MILLIS = 150L
    }
}
