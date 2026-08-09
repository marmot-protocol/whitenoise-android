@file:Suppress("ReturnCount") // Guard returns keep encrypted-store failures explicit and fail closed.

package dev.ipf.whitenoise.android.media.editor

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import dev.ipf.whitenoise.android.media.AndroidKeystoreDiskByteCacheKeyProvider
import dev.ipf.whitenoise.android.media.DiskByteCache
import dev.ipf.whitenoise.android.media.MediaPipeline
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

internal interface EditorEncryptedPayloadStore {
    fun prepare()

    fun contains(key: String): Boolean

    fun get(key: String): ByteArray?

    fun put(
        key: String,
        bytes: ByteArray,
    ): Boolean

    fun remove(key: String)

    fun clear()
}

private class DiskEditorEncryptedPayloadStore(
    private val cache: DiskByteCache,
) : EditorEncryptedPayloadStore {
    override fun prepare() = cache.prepare()

    override fun contains(key: String): Boolean = cache.contains(key)

    override fun get(key: String): ByteArray? = cache.get(key)

    override fun put(
        key: String,
        bytes: ByteArray,
    ): Boolean {
        cache.put(key, bytes)
        return cache.contains(key)
    }

    override fun remove(key: String) = cache.remove(key)

    override fun clear() = cache.clear()
}

internal data class EditorSourceLease(
    val id: String,
    val sha256Hex: String,
    val plaintextBytes: Int,
    val references: Int,
    val createdAtMs: Long,
)

internal sealed interface EditorSourceStageResult {
    data class Success(
        val lease: EditorSourceLease,
    ) : EditorSourceStageResult

    data object Empty : EditorSourceStageResult

    data object TooLarge : EditorSourceStageResult

    data object BudgetExceeded : EditorSourceStageResult

    data object Unavailable : EditorSourceStageResult
}

