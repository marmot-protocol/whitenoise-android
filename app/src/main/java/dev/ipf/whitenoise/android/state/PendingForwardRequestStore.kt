package dev.ipf.whitenoise.android.state

import android.content.Context
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.marmotkit.MediaLocatorFfi
import dev.ipf.whitenoise.android.core.ForwardAttachmentSource
import dev.ipf.whitenoise.android.core.ForwardMessagePayload
import dev.ipf.whitenoise.android.media.AndroidKeystoreDiskByteCacheKeyProvider
import dev.ipf.whitenoise.android.media.DiskByteCache
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * One unresolved in-app forward: everything the picker needs to restore after
 * process death without substituting any live account for a bound owner.
 *
 * [payloads] keep the accepted source identity and original timeline order.
 * [destinationAccountRef] and [selectedGroupIds] mirror the picker's current
 * choices; both are revalidated against live accounts and live destination
 * chats before the restored request can be accepted.
 */
internal data class PendingForwardRequest(
    val requestId: String,
    val sourceAccountRef: String,
    val originGroupIdHex: String,
    val payloads: List<ForwardMessagePayload>,
    val destinationAccountRef: String?,
    val selectedGroupIds: List<String>,
)

/**
 * Process-death bridge for the single unresolved forward request. Content is
 * encrypted at rest in no-backup storage, mirroring the inbound-share store:
 * plaintext message text never reaches backups or the saved-state Bundle.
 */
internal interface PendingForwardRequestStore {
    /** Persists [request] as the single unresolved entry; false when it cannot be retained. */
    fun save(request: PendingForwardRequest): Boolean

    /** Returns the single unresolved request, if a decodable one exists. */
    fun load(): PendingForwardRequest?

    /** Removes the entry only when its id matches [requestId]. */
    fun remove(requestId: String)

    /** Drops the unresolved entry unconditionally. */
    fun clear()
}

/**
 * Serializes store access before dispatching blocking encryption and disk
 * work, so a newer request or dismissal always lands after an in-flight write
 * instead of being overwritten by stale work.
 */
internal class SerializedPendingForwardRequestStore(
    private val delegate: PendingForwardRequestStore,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /** Serializes one save behind the process mutex on the IO dispatcher. */
    suspend fun save(request: PendingForwardRequest): Boolean =
        processMutex.withLock {
            withContext(ioDispatcher) {
                delegate.save(request).also { saved ->
                    if (!saved) delegate.clear()
                }
            }
        }

    /** Serializes one load behind the process mutex on the IO dispatcher. */
    suspend fun load(): PendingForwardRequest? =
        processMutex.withLock {
            withContext(ioDispatcher) { delegate.load() }
        }

    /** Serializes one id-matched removal behind the process mutex. */
    suspend fun remove(requestId: String) {
        processMutex.withLock { withContext(ioDispatcher) { delegate.remove(requestId) } }
    }

    /** Serializes an unconditional clear behind the process mutex. */
    suspend fun clear() {
        processMutex.withLock { withContext(ioDispatcher) { delegate.clear() } }
    }

    private companion object {
        /** Shared across Activity recreation because old cancelled I/O can outlive its wrapper. */
        val processMutex = Mutex()
    }
}

/**
 * App-state facade over the encrypted pending-forward store. Writes and
 * discards are fire-and-forget on the app scope so UI callers never block;
 * loads run inline for the restore path. The store itself is created lazily
 * on first use and can be replaced by tests through [override].
 */
internal class ForwardRequestPersistence(
    private val scope: CoroutineScope,
    private val storeProvider: () -> SerializedPendingForwardRequestStore,
) {
    private var overrideStore: SerializedPendingForwardRequestStore? = null

    private val store: SerializedPendingForwardRequestStore
        get() = overrideStore ?: storeProvider()

    /** Test seam replacing the encrypted on-disk pending-forward store. */
    fun override(store: SerializedPendingForwardRequestStore) {
        overrideStore = store
    }

    /** Mirrors one unresolved forward request into encrypted no-backup storage. */
    fun persist(request: PendingForwardRequest) {
        scope.launch { store.save(request) }
    }

    /** Discards an unresolved forward request after explicit dismissal or acceptance. */
    fun discard(requestId: String) {
        scope.launch { store.remove(requestId) }
    }

    /** Loads the unresolved forward request, if any, for process-recreation restore. */
    suspend fun load(): PendingForwardRequest? = store.load()

    /** Drops any unresolved request, used by destructive account wipes. */
    suspend fun clear() = store.clear()
}

