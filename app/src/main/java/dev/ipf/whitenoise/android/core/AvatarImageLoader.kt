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
    private const val MAX_AVATAR_DIMENSION = PROFILE_AVATAR_MAX_DIMENSION

    // A full-width 2:1 banner is stretched across the whole screen, so the
    // avatar cap would leave it upscaled from 512x256 (#2762). Its decode is
    // bounded by its own fetch ceiling here, and by the dimension and byte
    // budgets the shared helpers below apply.
    private const val MAX_BANNER_BYTES = 4 * 1024 * 1024

    // Byte-budgeted cache. With ~1MB worst-case decoded avatar and typical
    // <400KB, the first 16MB holds dozens of avatars without unbounded memory
    // growth; the second 8MB is the banner allowance, so a banner entry cannot
    // evict the avatar working set that was sized against it.
    private const val CACHE_SIZE_BYTES = 24 * 1024 * 1024
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

    // staleness-exempt: captured request-lifetime tokens for bounded queued work, not a counter owner.
    private val preWarmQueuedGeneration = mutableMapOf<String, Long>()
    private val failureExpiresAt = AvatarFailureExpiryCache(FAILURE_CACHE_MAX_ENTRIES)
    private var profileImageFetcher: (suspend (String, ULong) -> ByteArray)? = null

    // Bumped by clear(); fetches launched under an older generation discard
    // their results so a logout/account-switch can't be re-polluted by an
    // in-flight request that was already on the network.
    private val cacheLifetime = StalenessGuard()
    private val requestLifetime = StalenessGuard()

    /**
     * Attach the process-owned Marmot profile-image fetch. MDK owns URL
     * validation, DNS pinning, redirect validation, timeouts, and byte bounds;
     * this loader owns only request deduplication, decoding, and the memory
     * cache used by Android presentation surfaces.
     */
    internal fun attachProfileImageFetcher(fetcher: suspend (String, ULong) -> ByteArray) {
        val becameAvailable =
            synchronized(lock) {
                val wasUnavailable = profileImageFetcher == null
                profileImageFetcher = fetcher
                wasUnavailable
            }
        if (becameAvailable) AvatarLoadRecovery.onFetcherAvailable()
    }

    /** Test-only lifecycle boundary for the process-global MDK fetch adapter. */
    internal fun resetProfileImageFetcherForTests() {
        synchronized(lock) {
            profileImageFetcher = null
        }
    }

    suspend fun load(url: String): ImageBitmap? =
        load(
            request = avatarRequest(url),
            expectedGeneration = null,
            fetchLane = AvatarFetchLane.REGULAR,
        )

    /**
     * Loads [url] for a full-width profile banner, decoded for a box [targetWidthPx] wide.
     *
     * The target is bucketed and bounded, and the bucket is part of the cache and in-flight keys, so
     * a banner request can never be answered by the avatar-sized bitmap of the same URL while two
     * banner surfaces at comparable widths still share one decode. The bytes come through the same
     * MarmotKit fetcher as every other profile image.
     */
    suspend fun loadBanner(
        url: String,
        targetWidthPx: Int,
    ): ImageBitmap? =
        load(
            request = bannerRequest(url, targetWidthPx),
            expectedGeneration = null,
            fetchLane = AvatarFetchLane.REGULAR,
        )

    /** Synchronous banner counterpart to [peek]; an avatar-sized entry never satisfies it. */
    fun peekBanner(
        url: String?,
        targetWidthPx: Int,
    ): ImageBitmap? {
        val key = url?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return synchronized(lock) { cache.get(bannerRequest(key, targetWidthPx).cacheKey) }
    }

    /** The avatar-variant request for [url], whose cache key stays the bare URL. */
    private fun avatarRequest(url: String) =
        ProfileImageRequest(
            url = url,
            variant = ProfileImageVariant.AVATAR,
            maxDimension = MAX_AVATAR_DIMENSION,
            maxBytes = MAX_AVATAR_BYTES,
        )

    /** The banner-variant request for [url] at the bucket [targetWidthPx] rounds up into. */
    private fun bannerRequest(
        url: String,
        targetWidthPx: Int,
    ) = ProfileImageRequest(
        url = url,
        variant = ProfileImageVariant.BANNER,
        maxDimension = profileBannerDecodeDimension(targetWidthPx),
        maxBytes = MAX_BANNER_BYTES,
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
                requestLifetime.capture().also { preWarmQueuedGeneration[key] = it }
            }
        scope.launch {
            try {
                // Admit speculative work before it is registered in `inFlight`.
                // A demand for a URL behind this queue can therefore start its
                // own fetch through the reserved lane instead of joining a
                // deferred that has not acquired network capacity yet.
                fetchGate.withPreWarmAdmission {
                    load(
                        request = avatarRequest(key),
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
        request: ProfileImageRequest,
        expectedGeneration: Long?,
        fetchLane: AvatarFetchLane,
        waitForDetachedFetch: Boolean = false,
    ): ImageBitmap? {
        val url = request.cacheKey
        cached(url)?.let { return it }
        val pending =
            synchronized(lock) {
                if (expectedGeneration != null && !requestLifetime.isCurrent(expectedGeneration)) {
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
                val launchedRequest = requestLifetime.capture()
                scope.launch {
                    try {
                        // Gate the detached fetch itself, not the caller awaiting
                        // its result. clear() completes result waiters immediately
                        // but cannot end a blocking socket read; the fetch permit
                        // remains held until the socket returns.
                        val fetchResult =
                            runCatching {
                                fetchGate.withPermit(fetchLane) {
                                    if (!isCurrentRequest(launchedGeneration, launchedRequest)) {
                                        AvatarImageFetchResult.Unavailable
                                    } else {
                                        fetch(request)
                                    }
                                }
                            }.getOrElse { AvatarImageFetchResult.Failed }
                        val image = (fetchResult as? AvatarImageFetchResult.Success)?.image
                        synchronized(lock) {
                            if (!isCurrentRequest(launchedGeneration, launchedRequest)) {
                                // Teardown or recovery retired this work, including late failures.
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
        return pending.await(waitForDetachedFetch)
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

    /** Test-only injection of a banner-variant entry, for deterministic first-frame coverage. */
    internal fun putCachedBanner(
        url: String,
        targetWidthPx: Int,
        image: ImageBitmap,
    ) {
        val key = url.trim().takeIf { it.isNotEmpty() } ?: return
        val cacheKey = bannerRequest(key, targetWidthPx).cacheKey
        synchronized(lock) {
            cache.put(cacheKey, image)
            failureExpiresAt.remove(cacheKey)
        }
    }

    /** Android-bitmap view of [peek], for non-Compose consumers (notification icons). */
    fun peekBitmap(url: String?): android.graphics.Bitmap? = peek(url)?.asAndroidBitmap()

    /** Android-bitmap view of [load], using capacity reserved for notification icons. */
    suspend fun loadBitmap(url: String): android.graphics.Bitmap? =
        load(
            request = avatarRequest(url),
            expectedGeneration = null,
            fetchLane = AvatarFetchLane.NOTIFICATION,
        )?.asAndroidBitmap()

    /** Wipes cached avatars and invalidates every queued or in-flight fetch. */
    fun clear() {
        synchronized(lock) {
            cacheLifetime.advance()
            cache.evictAll()
            retireRequestsLocked()
        }
    }

    /** Retires failed/pending work but preserves decoded pixels and detached socket permits. */
    internal fun prepareForRecovery() {
        synchronized(lock) { retireRequestsLocked() }
    }

    /** Advance before waking waiters so queued work and late failures cannot enter the new lifetime. */
    private fun retireRequestsLocked() {
        requestLifetime.advance()
        failureExpiresAt.clear()
        preWarmQueuedGeneration.clear()
        inFlight.values.forEach { it.result.complete(null) }
        inFlight.clear()
    }

    /** Checks both account teardown and request retirement while holding the loader publication lock. */
    private fun isCurrentRequest(
        cacheGeneration: Long,
        requestGeneration: Long,
    ): Boolean =
        synchronized(lock) {
            cacheLifetime.isCurrent(cacheGeneration) && requestLifetime.isCurrent(requestGeneration)
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

    private suspend fun fetch(request: ProfileImageRequest): AvatarImageFetchResult =
        when (val result = fetchBytes(request.url, request.maxBytes)) {
            is AvatarByteFetchResult.Success ->
                decode(result.bytes, request.variant, request.maxDimension)
                    ?.asImageBitmap()
                    ?.let(AvatarImageFetchResult::Success)
                    ?: AvatarImageFetchResult.Failed
            AvatarByteFetchResult.Failed -> AvatarImageFetchResult.Failed
            AvatarByteFetchResult.Unavailable -> AvatarImageFetchResult.Unavailable
        }

    /** The image already held for [key], whether it came from a URL fetch or MarmotKit's durable store. */
    internal fun cachedImage(key: String): ImageBitmap? = cached(key.trim())

    /** The cache lifetime a durable read must capture before it suspends; [clear] makes it stale. */
    internal fun currentCacheLifetime(): Long = cacheLifetime.capture()

    /**
     * Decodes avatar bytes MarmotKit already validated and stored, caching them under [key] unless the
     * cache was cleared after [lifetime] was captured, so a read that outlives a sign-out cannot repopulate
     * the next account's cache. It reuses the same sampling and dimension budget as a fetched avatar, so a
     * durable avatar costs the cache no more than the URL it replaces. Null when the bytes do not decode or
     * the publication is stale.
     */
    internal fun decodeAndCache(
        key: String,
        bytes: ByteArray,
        lifetime: Long,
    ): ImageBitmap? {
        val image = decode(bytes, ProfileImageVariant.AVATAR, MAX_AVATAR_DIMENSION)?.asImageBitmap() ?: return null
        val published =
            synchronized(lock) {
                cacheLifetime.isCurrent(lifetime).also { current -> if (current) putCached(key, image) }
            }
        return image.takeIf { published }
    }

    private fun decode(
        bytes: ByteArray,
        variant: ProfileImageVariant,
        maxDimension: Int,
    ): android.graphics.Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val target = boundedDecodeDimension(bounds.outWidth, bounds.outHeight, maxDimension)
        val options =
            BitmapFactory.Options().apply {
                inSampleSize = profileDecodeSampleSize(bounds.outWidth, bounds.outHeight, variant, target)
            }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        return scaleAvatarBitmapToMaxDimension(decoded, target)
    }
}

/** The two decode policies the shared profile-image loader serves (#2762). */
internal enum class ProfileImageVariant {
    AVATAR,
    BANNER,
}

/**
 * One profile-image fetch: where the bytes come from, and how large the decode may be.
 *
 * [cacheKey] carries the variant and target bucket, so a cached avatar cannot satisfy a banner
 * request and two banner requests only share a fetch when their decodes would be identical.
 */
internal data class ProfileImageRequest(
    val url: String,
    val variant: ProfileImageVariant,
    val maxDimension: Int,
    val maxBytes: Int,
) {
    val cacheKey: String get() = profileImageCacheKey(url, variant, maxDimension)
}

/** Avatar entries keep the bare URL, so durable seeds and every existing caller are unchanged. */
internal fun profileImageCacheKey(
    url: String,
    variant: ProfileImageVariant,
    maxDimension: Int,
): String =
    when (variant) {
        ProfileImageVariant.AVATAR -> url
        ProfileImageVariant.BANNER -> "banner:$maxDimension $url"
    }

/** Decoded pixels are ARGB_8888, so a target's memory cost is four bytes each. */
private const val DECODED_BYTES_PER_PIXEL = 4

/** No single decode may exceed this, whatever aspect ratio the source has. */
internal const val MAX_PROFILE_IMAGE_DECODED_BYTES: Int = 8 * 1024 * 1024

/**
 * The largest long-edge target at or below [maxDimension] whose decode fits the byte budget.
 *
 * The dimension cap alone bounds a 2:1 banner, but a near-square source at the same long edge costs
 * twice as much, so the budget halves the target until the decode is affordable. For an avatar's
 * 512px cap the budget never binds, which keeps avatar memory exactly where it was.
 */
internal fun boundedDecodeDimension(
    width: Int,
    height: Int,
    maxDimension: Int,
    maxDecodedBytes: Int = MAX_PROFILE_IMAGE_DECODED_BYTES,
): Int {
    var limit = maxDimension
    while (limit > 1) {
        val (scaledWidth, scaledHeight) = avatarScaledDimensions(width, height, limit)
        val decodedBytes = scaledWidth.toLong() * scaledHeight * DECODED_BYTES_PER_PIXEL
        if (decodedBytes <= maxDecodedBytes) return limit
        limit /= 2
    }
    return 1
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

/**
 * Bounded long-edge target for a banner rendered [requestedWidthPx] wide.
 *
 * Rounding up to a shared bucket means two surfaces whose measured widths differ by a few pixels
 * agree on one decode — and therefore one cache entry and one in-flight fetch — instead of racing
 * two. The floor is the avatar cap, so a banner is never decoded smaller than it already was, and
 * the ceiling keeps an implausibly wide window from asking for an unbounded bitmap.
 */
internal fun profileBannerDecodeDimension(
    requestedWidthPx: Int,
    bucketPx: Int = PROFILE_BANNER_TARGET_BUCKET_PX,
    minimumPx: Int = PROFILE_AVATAR_MAX_DIMENSION,
    maximumPx: Int = PROFILE_BANNER_MAX_DIMENSION,
): Int {
    val buckets = (requestedWidthPx.coerceAtLeast(0) + bucketPx - 1) / bucketPx
    return (buckets * bucketPx).coerceIn(minimumPx, maximumPx)
}

/** Mirrors the loader's own avatar cap, so a banner can never be decoded below it. */
internal const val PROFILE_AVATAR_MAX_DIMENSION: Int = 512

/** Hard upper bound on a banner's decoded long edge, whatever width a window reports. */
internal const val PROFILE_BANNER_MAX_DIMENSION: Int = 1536

/** Rendered widths round up to this bucket so neighbouring surfaces coalesce onto one decode. */
internal const val PROFILE_BANNER_TARGET_BUCKET_PX: Int = 256

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

/** A transient decode may exceed the final budget while it is being scaled, but only this far. */
private const val TRANSIENT_DECODE_BUDGET_MULTIPLIER = 2

/**
 * Power-of-two sample whose decode still covers [targetDimension] on the long edge.
 *
 * [avatarDecodeSampleSize] samples until both edges are at or below its cap, which is right for a
 * ceiling and wrong for a target: a 4000px source sampled down to 1000px for a 1280px banner is then
 * upscaled again by the very surface that asked for 1280, which is the blur this avoids. Keeping one
 * sampling step in hand lets [scaleAvatarBitmapToMaxDimension] land on the target exactly. The
 * transient decode that step buys is itself bounded, so a very large source steps back down instead.
 */
internal fun bannerDecodeSampleSize(
    width: Int,
    height: Int,
    targetDimension: Int,
    maxTransientBytes: Int = MAX_PROFILE_IMAGE_DECODED_BYTES * TRANSIENT_DECODE_BUDGET_MULTIPLIER,
): Int {
    val longEdge = maxOf(width, height)
    if (longEdge <= 0 || targetDimension <= 0) return 1
    var sampleSize = 1
    while (longEdge / (sampleSize * 2) >= targetDimension) sampleSize *= 2
    while (sampleSize < longEdge && sampledDecodedBytes(width, height, sampleSize) > maxTransientBytes) {
        sampleSize *= 2
    }
    return sampleSize
}

/** Decoded ARGB cost of [width] x [height] sampled by [sampleSize]. */
private fun sampledDecodedBytes(
    width: Int,
    height: Int,
    sampleSize: Int,
): Long = (width / sampleSize).toLong() * (height / sampleSize) * DECODED_BYTES_PER_PIXEL

/** Each variant's sampling policy: an avatar honours a ceiling, a banner covers its target. */
internal fun profileDecodeSampleSize(
    width: Int,
    height: Int,
    variant: ProfileImageVariant,
    targetDimension: Int,
): Int =
    when (variant) {
        ProfileImageVariant.AVATAR -> avatarDecodeSampleSize(width, height, targetDimension)
        ProfileImageVariant.BANNER -> bannerDecodeSampleSize(width, height, targetDimension)
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
