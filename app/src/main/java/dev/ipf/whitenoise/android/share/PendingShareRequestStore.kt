package dev.ipf.whitenoise.android.share

import android.content.Context
import android.net.Uri
import dev.ipf.whitenoise.android.media.AndroidKeystoreDiskByteCacheKeyProvider
import dev.ipf.whitenoise.android.media.DiskByteCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Process-death bridge for one unresolved inbound share.
 *
 * The saved-state Bundle contains only [ShareRequest.requestId]. The potentially
 * large plaintext and URI list live in this small, encrypted, no-backup store
 * until the picker is dismissed or the request is staged.
 */
internal interface PendingShareRequestStore {
    fun save(request: ShareRequest): Boolean

    fun load(requestId: String): ShareRequest?

    fun remove(requestId: String)

    fun clear()
}

internal class EncryptedPendingShareRequestStore(
    private val cache: DiskByteCache,
) : PendingShareRequestStore {
    override fun save(request: ShareRequest): Boolean =
        if (request.requestId.isBlank()) {
            false
        } else {
            val encoded = encodePendingShareRequest(request)
            if (encoded.size > MAX_PENDING_SHARE_REQUEST_BYTES) {
                false
            } else {
                cache.clear()
                cache.put(request.requestId, encoded)
                cache.containsAfterHydration(request.requestId)
            }
        }

    override fun load(requestId: String): ShareRequest? =
        if (requestId.isBlank()) {
            null
        } else {
            cache.get(requestId)?.let { encoded ->
                decodePendingShareRequest(encoded, expectedRequestId = requestId)
            }
        }

    override fun remove(requestId: String) {
        if (requestId.isNotBlank()) cache.remove(requestId)
    }

    override fun clear() = cache.clear()

    companion object {
        private const val STORE_DIRECTORY = "pending-share-requests"
        private const val STORE_KEY_ALIAS = "whitenoise.pending_share_requests.aes_gcm.v1"

        fun create(context: Context): EncryptedPendingShareRequestStore {
            val app = context.applicationContext
            return EncryptedPendingShareRequestStore(
                DiskByteCache(
                    cacheDir = File(app.noBackupFilesDir, STORE_DIRECTORY),
                    maxBytes = PENDING_SHARE_REQUEST_CACHE_BYTES.toLong(),
                    maxEntryBytes = MAX_PENDING_SHARE_REQUEST_BYTES.toLong(),
                    keyProvider = AndroidKeystoreDiskByteCacheKeyProvider(STORE_KEY_ALIAS),
                ),
            )
        }
    }
}

/** Resolves no-backup storage and constructs the encrypted cache away from Main. */
internal suspend fun createPendingShareRequestStore(
    context: Context,
    factory: (Context) -> PendingShareRequestStore = { EncryptedPendingShareRequestStore.create(it) },
): PendingShareRequestStore = withContext(Dispatchers.IO) { factory(context) }

private const val PENDING_SHARE_REQUEST_VERSION = 1
internal const val MAX_PENDING_SHARE_REQUEST_BYTES = 4 * 1024 * 1024
internal const val PENDING_SHARE_REQUEST_CACHE_BYTES = MAX_PENDING_SHARE_REQUEST_BYTES + (64 * 1024)
private const val MAX_PENDING_SHARE_URIS = 1_000
private const val KEY_VERSION = "version"
private const val KEY_REQUEST_ID = "request_id"
private const val KEY_TEXT = "text"
private const val KEY_STREAM_URIS = "stream_uris"
private const val KEY_MIME_TYPE = "mime_type"
private const val KEY_SHORTCUT_ID = "shortcut_id"

internal fun encodePendingShareRequest(request: ShareRequest): ByteArray {
    val streamUris = JSONArray()
    request.payload.streamUris.forEach { streamUris.put(it.toString()) }
    return JSONObject()
        .put(KEY_VERSION, PENDING_SHARE_REQUEST_VERSION)
        .put(KEY_REQUEST_ID, request.requestId)
        .putNullable(KEY_TEXT, request.payload.text)
        .put(KEY_STREAM_URIS, streamUris)
        .putNullable(KEY_MIME_TYPE, request.payload.intentMimeType)
        .putNullable(KEY_SHORTCUT_ID, request.shortcutId)
        .toString()
        .toByteArray(Charsets.UTF_8)
}

internal fun decodePendingShareRequest(
    encoded: ByteArray,
    expectedRequestId: String,
): ShareRequest? {
    if (encoded.isEmpty() || encoded.size > MAX_PENDING_SHARE_REQUEST_BYTES) return null
    return runCatching {
        val json = JSONObject(String(encoded, Charsets.UTF_8))
        require(json.getInt(KEY_VERSION) == PENDING_SHARE_REQUEST_VERSION)
        val requestId = json.getString(KEY_REQUEST_ID)
        require(requestId.isNotBlank() && requestId == expectedRequestId)
        val uriArray = json.getJSONArray(KEY_STREAM_URIS)
        require(uriArray.length() <= MAX_PENDING_SHARE_URIS)
        ShareRequest(
            payload =
                SharePayload(
                    text = json.nullableString(KEY_TEXT),
                    streamUris =
                        List(uriArray.length()) { index ->
                            Uri.parse(uriArray.getString(index)).also { require(it.toString().isNotBlank()) }
                        },
                    intentMimeType = json.nullableString(KEY_MIME_TYPE),
                ),
            shortcutId = json.nullableString(KEY_SHORTCUT_ID),
            requestId = requestId,
        ).takeIf { it.payload.isSupported() }
    }.getOrNull()
}

private fun JSONObject.putNullable(
    key: String,
    value: String?,
): JSONObject = put(key, value ?: JSONObject.NULL)

private fun JSONObject.nullableString(key: String): String? = if (isNull(key)) null else getString(key)
