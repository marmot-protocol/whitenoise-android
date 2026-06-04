package dev.ipf.darkmatter.core

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object AvatarImageLoader {
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 10_000
    private const val MAX_AVATAR_BYTES = 2 * 1024 * 1024
    private const val MAX_AVATAR_DIMENSION = 512
    // Byte-budgeted cache. With ~1MB worst-case decoded avatar and typical
    // <400KB, this holds dozens of avatars without unbounded memory growth.
    private const val CACHE_SIZE_BYTES = 16 * 1024 * 1024
    private const val FAILURE_TTL_MS = 60_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val cache = object : LruCache<String, ImageBitmap>(CACHE_SIZE_BYTES) {
        override fun sizeOf(key: String, value: ImageBitmap): Int =
            value.asAndroidBitmap().byteCount.coerceAtLeast(1)
    }
    private val inFlight = mutableMapOf<String, CompletableDeferred<ImageBitmap?>>()
    private val failureExpiresAt = mutableMapOf<String, Long>()
    // Bumped by clear(); fetches launched under an older generation discard
    // their results so a logout/account-switch can't be re-polluted by an
    // in-flight request that was already on the network.
    private var generation = 0L

    suspend fun load(url: String): ImageBitmap? {
        cached(url)?.let { return it }
        val request = synchronized(lock) {
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
                        failureExpiresAt[url] = System.currentTimeMillis() + FAILURE_TTL_MS
                    }
                    inFlight.remove(url)
                }
                deferred.complete(image)
            }
            PendingAvatarRequest(deferred)
        }
        return request.await()
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

    private fun isFailureFresh(url: String, nowMillis: Long): Boolean {
        val fresh = isAvatarFailureFresh(failureExpiresAt[url], nowMillis)
        if (!fresh) failureExpiresAt.remove(url)
        return fresh
    }

    private fun fetch(url: String): ImageBitmap? {
        val connection = URL(url).openConnection() as? HttpURLConnection ?: return null
        return try {
            connection.instanceFollowRedirects = true
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.connect()
            if (connection.url.protocol != "https") return null
            val contentLength = connection.contentLengthLong
            if (contentLength > MAX_AVATAR_BYTES) return null
            val bytes = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    total += read
                    if (total > MAX_AVATAR_BYTES) return null
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            }
            decode(bytes)?.asImageBitmap()
        } finally {
            connection.disconnect()
        }
    }

    private fun decode(bytes: ByteArray): android.graphics.Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = avatarDecodeSampleSize(bounds.outWidth, bounds.outHeight, MAX_AVATAR_DIMENSION)
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }
}

internal fun isAvatarFailureFresh(expiresAt: Long?, nowMillis: Long): Boolean =
    expiresAt != null && nowMillis < expiresAt

internal fun avatarDecodeSampleSize(width: Int, height: Int, maxDimension: Int): Int {
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

private class CompletedAvatarRequest(private val image: ImageBitmap?) : AvatarRequest {
    override suspend fun await(): ImageBitmap? = image
}

private class PendingAvatarRequest(private val deferred: CompletableDeferred<ImageBitmap?>) : AvatarRequest {
    override suspend fun await(): ImageBitmap? = deferred.await()
}
