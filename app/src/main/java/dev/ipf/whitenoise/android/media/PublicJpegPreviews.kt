package dev.ipf.whitenoise.android.media

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.LruCache
import dev.ipf.whitenoise.android.media.sandbox.IsolatedMediaDecoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URI
import java.security.MessageDigest

internal const val PUBLIC_JPEG_MAX_BYTES = 4_194_304L
private const val MAX_EDGE = 4096
private const val MAX_PIXELS = 4_000_000L
private const val CACHE_BYTES = 16 * 1024 * 1024
private const val MAX_FAILURES = 128
private const val FAILURE_AGE_MS = 5 * 60 * 1000L

private const val MAX_LOCATOR_LENGTH = 2048
private const val HTTPS_PORT = 443
private const val MIN_JPEG_BYTES = 4
private const val JPEG_PREFIX = 0xff
private const val JPEG_SOI = 0xd8
private const val JPEG_EOI = 0xd9
private const val JPEG_SOS = 0xda
private const val JPEG_APP2 = 0xe2
private const val JPEG_RESTART_FIRST = 0xd0
private const val JPEG_RESTART_LAST = 0xd7
private const val BYTE_SHIFT = 8
private const val PREVIEW_DECODE_EDGE = 1024
private const val PREVIEW_TIMEOUT_MS = 15_000L
private val multiPictureMagic = byteArrayOf(0x4d, 0x50, 0x46, 0)

/** A content-addressed Blossom locator, never arbitrary web-page image discovery. */
internal fun publicJpegDigest(raw: String): String? =
    runCatching {
        val uri = URI(raw)
        val publicDestination = uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.port in listOf(-1, HTTPS_PORT)
        val unadorned = uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null
        if (raw.length > MAX_LOCATOR_LENGTH || !publicDestination || !unadorned) return null
        Regex("^/([0-9a-fA-F]{64})(?:\\.jpe?g)?$")
            .matchEntire(uri.rawPath)
            ?.groupValues
            ?.get(1)
            ?.lowercase()
    }.getOrNull()

internal fun publicJpegPayloadMatches(
    bytes: ByteArray,
    digest: String,
): Boolean =
    bytes.size in MIN_JPEG_BYTES..PUBLIC_JPEG_MAX_BYTES.toInt() &&
        isStaticJpegEnvelope(bytes) &&
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } == digest

/** Every segment and entropy escape is bounded; trailing images/data and MPO never enter a codec. */
@Suppress("ReturnCount", "CyclomaticComplexMethod", "LoopWithTooManyJumpStatements")
// Keep the bounded marker/entropy state machine together; every rejection precedes its next read.
private fun isStaticJpegEnvelope(bytes: ByteArray): Boolean {
    fun byte(index: Int) = bytes[index].toInt() and JPEG_PREFIX
    if (byte(0) != JPEG_PREFIX || byte(1) != JPEG_SOI) return false
    var offset = 2
    while (offset < bytes.size) {
        if (byte(offset++) != JPEG_PREFIX) return false
        while (offset < bytes.size && byte(offset) == JPEG_PREFIX) offset++
        if (offset >= bytes.size) return false
        val marker = byte(offset++)
        if (marker == JPEG_EOI) return offset == bytes.size
        if (marker == JPEG_SOI || marker == 0 || marker in JPEG_RESTART_FIRST..JPEG_RESTART_LAST) return false
        if (offset + 2 > bytes.size) return false
        val length = (byte(offset) shl BYTE_SHIFT) or byte(offset + 1)
        if (length < 2 || offset + length > bytes.size) return false
        val payload = offset + 2
        if (marker == JPEG_APP2 &&
            length >= 2 + multiPictureMagic.size &&
            bytes.copyOfRange(payload, payload + multiPictureMagic.size).contentEquals(multiPictureMagic)
        ) {
            return false
        }
        offset += length
        if (marker == JPEG_SOS) {
            while (offset < bytes.size) {
                if (byte(offset++) != JPEG_PREFIX) continue
                if (offset >= bytes.size) return false
                val code = byte(offset)
                if (code == 0 || code in JPEG_RESTART_FIRST..JPEG_RESTART_LAST) {
                    offset++
                    continue
                }
                // The outer loop consumes the marker after the entropy-coded scan.
                offset--
                break
            }
        }
    }
    return false
}

