package dev.ipf.whitenoise.android.core

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.ipf.whitenoise.android.state.StalenessGuard
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import java.util.LinkedHashMap

@Suppress("TooManyFunctions") // Cohesive process-wide avatar cache, fetch, and lifecycle boundary.
object AvatarImageLoader {
    private const val MAX_AVATAR_BYTES = 2 * 1024 * 1024
    private const val MAX_AVATAR_DIMENSION = 512

    // Byte-budgeted cache. With ~1MB worst-case decoded avatar and typical
    // <400KB, this holds dozens of avatars without unbounded memory growth.
    private const val CACHE_SIZE_BYTES = 16 * 1024 * 1024
    private const val FAILURE_TTL_MS = 60_000L
    private const val FAILURE_CACHE_MAX_ENTRIES = 512
    private const val FETCH_CONCURRENCY = 4
    private const val NOTIFICATION_FETCH_CONCURRENCY = 2
    private const val REGULAR_FETCH_CONCURRENCY = FETCH_CONCURRENCY - NOTIFICATION_FETCH_CONCURRENCY
    private const val PREWARM_MAX_QUEUED = 64

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val fetchGate = AvatarFetchGate(REGULAR_FETCH_CONCURRENCY, NOTIFICATION_FETCH_CONCURRENCY)
    private val lock = Any()
    private val cache =
        object : LruCache<String, ImageBitmap>(CACHE_SIZE_BYTES) {
            override fun sizeOf(
                key: String,
                value: ImageBitmap,
            ): Int = value.asAndroidBitmap().byteCount.coerceAtLeast(1)
        }
    private val inFlight = mutableMapOf<String, AvatarInFlightRequest>()

    // staleness-exempt: captured cache-lifetime tokens for bounded queued work, not a counter owner.
    private val preWarmQueuedGeneration = mutableMapOf<String, Long>()
    private val failureExpiresAt = AvatarFailureExpiryCache(FAILURE_CACHE_MAX_ENTRIES)
    private var profileImageFetcher: (suspend (String, ULong) -> ByteArray)? = null

    // Bumped by clear(); fetches launched under an older generation discard
    // their results so a logout/account-switch can't be re-polluted by an
    // in-flight request that was already on the network.
    private val cacheLifetime = StalenessGuard()

    /**
     * Attach the process-owned Marmot profile-image fetch. MDK owns URL
     * validation, DNS pinning, redirect validation, timeouts, and byte bounds;
     * this loader owns only request deduplication, decoding, and the memory
     * cache used by Android presentation surfaces.
     */
    internal fun attachProfileImageFetcher(fetcher: suspend (String, ULong) -> ByteArray) {
        synchronized(lock) {
            profileImageFetcher = fetcher
        }
    }

    /** Test-only lifecycle boundary for the process-global MDK fetch adapter. */
    internal fun resetProfileImageFetcherForTests() {
        synchronized(lock) {
            profileImageFetcher = null
        }
    }

    suspend fun load(url: String): ImageBitmap? =
        load(
            url = url,
            expectedGeneration = null,
            fetchLane = AvatarFetchLane.REGULAR,
        )

    /**
     * Queue an avatar fetch without delaying the caller. Chat/profile projection
     * uses this ahead of notification delivery so the first shortcut/person icon
     * can take the synchronous cache path. Work is URL-deduplicated, bounded to a
     * small network fan-out, and generation-guarded so account teardown cannot be
     * followed by an old queued warm repopulating the cache.
     */
    fun preWarm(url: String?) {
        val key = url?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val scheduledGeneration =
            synchronized(lock) {
                val nowMillis = System.currentTimeMillis()
                if (cache.get(key) != null ||
                    isFailureFresh(key, nowMillis) ||
                    inFlight.containsKey(key) ||
                    preWarmQueuedGeneration.containsKey(key) ||
                    preWarmQueuedGeneration.size >= PREWARM_MAX_QUEUED
                ) {
                    return
                }
                cacheLifetime.capture().also { preWarmQueuedGeneration[key] = it }
            }
        scope.launch {
            try {
                // Admit speculative work before it is registered in `inFlight`.
                // A demand for a URL behind this queue can therefore start its
                // own fetch through the reserved lane instead of joining a
                // deferred that has not acquired network capacity yet.
                fetchGate.withPreWarmAdmission {
                    load(
                        url = key,
                        expectedGeneration = scheduledGeneration,
                        fetchLane = AvatarFetchLane.PREWARM_ADMITTED,
                        waitForDetachedFetch = true,
                    )
                }
            } finally {
                synchronized(lock) {
                    if (preWarmQueuedGeneration[key] == scheduledGeneration) {
                        preWarmQueuedGeneration.remove(key)
                    }
                }
            }
        }
    }