internal class EncryptedPendingForwardRequestStore(
    private val cache: DiskByteCache,
) : PendingForwardRequestStore {
    /** Encrypts and stores [request], replacing any previous entry. */
    override fun save(request: PendingForwardRequest): Boolean {
        val valid =
            request.requestId.isNotBlank() &&
                request.sourceAccountRef.isNotBlank() &&
                request.payloads.isNotEmpty()
        val encoded = if (valid) encodePendingForwardRequest(request) else null
        if (encoded == null || encoded.size > MAX_PENDING_FORWARD_REQUEST_BYTES) return false
        cache.clear()
        cache.put(ENTRY_KEY, encoded)
        return cache.containsAfterHydration(ENTRY_KEY)
    }

    /** Decrypts and decodes the single entry, or null when absent or malformed. */
    override fun load(): PendingForwardRequest? = cache.get(ENTRY_KEY)?.let(::decodePendingForwardRequest)

    /** Removes only the matching entry so a stale dismisser cannot delete a newer request. */
    override fun remove(requestId: String) {
        if (requestId.isNotBlank() && load()?.requestId == requestId) cache.remove(ENTRY_KEY)
    }

    /** Deletes the encrypted entry unconditionally. */
    override fun clear() = cache.clear()

    companion object {
        private const val ENTRY_KEY = "pending-forward-request"
        private const val STORE_DIRECTORY = "pending-forward-requests"
        private const val STORE_KEY_ALIAS = "whitenoise.pending_forward_requests.aes_gcm.v1"
        private const val PENDING_FORWARD_REQUEST_CACHE_BYTES = 512 * 1024
        internal const val MAX_PENDING_FORWARD_REQUEST_BYTES = 256 * 1024

        @Volatile
        private var processInstance: SerializedPendingForwardRequestStore? = null

        /** One process-wide store: every picker surface shares the single unresolved entry. */
        fun forContext(context: Context): SerializedPendingForwardRequestStore =
            processInstance ?: synchronized(this) {
                processInstance ?: SerializedPendingForwardRequestStore(create(context))
                    .also { processInstance = it }
            }

        /** Builds the keystore-encrypted store rooted in no-backup storage. */
        fun create(context: Context): EncryptedPendingForwardRequestStore {
            val app = context.applicationContext
            return EncryptedPendingForwardRequestStore(
                DiskByteCache(
                    cacheDir = File(app.noBackupFilesDir, STORE_DIRECTORY),
                    maxBytes = PENDING_FORWARD_REQUEST_CACHE_BYTES.toLong(),
                    maxEntryBytes = MAX_PENDING_FORWARD_REQUEST_BYTES.toLong(),
                    keyProvider = AndroidKeystoreDiskByteCacheKeyProvider(STORE_KEY_ALIAS),
                ),
            )
        }
    }
}

private const val KEY_VERSION = "version"
private const val KEY_REQUEST_ID = "requestId"
private const val KEY_SOURCE_ACCOUNT = "sourceAccountRef"
private const val KEY_ORIGIN_GROUP = "originGroupIdHex"
private const val KEY_DESTINATION_ACCOUNT = "destinationAccountRef"
private const val KEY_SELECTED_GROUPS = "selectedGroupIds"
private const val KEY_PAYLOADS = "payloads"
private const val KEY_KIND = "kind"
private const val KEY_SOURCE_GROUP = "sourceGroupIdHex"
private const val KEY_SOURCE_MESSAGE = "sourceMessageIdHex"
private const val KEY_TEXT = "text"
private const val KEY_CAPTION = "caption"
private const val KEY_EXPIRES_AT = "expiresAtSeconds"
private const val KEY_ATTACHMENTS = "attachments"
private const val KEY_ATTACHMENT_INDEX = "attachmentIndex"
private const val KEY_REFERENCE = "reference"
private const val KEY_LOCATORS = "locators"
private const val KEY_LOCATOR_KIND = "kind"
private const val KEY_LOCATOR_VALUE = "value"
private const val KEY_CIPHERTEXT_SHA256 = "ciphertextSha256"
private const val KEY_PLAINTEXT_SHA256 = "plaintextSha256"
private const val KEY_NONCE_HEX = "nonceHex"
private const val KEY_FILE_NAME = "fileName"
private const val KEY_MEDIA_TYPE = "mediaType"
private const val KEY_MEDIA_VERSION = "mediaVersion"
private const val KEY_SOURCE_EPOCH = "sourceEpoch"
private const val KEY_DIM = "dim"
private const val KEY_THUMBHASH = "thumbhash"
private const val KIND_TEXT = "text"
private const val KIND_MEDIA = "media"
private const val FORMAT_VERSION = 1

