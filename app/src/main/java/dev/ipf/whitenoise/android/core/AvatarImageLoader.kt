package dev.ipf.whitenoise.android.core

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.LinkedHashMap

object AvatarImageLoader {
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 10_000
    private const val MAX_AVATAR_BYTES = 2 * 1024 * 1024
    private const val MAX_AVATAR_DIMENSION = 512

    // Byte-budgeted cache. With ~1MB worst-case decoded avatar and typical
    // <400KB, this holds dozens of avatars without unbounded memory growth.
    private const val CACHE_SIZE_BYTES = 16 * 1024 * 1024
    private const val FAILURE_TTL_MS = 60_000L
    private const val FAILURE_CACHE_MAX_ENTRIES = 512

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val cache =
        object : LruCache<String, ImageBitmap>(CACHE_SIZE_BYTES) {
            override fun sizeOf(
                key: String,
                value: ImageBitmap,
            ): Int = value.asAndroidBitmap().byteCount.coerceAtLeast(1)
        }
    private val inFlight = mutableMapOf<String, CompletableDeferred<ImageBitmap?>>()
    private val failureExpiresAt = AvatarFailureExpiryCache(FAILURE_CACHE_MAX_ENTRIES)

    // Bumped by clear(); fetches launched under an older generation discard
    // their results so a logout/account-switch can't be re-polluted by an
    // in-flight request that was already on the network.
    private var generation = 0L

    suspend fun load(url: String): ImageBitmap? {
        cached(url)?.let { return it }
        val request =
            synchronized(lock) {
                cache.get(url)?.let { return@synchronized CompletedAvatarRequest(it) }
                if (isFailureFresh(url, System.currentTimeMillis())) {
                    return@synchronized CompletedAvatarRequest(null)
                }
                inFlight[url]?.let { return@synchronized PendingAvatarRequest(it) }
                val deferred = CompletableDeferred<ImageBitmap?>()
                inFlight[url] = deferred
                val launchedGeneration = generation
                scope.launch {
                    val image = runCatching { fetch(url) }.getOrNull()
                    synchronized(lock) {
                        if (launchedGeneration != generation) {
                            // clear() ran while we were in flight; drop the result.
                            inFlight.remove(url, deferred)
                            deferred.complete(null)
                            return@launch
                        }
                        if (image != null) {
                            cache.put(url, image)
                            failureExpiresAt.remove(url)
                        } else {
                            val nowMillis = System.currentTimeMillis()
                            failureExpiresAt.recordFailure(
                                url = url,
                                expiresAtMillis = nowMillis + FAILURE_TTL_MS,
                                nowMillis = nowMillis,
                            )
                        }
                        inFlight.remove(url)
                        // Complete INSIDE the lock so any concurrent `load(url)`
                        // that enters the synchronized block sees a consistent
                        // (cache hit OR fresh failure-fresh state OR pending
                        // entry) — never the gap of "removed from inFlight + not
                        // yet completed" that would let a second fetch slip in
                        // for the same URL.
                        deferred.complete(image)
                    }
                }
                PendingAvatarRequest(deferred)
            }
        return request.await()
    }

    /**
     * Synchronously returns an already-cached avatar for [url], or null when
     * absent. Lets a composable seed its initial state from the in-memory
     * cache so re-entering a screen shows the cached image immediately instead
     * of flashing the placeholder while [load] re-resolves it. In-memory read
     * only — safe to call from composition. See issue #31.
     */
    fun peek(url: String?): ImageBitmap? {
        val key = url ?: return null
        return synchronized(lock) { cache.get(key) }
    }

    fun clear() {
        synchronized(lock) {
            generation++
            cache.evictAll()
            failureExpiresAt.clear()
            inFlight.values.forEach { it.complete(null) }
            inFlight.clear()
        }
    }

    private fun cached(url: String): ImageBitmap? = synchronized(lock) { cache.get(url) }

    private fun isFailureFresh(
        url: String,
        nowMillis: Long,
    ): Boolean = failureExpiresAt.isFresh(url, nowMillis)

