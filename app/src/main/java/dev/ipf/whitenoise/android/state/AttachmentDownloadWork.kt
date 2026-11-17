package dev.ipf.whitenoise.android.state

import android.content.Context
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import dev.ipf.whitenoise.android.WhiteNoiseApplication
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import java.io.IOException
import java.security.MessageDigest

internal val ATTACHMENT_GROUP_ID_HEX = Regex("^[0-9a-fA-F]{32}$")
internal val ATTACHMENT_MESSAGE_ID_HEX = Regex("^[0-9a-fA-F]{64}$")

/**
 * Minimal identity needed to find an attachment again from MDK after process
 * death. The media reference itself deliberately stays in MDK's SQLite source
 * of truth: signed locators, hashes, nonces, filenames and captions never enter
 * WorkManager's plaintext database.
 */
internal data class AttachmentTransferRequest(
    val accountRef: String,
    val groupIdHex: String,
    val messageIdHex: String,
    val attachmentIndex: Int,
    val sourceMessageIdHex: String? = null,
)

/** Returns the exact native target when the authoritative source id is known. */
internal fun AttachmentTransferRequest.nativeTarget(): NativeAttachmentTarget? =
    sourceMessageIdHex?.let { sourceId ->
        NativeAttachmentTarget(messageIdHex, sourceId, attachmentIndex)
    }

/** Returns the encrypted-cache key for this protocol-owned attachment identity. */
internal fun AttachmentTransferRequest.cacheKey(): String =
    mediaCacheKey(
        accountRef,
        groupIdHex,
        messageIdHex,
        attachmentIndex,
    )

internal object AttachmentDownloadWorkData {
    private const val KEY_ACCOUNT_REF = "account_ref"
    private const val KEY_GROUP_ID_HEX = "group_id_hex"
    private const val KEY_MESSAGE_ID_HEX = "message_id_hex"
    private const val KEY_ATTACHMENT_INDEX = "attachment_index"
    private const val KEY_SOURCE_MESSAGE_ID_HEX = "source_message_id_hex"
    private const val KEY_INTERRUPTION_BACKOFF = "interruption_backoff"
    private const val KEY_RETIRED = "retired"

    /**
     * Encodes only the minimal identity needed for MDK to resolve the attachment again.
     * [interruptionBackoff] marks a spec built with the system-interruption backoff
     * opt-in, so a spec persisted before that opt-in existed is recognizable by its absence.
     */
    fun encode(
        request: AttachmentTransferRequest,
        interruptionBackoff: Boolean = false,
        retired: Boolean = false,
    ): Data {
        val entries =
            mutableListOf<Pair<String, Any?>>(
                KEY_ACCOUNT_REF to request.accountRef,
                KEY_GROUP_ID_HEX to request.groupIdHex,
                KEY_MESSAGE_ID_HEX to request.messageIdHex,
                KEY_ATTACHMENT_INDEX to request.attachmentIndex,
                KEY_SOURCE_MESSAGE_ID_HEX to request.sourceMessageIdHex,
            )
        if (interruptionBackoff) entries += KEY_INTERRUPTION_BACKOFF to true
        if (retired) entries += KEY_RETIRED to true
        return workDataOf(*entries.toTypedArray())
    }

    /** True for the inert spec that [AttachmentDownloadWorker.retireWork] leaves in place of cancelled work. */
    fun isRetired(data: Data): Boolean = data.getBoolean(KEY_RETIRED, false)

    /** True when the persisted spec was built with the system-interruption backoff opt-in. */
    fun hasInterruptionBackoff(data: Data): Boolean = data.getBoolean(KEY_INTERRUPTION_BACKOFF, false)

    /** Rejects malformed WorkManager input before it can reach MDK or cache paths. */
    fun decode(data: Data): AttachmentTransferRequest? {
        val accountRef = data.getString(KEY_ACCOUNT_REF).orEmpty()
        val groupIdHex = data.getString(KEY_GROUP_ID_HEX).orEmpty()
        val messageIdHex = data.getString(KEY_MESSAGE_ID_HEX).orEmpty()
        val attachmentIndex = data.getInt(KEY_ATTACHMENT_INDEX, -1)
        val sourceMessageIdHex = data.getString(KEY_SOURCE_MESSAGE_ID_HEX)
        val valid =
            accountRef.isNotBlank() &&
                ATTACHMENT_GROUP_ID_HEX.matches(groupIdHex) &&
                ATTACHMENT_MESSAGE_ID_HEX.matches(messageIdHex) &&
                (sourceMessageIdHex == null || ATTACHMENT_MESSAGE_ID_HEX.matches(sourceMessageIdHex)) &&
                attachmentIndex >= 0
        return if (valid) {
            AttachmentTransferRequest(accountRef, groupIdHex, messageIdHex, attachmentIndex, sourceMessageIdHex)
        } else {
            null
        }
    }
}