    /** Shares one bounded fetch and rejects cache publication after an account-scoped clear. */
    @Suppress("LongMethod") // Request deduplication and generation-safe completion form one atomic lifecycle.
    private suspend fun load(
        url: String,
        expectedGeneration: Long?,
        fetchLane: AvatarFetchLane,
        waitForDetachedFetch: Boolean = false,
    ): ImageBitmap? {
        cached(url)?.let { return it }
        val request =
            synchronized(lock) {
                if (expectedGeneration != null && !cacheLifetime.isCurrent(expectedGeneration)) {
                    return@synchronized CompletedAvatarRequest(null)
                }
                cache.get(url)?.let { return@synchronized CompletedAvatarRequest(it) }
                if (isFailureFresh(url, System.currentTimeMillis())) {
                    return@synchronized CompletedAvatarRequest(null)
                }
                inFlight[url]?.let {
                    // A prewarm admission is only for starting new regular-lane work.
                    // Do not hold it while an existing demand fetch finishes.
                    return@synchronized if (fetchLane == AvatarFetchLane.PREWARM_ADMITTED) {
                        CompletedAvatarRequest(null)
                    } else {
                        PendingAvatarRequest(it)
                    }
                }
                val inFlightRequest = AvatarInFlightRequest()
                val deferred = inFlightRequest.result
                inFlight[url] = inFlightRequest
                val launchedGeneration = cacheLifetime.capture()
                scope.launch {
                    try {
                        // Gate the detached fetch itself, not the caller awaiting
                        // its result. clear() completes result waiters immediately
                        // but cannot end a blocking socket read; the fetch permit
                        // remains held until the socket returns.
                        val fetchResult =
                            runCatching {
                                fetchGate.withPermit(fetchLane) {
                                    if (synchronized(lock) { !cacheLifetime.isCurrent(launchedGeneration) }) {
                                        AvatarImageFetchResult.Unavailable
                                    } else {
                                        fetch(url)
                                    }
                                }
                            }.getOrElse { AvatarImageFetchResult.Failed }
                        val image = (fetchResult as? AvatarImageFetchResult.Success)?.image
                        synchronized(lock) {
                            if (!cacheLifetime.isCurrent(launchedGeneration)) {
                                // clear() ran while we were in flight; drop the result.
                                inFlight.remove(url, inFlightRequest)
                                deferred.complete(null)
                                return@launch
                            }
                            when (fetchResult) {
                                is AvatarImageFetchResult.Success -> {
                                    cache.put(url, fetchResult.image)
                                    failureExpiresAt.remove(url)
                                }
                                AvatarImageFetchResult.Failed -> {
                                    val nowMillis = System.currentTimeMillis()
                                    failureExpiresAt.recordFailure(
                                        url = url,
                                        expiresAtMillis = nowMillis + FAILURE_TTL_MS,
                                        nowMillis = nowMillis,
                                    )
                                }
                                AvatarImageFetchResult.Unavailable -> Unit
                            }
                            inFlight.remove(url, inFlightRequest)
                            // Complete INSIDE the lock so any concurrent `load(url)`
                            // that enters the synchronized block sees a consistent
                            // (cache hit OR fresh failure-fresh state OR pending
                            // entry) — never the gap of "removed from inFlight + not
                            // yet completed" that would let a second fetch slip in
                            // for the same URL.
                            deferred.complete(image)
                        }
                    } finally {
                        // Separate from `result`: clear() deliberately wakes UI
                        // waiters early, while speculative admission must remain
                        // occupied until the detached socket has actually stopped.
                        inFlightRequest.fetchCompleted.complete(Unit)
                    }
                }
                PendingAvatarRequest(inFlightRequest)
            }
        return request.await(waitForDetachedFetch)
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

    /** Test-only injection for deterministic first-frame composition coverage. */
    internal fun putCached(
        url: String,
        image: ImageBitmap,
    ) {
        val key = url.trim()
        if (key.isEmpty()) return
        synchronized(lock) {
            cache.put(key, image)
            failureExpiresAt.remove(key)
        }
    }

    /** Android-bitmap view of [peek], for non-Compose consumers (notification icons). */
    fun peekBitmap(url: String?): android.graphics.Bitmap? = peek(url)?.asAndroidBitmap()

    /** Android-bitmap view of [load], using capacity reserved for notification icons. */
    suspend fun loadBitmap(url: String): android.graphics.Bitmap? =
        load(
            url = url,
            expectedGeneration = null,
            fetchLane = AvatarFetchLane.NOTIFICATION,
        )?.asAndroidBitmap()

    /** Wipes cached avatars and invalidates every queued or in-flight fetch. */
    fun clear() {
        synchronized(lock) {
            cacheLifetime.advance()
            cache.evictAll()
            failureExpiresAt.clear()
            preWarmQueuedGeneration.clear()
            inFlight.values.forEach { it.result.complete(null) }
            inFlight.clear()
        }
    }

    private fun cached(url: String): ImageBitmap? = synchronized(lock) { cache.get(url) }

    private fun isFailureFresh(
        url: String,
        nowMillis: Long,
    ): Boolean = failureExpiresAt.isFresh(url, nowMillis)

    @Suppress("ReturnCount") // Fail-closed guards keep invalid limits and an unattached MDK adapter explicit.
    internal suspend fun fetchBytes(
        url: String,
        maxBytes: Int,
    ): AvatarByteFetchResult {
        if (maxBytes <= 0) return AvatarByteFetchResult.Failed
        val fetcher = synchronized(lock) { profileImageFetcher } ?: return AvatarByteFetchResult.Unavailable
        val bytes = fetcher(url, maxBytes.toULong())
        return if (bytes.size <= maxBytes) AvatarByteFetchResult.Success(bytes) else AvatarByteFetchResult.Failed
    }

    private suspend fun fetch(url: String): AvatarImageFetchResult =
        when (val result = fetchBytes(url, MAX_AVATAR_BYTES)) {
            is AvatarByteFetchResult.Success ->
                decode(result.bytes)
                    ?.asImageBitmap()
                    ?.let(AvatarImageFetchResult::Success)
                    ?: AvatarImageFetchResult.Failed
            AvatarByteFetchResult.Failed -> AvatarImageFetchResult.Failed
            AvatarByteFetchResult.Unavailable -> AvatarImageFetchResult.Unavailable
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

internal sealed interface AvatarByteFetchResult {
    data class Success(
        val bytes: ByteArray,
    ) : AvatarByteFetchResult

    data object Failed : AvatarByteFetchResult

    data object Unavailable : AvatarByteFetchResult
}

private sealed interface AvatarImageFetchResult {
    data class Success(
        val image: ImageBitmap,
    ) : AvatarImageFetchResult

    data object Failed : AvatarImageFetchResult

    data object Unavailable : AvatarImageFetchResult
}

internal enum class AvatarFetchLane {
    REGULAR,
    NOTIFICATION,
    PREWARM_ADMITTED,
}

/**
 * Keeps notification image loads off the regular avatar queue. The two lane
 * counts add up to the loader's single hard network bound, while two notification
 * permits let a group shortcut and sender icon resolve in parallel.
 */
internal class AvatarFetchGate(
    regularPermits: Int,
    notificationPermits: Int,
) {
    init {
        require(regularPermits > 0) { "regularPermits must be positive" }
        require(notificationPermits > 0) { "notificationPermits must be positive" }
    }

    private val regular = Semaphore(regularPermits)
    private val notification = Semaphore(notificationPermits)

    suspend fun <T> withPermit(
        lane: AvatarFetchLane,
        block: suspend () -> T,
    ): T =
        when (lane) {
            AvatarFetchLane.REGULAR -> regular.withPermit(block)
            AvatarFetchLane.NOTIFICATION -> notification.withPermit(block)
            AvatarFetchLane.PREWARM_ADMITTED -> block()
        }

    suspend fun <T> withPreWarmAdmission(block: suspend () -> T): T = regular.withPermit(block)

    suspend fun <T> withNotificationPermit(block: suspend () -> T): T = notification.withPermit(block)

    private suspend fun <T> Semaphore.withPermit(block: suspend () -> T): T {
        acquire()
        return try {
            block()
        } finally {
            release()
        }
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
    suspend fun await(waitForDetachedFetch: Boolean): ImageBitmap?
}

private class CompletedAvatarRequest(
    private val image: ImageBitmap?,
) : AvatarRequest {
    override suspend fun await(waitForDetachedFetch: Boolean): ImageBitmap? = image
}

private class AvatarInFlightRequest {
    val result = CompletableDeferred<ImageBitmap?>()
    val fetchCompleted = CompletableDeferred<Unit>()
}

private class PendingAvatarRequest(
    private val request: AvatarInFlightRequest,
) : AvatarRequest {
    override suspend fun await(waitForDetachedFetch: Boolean): ImageBitmap? {
        val image = request.result.await()
        if (waitForDetachedFetch) request.fetchCompleted.await()
        return image
    }
}