@Suppress("TooManyFunctions") // Cohesive lifecycle API for encrypted source leases.
internal class EditorSourceStore(
    private val payloads: EditorEncryptedPayloadStore,
    private val records: EditorStringStore,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val maxSourceBytes: Int = MAX_SOURCE_BYTES,
    private val maxTotalBytes: Long = MAX_TOTAL_SOURCE_BYTES,
) {
    private val lock = Any()
    private var loaded = false
    private var leases = linkedMapOf<String, EditorSourceLease>()

    fun prepare() {
        payloads.prepare()
        synchronized(lock) { ensureLoadedLocked() }
    }

    fun stageUri(
        contentResolver: ContentResolver,
        uri: Uri,
    ): EditorSourceStageResult {
        val bytes =
            try {
                val stream = contentResolver.openInputStream(uri) ?: return EditorSourceStageResult.Unavailable
                stream.use {
                    MediaPipeline.readBoundedBytes(it, maxSourceBytes)
                } ?: return EditorSourceStageResult.TooLarge
            } catch (_: IOException) {
                return EditorSourceStageResult.Unavailable
            } catch (_: SecurityException) {
                return EditorSourceStageResult.Unavailable
            }
        return stageBytes(bytes)
    }

    fun stageBytes(bytes: ByteArray): EditorSourceStageResult {
        if (bytes.isEmpty()) return EditorSourceStageResult.Empty
        if (bytes.size > maxSourceBytes) return EditorSourceStageResult.TooLarge
        val digest = sha256Hex(bytes)
        synchronized(lock) {
            if (!ensureLoadedLocked()) return EditorSourceStageResult.Unavailable
            leases.values
                .firstOrNull { it.sha256Hex == digest && it.plaintextBytes == bytes.size && payloads.contains(it.id) }
                ?.let { existing ->
                    val retained = existing.copy(references = existing.references + 1)
                    if (!persistLocked(leases + (existing.id to retained))) {
                        return EditorSourceStageResult.Unavailable
                    }
                    leases[existing.id] = retained
                    return EditorSourceStageResult.Success(retained)
                }
            val resident = leases.values.sumOf { it.plaintextBytes.toLong() }
            if (resident + bytes.size > maxTotalBytes) return EditorSourceStageResult.BudgetExceeded
            val lease =
                EditorSourceLease(
                    id = newId(),
                    sha256Hex = digest,
                    plaintextBytes = bytes.size,
                    references = 1,
                    createdAtMs = nowMs(),
                )
            if (!payloads.put(lease.id, bytes)) return EditorSourceStageResult.Unavailable
            val updated = leases + (lease.id to lease)
            if (!persistLocked(updated)) {
                payloads.remove(lease.id)
                return EditorSourceStageResult.Unavailable
            }
            leases[lease.id] = lease
            return EditorSourceStageResult.Success(lease)
        }
    }

    fun bytes(leaseId: String): ByteArray? =
        synchronized(lock) {
            if (!ensureLoadedLocked()) return null
            if (leaseId !in leases) null else payloads.get(leaseId)
        }

    fun lease(leaseId: String): EditorSourceLease? =
        synchronized(lock) {
            if (!ensureLoadedLocked()) return null
            leases[leaseId]
        }

    fun retain(leaseId: String): EditorSourceLease? =
        synchronized(lock) {
            if (!ensureLoadedLocked()) return null
            val current = leases[leaseId]?.takeIf { payloads.contains(leaseId) } ?: return null
            val retained = current.copy(references = current.references + 1)
            if (!persistLocked(leases + (leaseId to retained))) return null
            leases[leaseId] = retained
            retained
        }

    fun release(leaseId: String): Boolean =
        synchronized(lock) {
            if (!ensureLoadedLocked()) return false
            val current = leases[leaseId] ?: return false
            if (current.references > 1) {
                val retained = current.copy(references = current.references - 1)
                if (!persistLocked(leases + (leaseId to retained))) return false
                leases[leaseId] = retained
            } else {
                val updated = leases - leaseId
                if (!persistLocked(updated)) return false
                leases.remove(leaseId)
                payloads.remove(leaseId)
            }
            true
        }

    fun reconcile(liveLeaseIds: Set<String>): Int = reconcile(liveLeaseIds.associateWith { 1 })

    /** Reconciles both ownership and deduplicated reference counts after restart. */
    fun reconcile(liveLeaseReferences: Map<String, Int>): Int =
        synchronized(lock) {
            if (!ensureLoadedLocked()) return 0
            val validReferences = liveLeaseReferences.filterValues { it > 0 }
            val stale = leases.keys - validReferences.keys
            val updated =
                leases
                    .filterKeys { it in validReferences }
                    .mapValues { (id, lease) -> lease.copy(references = requireNotNull(validReferences[id])) }
            if (!persistLocked(updated)) return 0
            stale.forEach {
                payloads.remove(it)
            }
            leases = LinkedHashMap(updated)
            stale.size
        }

    fun clear() {
        synchronized(lock) {
            leases.clear()
            loaded = true
            records.clear()
            payloads.clear()
        }
    }

    private fun ensureLoadedLocked(): Boolean {
        if (loaded) return true
        val persisted = records.readAll() ?: return false
        leases =
            persisted
                .mapNotNull { (id, encoded) -> decodeLease(id, encoded)?.let { id to it } }
                .toMap(LinkedHashMap())
        loaded = true
        return true
    }

    private fun persistLocked(updated: Map<String, EditorSourceLease>): Boolean =
        records.replaceAll(
            updated.mapValues { (_, lease) -> encodeLease(lease) },
        )

    private fun encodeLease(lease: EditorSourceLease): String =
        JSONObject()
            .put("sha256", lease.sha256Hex)
            .put("bytes", lease.plaintextBytes)
            .put("references", lease.references)
            .put("created_at_ms", lease.createdAtMs)
            .toString()

    private fun decodeLease(
        id: String,
        encoded: String,
    ): EditorSourceLease? =
        runCatching {
            val json = JSONObject(encoded)
            EditorSourceLease(
                id = id,
                sha256Hex = json.getString("sha256"),
                plaintextBytes = json.getInt("bytes"),
                references = json.getInt("references"),
                createdAtMs = json.getLong("created_at_ms"),
            ).takeIf {
                it.sha256Hex.length == SHA256_HEX_LENGTH &&
                    it.plaintextBytes in 1..maxSourceBytes &&
                    it.references > 0
            }
        }.getOrNull()

    companion object {
        const val MAX_SOURCE_BYTES: Int = 32 * 1024 * 1024
        const val MAX_TOTAL_SOURCE_BYTES: Long = 256L * 1024L * 1024L
        private const val SOURCE_CACHE_ENVELOPE_ALLOWANCE_BYTES = 32L * 1024L * 1024L
        private const val SHA256_HEX_LENGTH = 64
        private const val SOURCE_KEY_ALIAS = "whitenoise.photo_editor_sources.aes_gcm.v1"
        private const val SOURCE_RECORD_KEY_ALIAS = "whitenoise.photo_editor_source_records.aes_gcm.v1"
        private const val SOURCE_RECORD_FILE = "whitenoise.photo_editor_source_records"

        fun create(context: Context): EditorSourceStore {
            val app = context.applicationContext
            val payloads =
                DiskEditorEncryptedPayloadStore(
                    DiskByteCache(
                        cacheDir = File(app.noBackupFilesDir, "photo-editor-sources"),
                        maxBytes = MAX_TOTAL_SOURCE_BYTES + SOURCE_CACHE_ENVELOPE_ALLOWANCE_BYTES,
                        maxEntryBytes = MAX_SOURCE_BYTES.toLong(),
                        keyProvider = AndroidKeystoreDiskByteCacheKeyProvider(SOURCE_KEY_ALIAS),
                    ),
                )
            val records =
                KeystoreEditorStringStore(
                    context = app,
                    fileName = SOURCE_RECORD_FILE,
                    keyAlias = SOURCE_RECORD_KEY_ALIAS,
                )
            return EditorSourceStore(payloads = payloads, records = records)
        }
    }
}

private fun sha256Hex(bytes: ByteArray): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(bytes)
        .joinToString(separator = "") { "%02x".format(it) }