/** Encodes one pending request as versioned JSON bytes. */
internal fun encodePendingForwardRequest(request: PendingForwardRequest): ByteArray =
    JSONObject()
        .put(KEY_VERSION, FORMAT_VERSION)
        .put(KEY_REQUEST_ID, request.requestId)
        .put(KEY_SOURCE_ACCOUNT, request.sourceAccountRef)
        .put(KEY_ORIGIN_GROUP, request.originGroupIdHex)
        .put(KEY_DESTINATION_ACCOUNT, request.destinationAccountRef ?: JSONObject.NULL)
        .put(KEY_SELECTED_GROUPS, JSONArray(request.selectedGroupIds))
        .put(KEY_PAYLOADS, JSONArray(request.payloads.map(::encodePayload)))
        .toString()
        .encodeToByteArray()

/** Decodes versioned JSON bytes, returning null for malformed or skewed input. */
@Suppress("SwallowedException", "TooGenericExceptionCaught")
internal fun decodePendingForwardRequest(encoded: ByteArray): PendingForwardRequest? =
    try {
        val json = JSONObject(encoded.decodeToString())
        val requestId = json.getString(KEY_REQUEST_ID)
        if (json.getInt(KEY_VERSION) != FORMAT_VERSION || requestId.isBlank()) {
            null
        } else {
            val payloadsJson = json.getJSONArray(KEY_PAYLOADS)
            PendingForwardRequest(
                requestId = requestId,
                sourceAccountRef = json.getString(KEY_SOURCE_ACCOUNT),
                originGroupIdHex = json.getString(KEY_ORIGIN_GROUP),
                destinationAccountRef =
                    json.optString(KEY_DESTINATION_ACCOUNT).takeIf {
                        !json.isNull(KEY_DESTINATION_ACCOUNT) && it.isNotBlank()
                    },
                selectedGroupIds =
                    json.getJSONArray(KEY_SELECTED_GROUPS).let { array ->
                        List(array.length()) { index -> array.getString(index) }
                    },
                payloads =
                    List(payloadsJson.length()) { index ->
                        decodePayload(payloadsJson.getJSONObject(index))
                    },
            ).takeIf { it.payloads.isNotEmpty() }
        }
    } catch (malformed: Exception) {
        // Any malformed or version-skewed entry is unrecoverable state, never a crash.
        null
    }

/** Encodes one payload, preserving its kind, source identity, and order. */
private fun encodePayload(payload: ForwardMessagePayload): JSONObject =
    when (payload) {
        is ForwardMessagePayload.Text ->
            JSONObject()
                .put(KEY_KIND, KIND_TEXT)
                .put(KEY_SOURCE_GROUP, payload.sourceGroupIdHex)
                .put(KEY_SOURCE_MESSAGE, payload.sourceMessageIdHex)
                .put(KEY_TEXT, payload.text)
        is ForwardMessagePayload.Media ->
            JSONObject()
                .put(KEY_KIND, KIND_MEDIA)
                .put(KEY_SOURCE_GROUP, payload.sourceGroupIdHex)
                .put(KEY_SOURCE_MESSAGE, payload.sourceMessageIdHex)
                .put(KEY_CAPTION, payload.caption ?: JSONObject.NULL)
                .put(KEY_EXPIRES_AT, payload.expiresAtSeconds?.toString() ?: JSONObject.NULL)
                .put(
                    KEY_ATTACHMENTS,
                    JSONArray(
                        payload.attachments.map { attachment ->
                            JSONObject()
                                .put(KEY_ATTACHMENT_INDEX, attachment.attachmentIndex)
                                .put(KEY_REFERENCE, encodeReference(attachment.reference))
                        },
                    ),
                )
    }