/** Device-local rendering consent; defaults to no receive-side network requests. */
internal class PublicJpegPreviewPreferences(
    context: Context,
) {
    private val preferences = context.getSharedPreferences("whitenoise.public_jpeg_previews", Context.MODE_PRIVATE)
    private val choices =
        MutableStateFlow(preferences.all.mapNotNull { (k, v) -> (v as? Boolean)?.let { k to it } }.toMap())
    val state = choices.asStateFlow()
    private var retainedAccountIds: Set<String>? = null
    private val consentRevisions = mutableMapOf<String, Long>()

    fun enabled(accountRef: String): Boolean = choices.value[accountRef] == true

    /** Account removal revokes consent immediately and prunes its device-local preference. */
    fun retainAccounts(accountIds: Collection<String>): Set<String> =
        synchronized(this) {
            val keep = accountIds.toSet()
            retainedAccountIds = keep
            val removed = choices.value.keys - keep
            (choices.value.keys + consentRevisions.keys - keep).forEach { id ->
                consentRevisions[id] = (consentRevisions[id] ?: 0L) + 1
            }
            choices.value = choices.value.filterKeys { it in keep }
            preferences.edit().also { editor -> removed.forEach(editor::remove) }.apply()
            removed
        }

    /** Revoke immediately and schedule persistence independently of the settings screen lifetime. */
    fun revoke(accountId: String) {
        synchronized(this) {
            if (retainedAccountIds?.contains(accountId) == false) return
            consentRevisions[accountId] = (consentRevisions[accountId] ?: 0L) + 1
            choices.value = choices.value + (accountId to false)
            preferences.edit().putBoolean(accountId, false).apply()
        }
    }

    suspend fun setEnabled(
        accountRef: String,
        enabled: Boolean,
    ): Boolean {
        val revision =
            synchronized(this) {
                ((consentRevisions[accountRef] ?: 0L) + 1).also { consentRevisions[accountRef] = it }
            }
        return withContext(Dispatchers.IO) {
            synchronized(this@PublicJpegPreviewPreferences) {
                if (retainedAccountIds?.contains(accountRef) == false || consentRevisions[accountRef] != revision) {
                    return@synchronized false
                }
                val previous = this@PublicJpegPreviewPreferences.enabled(accountRef)
                // Revoke before disk I/O so queued work observes OFF immediately.
                if (!enabled) choices.value = choices.value + (accountRef to false)
                val saved = preferences.edit().putBoolean(accountRef, enabled).commit()
                if (saved) {
                    choices.value = choices.value + (accountRef to enabled)
                } else {
                    preferences.edit().putBoolean(accountRef, previous).apply()
                    choices.value = choices.value + (accountRef to previous)
                }
                saved
            }
        }
    }
}

/**
 * A bounded cache of decoded host pixels, not message/protocol data. No disk cache or new HTTP client.
 * The caller supplies MDK's dial-safe downloader and rechecks current consent/network/owner at admission.
 * A single cancellable worker also coalesces simultaneous requests for the same content.
 */
internal class PublicJpegPreviewLoader {
    private val worker = Mutex()
    private val pixels =
        object : LruCache<String, Bitmap>(CACHE_BYTES) {
            override fun sizeOf(
                key: String,
                value: Bitmap,
            ): Int = value.allocationByteCount
        }
    private val failures =
        object : LinkedHashMap<String, Long>() {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>): Boolean = size > MAX_FAILURES
        }

    /** Drops only decoded-cache references; active images, consent and request serialization stay intact. */
    fun trimMemory(trim: PresentationMemoryTrim) {
        pixels.trimToSize(trim.targetBytes(CACHE_BYTES.toLong()).toInt())
    }

    fun clearPixels(accountId: String) {
        pixels
            .snapshot()
            .keys
            .filter { it.split(':').getOrNull(1) == accountId }
            .forEach(pixels::remove)
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    // One serialized, cancellable acquisition and admission boundary.
    suspend fun load(
        owner: String,
        url: String,
        allowed: () -> Boolean,
        fetch: suspend (String, ULong) -> ByteArray,
    ): Bitmap? =
        withContext(Dispatchers.IO) {
            val digest = publicJpegDigest(url) ?: return@withContext null
            val key = "$owner:$digest"
            worker.withLock {
                if (!allowed()) return@withLock null
                pixels.get(key)?.let { return@withLock it }
                if ((failures[key] ?: Long.MIN_VALUE) > SystemClock.elapsedRealtime() - FAILURE_AGE_MS) {
                    return@withLock null
                }
                var policyRejected = false

                fun admitted() = allowed().also { if (!it) policyRejected = true }
                try {
                    withTimeoutOrNull(PREVIEW_TIMEOUT_MS) {
                        if (!admitted()) return@withTimeoutOrNull null
                        val bytes = fetch(url, PUBLIC_JPEG_MAX_BYTES.toULong())
                        if (!admitted() || !publicJpegPayloadMatches(bytes, digest)) return@withTimeoutOrNull null
                        val info = IsolatedMediaDecoder.inspect(bytes) ?: return@withTimeoutOrNull null
                        val dimensionsAllowed =
                            info.width in 1..MAX_EDGE &&
                                info.height in 1..MAX_EDGE &&
                                info.width.toLong() * info.height <= MAX_PIXELS
                        if (info.mime != "image/jpeg" || !dimensionsAllowed) return@withTimeoutOrNull null
                        val image = IsolatedMediaDecoder.decode(bytes, edge = PREVIEW_DECODE_EDGE, oriented = true)
                        image?.takeIf { admitted() }?.also {
                            pixels.put(key, it)
                            if (!admitted()) pixels.remove(key)
                        }
                    }.also {
                        if (it == null && !policyRejected && allowed()) failures[key] = SystemClock.elapsedRealtime()
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    if (!policyRejected && allowed()) failures[key] = SystemClock.elapsedRealtime()
                    null
                }
            }
        }
}
