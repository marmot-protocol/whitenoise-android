package dev.ipf.whitenoise.android.core

import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.ipf.whitenoise.android.media.MediaPipeline
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Bounded decoded-bitmap cache for MDK group images. MDK remains the source of
 * truth: the cache key includes the authoritative image hash, so an updated
 * group record naturally selects new bytes. Nothing is persisted by Android.
 */
internal object GroupAvatarImageLoader {
    private const val CACHE_SIZE_BYTES = 16 * 1024 * 1024
    private const val MAX_EDGE_PX = 512

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val loadPermits = Semaphore(permits = 4)
    private val cache =
        object : LruCache<String, ImageBitmap>(CACHE_SIZE_BYTES) {
            override fun sizeOf(
                key: String,
                value: ImageBitmap,
            ): Int = value.asAndroidBitmap().byteCount.coerceAtLeast(1)
        }
    private val inFlight = mutableMapOf<String, CompletableDeferred<ImageBitmap?>>()
    private var generation = 0L

    fun peek(key: String?): ImageBitmap? =
        key?.let {
            synchronized(lock) { cache.get(it) }
        }

    suspend fun load(
        key: String,
        fetchBytes: suspend () -> ByteArray,
    ): ImageBitmap? {
        peek(key)?.let { return it }
        val request =
            synchronized(lock) {
                cache.get(key)?.let { return@synchronized CompletedGroupAvatarRequest(it) }
                inFlight[key]?.let { return@synchronized PendingGroupAvatarRequest(it) }

                val deferred = CompletableDeferred<ImageBitmap?>()
                inFlight[key] = deferred
                val launchedGeneration = generation
                scope.launch {
                    val image =
                        runCatching {
                            loadPermits.withPermit {
                                MediaPipeline
                                    .decodeSampledBitmap(fetchBytes(), MAX_EDGE_PX)
                                    ?.asImageBitmap()
                            }
                        }.getOrNull()
                    synchronized(lock) {
                        if (launchedGeneration == generation && image != null) {
                            cache.put(key, image)
                        }
                        inFlight.remove(key, deferred)
                        deferred.complete(if (launchedGeneration == generation) image else null)
                    }
                }
                PendingGroupAvatarRequest(deferred)
            }
        return request.await()
    }

    fun clear() {
        synchronized(lock) {
            generation += 1
            cache.evictAll()
            inFlight.values.forEach { it.complete(null) }
            inFlight.clear()
        }
    }
}

private sealed interface GroupAvatarRequest {
    suspend fun await(): ImageBitmap?
}

private class CompletedGroupAvatarRequest(
    private val image: ImageBitmap?,
) : GroupAvatarRequest {
    override suspend fun await(): ImageBitmap? = image
}

private class PendingGroupAvatarRequest(
    private val result: CompletableDeferred<ImageBitmap?>,
) : GroupAvatarRequest {
    override suspend fun await(): ImageBitmap? = result.await()
}