/** Decodes one payload back into its typed form. */
private fun decodePayload(json: JSONObject): ForwardMessagePayload =
    when (val kind = json.getString(KEY_KIND)) {
        KIND_TEXT ->
            ForwardMessagePayload.Text(
                sourceGroupIdHex = json.getString(KEY_SOURCE_GROUP),
                sourceMessageIdHex = json.getString(KEY_SOURCE_MESSAGE),
                text = json.getString(KEY_TEXT),
            )
        KIND_MEDIA -> {
            val attachments = json.getJSONArray(KEY_ATTACHMENTS)
            ForwardMessagePayload.Media(
                sourceGroupIdHex = json.getString(KEY_SOURCE_GROUP),
                sourceMessageIdHex = json.getString(KEY_SOURCE_MESSAGE),
                caption = if (json.isNull(KEY_CAPTION)) null else json.getString(KEY_CAPTION),
                expiresAtSeconds =
                    if (json.isNull(KEY_EXPIRES_AT)) null else json.getString(KEY_EXPIRES_AT).toULong(),
                attachments =
                    List(attachments.length()) { index ->
                        val attachment = attachments.getJSONObject(index)
                        ForwardAttachmentSource(
                            attachmentIndex = attachment.getInt(KEY_ATTACHMENT_INDEX),
                            reference = decodeReference(attachment.getJSONObject(KEY_REFERENCE)),
                        )
                    },
            )
        }
        else -> throw IllegalArgumentException("unknown forward payload kind: $kind")
    }

/** Encodes every field of one media attachment reference. */
private fun encodeReference(reference: MediaAttachmentReferenceFfi): JSONObject =
    JSONObject()
        .put(
            KEY_LOCATORS,
            JSONArray(
                reference.locators.map { locator ->
                    JSONObject()
                        .put(KEY_LOCATOR_KIND, locator.kind)
                        .put(KEY_LOCATOR_VALUE, locator.value)
                },
            ),
        ).put(KEY_CIPHERTEXT_SHA256, reference.ciphertextSha256)
        .put(KEY_PLAINTEXT_SHA256, reference.plaintextSha256)
        .put(KEY_NONCE_HEX, reference.nonceHex)
        .put(KEY_FILE_NAME, reference.fileName)
        .put(KEY_MEDIA_TYPE, reference.mediaType)
        .put(KEY_MEDIA_VERSION, reference.version.name)
        .put(KEY_SOURCE_EPOCH, reference.sourceEpoch.toString())
        .put(KEY_DIM, reference.dim ?: JSONObject.NULL)
        .put(KEY_THUMBHASH, reference.thumbhash ?: JSONObject.NULL)

/** Decodes one media attachment reference field-for-field. */
private fun decodeReference(json: JSONObject): MediaAttachmentReferenceFfi {
    val locators = json.getJSONArray(KEY_LOCATORS)
    return MediaAttachmentReferenceFfi(
        locators =
            List(locators.length()) { index ->
                val locator = locators.getJSONObject(index)
                MediaLocatorFfi(
                    kind = locator.getString(KEY_LOCATOR_KIND),
                    value = locator.getString(KEY_LOCATOR_VALUE),
                )
            },
        ciphertextSha256 = json.getString(KEY_CIPHERTEXT_SHA256),
        plaintextSha256 = json.getString(KEY_PLAINTEXT_SHA256),
        nonceHex = json.getString(KEY_NONCE_HEX),
        fileName = json.getString(KEY_FILE_NAME),
        mediaType = json.getString(KEY_MEDIA_TYPE),
        version = EncryptedMediaVersionFfi.valueOf(json.getString(KEY_MEDIA_VERSION)),
        sourceEpoch = json.getString(KEY_SOURCE_EPOCH).toULong(),
        dim = if (json.isNull(KEY_DIM)) null else json.getString(KEY_DIM),
        thumbhash = if (json.isNull(KEY_THUMBHASH)) null else json.getString(KEY_THUMBHASH),
    )
}

/** True while [accountRef] can still own one side of a forwarding operation. */
internal fun WhiteNoiseAppState.isForwardOwnerSignedIn(accountRef: String): Boolean =
    accounts.any { account ->
        account.label == accountRef && account.isSignedInSigningAccount()
    }