    private fun fetch(url: String): ImageBitmap? {
        // Avatar URLs come from remote profile records, so a malicious peer can
        // publish an https URL that 30x-redirects to http or a private host.
        // SafeHttpsGet re-validates scheme/host/port at every hop and bounds the
        // body; we only have to decode the result.
        val bytes =
            SafeHttpsGet.get(
                url = url,
                maxBodyBytes = MAX_AVATAR_BYTES,
                connectTimeoutMillis = CONNECT_TIMEOUT_MS,
                readTimeoutMillis = READ_TIMEOUT_MS,
            ) ?: return null
        return decode(bytes)?.asImageBitmap()
    }

    private fun decode(bytes: ByteArray): android.graphics.Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options =
            BitmapFactory.Options().apply {
                inSampleSize = avatarDecodeSampleSize(bounds.outWidth, bounds.outHeight, MAX_AVATAR_DIMENSION)
            }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        return scaleAvatarBitmapToMaxDimension(decoded, MAX_AVATAR_DIMENSION)
    }
}

internal fun scaleAvatarBitmapToMaxDimension(
    bitmap: android.graphics.Bitmap,
    maxDimension: Int,
): android.graphics.Bitmap {
    val width = bitmap.width
    val height = bitmap.height
    val (targetWidth, targetHeight) = avatarScaledDimensions(width, height, maxDimension)
    if (targetWidth == width && targetHeight == height) return bitmap
    val scaled = android.graphics.Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
    if (scaled !== bitmap && !bitmap.isRecycled) bitmap.recycle()
    return scaled
}

internal fun avatarScaledDimensions(
    width: Int,
    height: Int,
    maxDimension: Int,
): Pair<Int, Int> {
    val longEdge = maxOf(width, height)
    if (longEdge <= maxDimension) return width to height
    return if (width >= height) {
        maxDimension to ((height.toLong() * maxDimension) / width).toInt().coerceAtLeast(1)
    } else {
        ((width.toLong() * maxDimension) / height).toInt().coerceAtLeast(1) to maxDimension
    }
}

internal fun isAvatarFailureFresh(
    expiresAt: Long?,
    nowMillis: Long,
): Boolean = expiresAt != null && nowMillis < expiresAt

internal class AvatarFailureExpiryCache(
    private val maxEntries: Int,
) {
    init {
        require(maxEntries > 0) { "maxEntries must be positive" }
    }

    private val expiresAtByUrl =
        object : LinkedHashMap<String, Long>(maxEntries + 1, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean = size > maxEntries
        }

    val size: Int
        get() = expiresAtByUrl.size

    fun recordFailure(
        url: String,
        expiresAtMillis: Long,
        nowMillis: Long,
    ) {
        removeExpired(nowMillis)
        expiresAtByUrl[url] = expiresAtMillis
    }

    fun remove(url: String) {
        expiresAtByUrl.remove(url)
    }

    fun clear() {
        expiresAtByUrl.clear()
    }

    fun isFresh(
        url: String,
        nowMillis: Long,
    ): Boolean {
        val fresh = isAvatarFailureFresh(expiresAtByUrl[url], nowMillis)
        if (!fresh) {
            expiresAtByUrl.remove(url)
        }
        return fresh
    }

    private fun removeExpired(nowMillis: Long) {
        val iterator = expiresAtByUrl.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (!isAvatarFailureFresh(entry.value, nowMillis)) {
                iterator.remove()
            }
        }
    }
}

internal fun avatarDecodeSampleSize(
    width: Int,
    height: Int,
    maxDimension: Int,
): Int {
    if (width <= maxDimension && height <= maxDimension) return 1
    var sampleSize = 1
    while ((width / sampleSize) > maxDimension || (height / sampleSize) > maxDimension) {
        sampleSize *= 2
    }
    return sampleSize
}

private sealed interface AvatarRequest {
    suspend fun await(): ImageBitmap?
}

private class CompletedAvatarRequest(
    private val image: ImageBitmap?,
) : AvatarRequest {
    override suspend fun await(): ImageBitmap? = image
}

private class PendingAvatarRequest(
    private val deferred: CompletableDeferred<ImageBitmap?>,
) : AvatarRequest {
    override suspend fun await(): ImageBitmap? = deferred.await()
}