/** Returns the unique WorkManager name used to coalesce this attachment transfer. */
internal fun attachmentDownloadWorkName(request: AttachmentTransferRequest): String {
    val canonical =
        listOf(
            request.accountRef,
            request.groupIdHex.lowercase(),
            request.messageIdHex.lowercase(),
            request.attachmentIndex.toString(),
        ).joinToString("\u0000")
    return "attachment_download_${attachmentIdentityDigest(canonical)}"
}

/** Produces a stable lowercase digest without persisting the source identity. */
internal fun attachmentIdentityDigest(value: String): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { byte -> "%02x".format(byte.toInt() and BYTE_MASK) }

/** Returns the WorkManager tag used to pause one account's automatic backlog. */
internal fun attachmentAutomaticAccountTag(accountRef: String): String {
    val identity = attachmentIdentityDigest(accountRef)
    return "attachment_download_auto_account_$identity"
}

/** Returns the WorkManager tag shared by automatic and interactive instances. */
internal fun attachmentIdentityTag(request: AttachmentTransferRequest): String =
    "attachment_download_identity_${attachmentIdentityDigest(attachmentDownloadWorkName(request))}"

/**
 * Allows one follow-up after a completed transient failure. The budget is the persisted
 * [transientRetrySpent] flag, not WorkManager's run-attempt count, because every platform
 * interruption also starts a new run and would otherwise spend the failure budget.
 */
internal fun shouldRetryAttachmentDownloadWork(
    transientRetrySpent: Boolean,
    failure: Throwable,
): Boolean =
    !transientRetrySpent &&
        (failure is AttachmentReferenceNotReadyException || isTransientAttachmentDownloadFailure(failure))

internal fun shouldCancelQueuedAutomaticWork(
    state: WorkInfo.State,
    hasInteractiveIntent: Boolean,
): Boolean =
    state in setOf(WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED) &&
        !hasInteractiveIntent

internal enum class AttachmentDownloadWorkState {
    Active,
    Finished,
}

/**
 * Observes the durable transfer independently of a conversation controller.
 * The interactive preference bridges enqueue registration, when WorkManager's
 * first snapshot can still be empty or contain only an older generation.
 */
internal fun attachmentDownloadWorkState(
    context: Context,
    request: AttachmentTransferRequest,
    hasInteractiveIntent: () -> Boolean,
): Flow<AttachmentDownloadWorkState> =
    WorkManager
        .getInstance(context.applicationContext)
        .getWorkInfosForUniqueWorkFlow(attachmentDownloadWorkName(request))
        .combine(AttachmentDownloadJobEvents.revision) { infos, _ ->
            if (hasInteractiveIntent() || infos.any { !it.state.isFinished }) {
                AttachmentDownloadWorkState.Active
            } else {
                AttachmentDownloadWorkState.Finished
            }
        }.distinctUntilChanged()

internal class AttachmentReferenceNotReadyException : IllegalStateException("attachment reference is not projected yet")

/**
 * Automatic work the live policy does not allow when it runs, for the [hold] reason, after a network or settings
 * change since it was queued. No fetch starts. A [AutomaticAttachmentHold.Network] hold waits for a network the
 * policy allows, which [networkType] narrows the queued work to; any other finishes, and the attachment's bubble
 * still offers it on demand.
 */
internal class AttachmentAutomaticPolicyDeferredException(
    val type: MediaAutoDownloadType?,
    val hold: AutomaticAttachmentHold,
    val networkType: NetworkType = NetworkType.CONNECTED,
) : IllegalStateException("automatic attachment policy does not allow this download now")

/** The native runtime does not hold the live automatic approval yet, so the work retries after its backoff. */
internal class AttachmentPermissionPendingException : IOException("automatic attachment permission is not applied yet")

internal typealias PerformDurableAttachmentDownload = suspend (
    WhiteNoiseApplication,
    AttachmentTransferRequest,
    AttachmentDownloadPriority,
) -> Boolean

private const val BYTE_MASK = 0xFF
