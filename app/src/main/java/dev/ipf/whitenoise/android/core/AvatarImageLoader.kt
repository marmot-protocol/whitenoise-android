package dev.ipf.whitenoise.android.core

import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.ipf.whitenoise.android.media.ByteSizeLruCache
import dev.ipf.whitenoise.android.media.MediaTrustDomain
import dev.ipf.whitenoise.android.state.StalenessGuard
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

@Suppress("TooManyFunctions") // Cohesive process-wide avatar cache, fetch, and lifecycle boundary.
object AvatarImageLoader {
    private const val MAX_AVATAR_BYTES = 2 * 1024 * 1024
    private const val MAX_AVATAR_DIMENSION = PROFILE_AVATAR_MAX_DIMENSION

    // A full-width 2:1 banner is stretched across the whole screen, so the
    // avatar cap would leave it upscaled from 512x256 (#2762). Its decode is
    // bounded by its own fetch ceiling here, and by the dimension and byte
    // budgets the shared helpers below apply.
    private const val MAX_BANNER_BYTES = 4 * 1024 * 1024

    // Byte-budgeted cache, one budget per decode variant. With ~1MB worst-case
    // decoded avatar and typical <400KB, 16MB holds dozens of avatars without
    // unbounded memory growth — the size this cache has always been. Banners get
    // their own 8MB rather than a share of that, because a single banner entry
    // can be worth eight avatars and a combined LRU would let a run of them
    // evict the avatar working set by plain recency (#2762).
    private const val AVATAR_CACHE_SIZE_BYTES = 16 * 1024 * 1024
    private const val BANNER_CACHE_SIZE_BYTES = 8 * 1024 * 1024
    private const val FAILURE_TTL_MS = 60_000L
    private const val FAILURE_CACHE_MAX_ENTRIES = 512
    private const val FETCH_CONCURRENCY = 4
    private const val NOTIFICATION_FETCH_CONCURRENCY = 2
    private const val REGULAR_FETCH_CONCURRENCY = FETCH_CONCURRENCY - NOTIFICATION_FETCH_CONCURRENCY
    private const val PREWARM_MAX_QUEUED = 64
    private const val ANIMATED_SOURCE_CACHE_SIZE_BYTES = 4L * 1024 * 1024

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val fetchGate = AvatarFetchGate(REGULAR_FETCH_CONCURRENCY, NOTIFICATION_FETCH_CONCURRENCY)
    private val lock = Any()
    private val publications = AvatarCachePublications()
    private val cache =
        PartitionedProfileImageCache(AVATAR_CACHE_SIZE_BYTES, BANNER_CACHE_SIZE_BYTES, publications::published)
    private val inFlight = mutableMapOf<String, AvatarInFlightRequest>()

    // staleness-exempt: captured request-lifetime tokens for bounded queued work, not a counter owner.
    private val preWarmQueuedGeneration = mutableMapOf<String, Long>()
    private val failureExpiresAt = AvatarFailureExpiryCache(FAILURE_CACHE_MAX_ENTRIES)

    // Encoded GIF bytes behind an already-cached first frame, so a visible surface can animate
    // without a second fetch. Bounded on its own, since the bitmap budget above stays where it was.
    private val animatedSources =
        ByteSizeLruCache<String, ByteArray>(
            maxBytes = ANIMATED_SOURCE_CACHE_SIZE_BYTES,
            sizeOf = { it.size },
            maxEntryBytes = MAX_AVATAR_BYTES.toLong(),
        )
    private var profileImageFetcher: (suspend (String, ULong) -> ByteArray)? = null

    /** The account whose public-profile domain decodes kind-0 pictures, attached by the app state. */
    @Volatile private var activeAccount: () -> String? = { null }

    // Bumped by clear(); fetches launched under an older generation discard
    // their results so a logout/account-switch can't be re-polluted by an
    // in-flight request that was already on the network.
    private val cacheLifetime = StalenessGuard()

    // Observable presentation token; the guard remains authoritative for async publication.
    private val observedCacheLifetime = mutableLongStateOf(cacheLifetime.capture())
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

    /** Attaches the active-account reader that names the public-profile decode domain. */
    internal fun attachActiveAccount(account: () -> String?) {
        activeAccount = account
    }

    /**
     * The active account's public-profile domain, which a caller names explicitly for a kind-0 picture or banner.
     * Kind-0 pictures are published, so one account's share a worker domain; nothing else may use it.
     */
    fun publicProfiles(): MediaTrustDomain = MediaTrustDomain.publicProfiles(activeAccount())

    /**
     * The domain an enclosing [dev.ipf.whitenoise.android.media.sandbox.MediaTrustScope] declared for this request,
     * otherwise a single-use worker: the fail-safe. An undeclared avatar-loader call inside a declared scope
     * therefore inherits that scope's domain. `PictureDomainCoverageTest` does not see scopes, so it requires every
     * production avatar-loader call to name its domain instead, so for this loader the inherited default is reachable
     * only from test and harness code. Other production paths do rely on scope inheritance by design:
     * `MediaPipeline`'s decode functions default to the enclosing scope, the photo editor declares its draft's domain
     * through its render context, and the notification presenter declares each picture slot's domain around its
     * resolver.
     */
    private fun declaredOrUnique(): MediaTrustDomain =
        dev.ipf.whitenoise.android.media.sandbox.MediaTrustScope
            .declared() ?: MediaTrustDomain.Unique

    /** Test-only lifecycle boundary for the process-global MDK fetch adapter. */
    internal fun resetProfileImageFetcherForTests() {
        synchronized(lock) {
            profileImageFetcher = null
        }
    }

    /** Test-only stand-in for the 60-second failure cooldown elapsing, without a recovery event. */
    internal fun expireFailureCooldownForTests() {
        synchronized(lock) { failureExpiresAt.clear() }
    }

    /**
     * Intercepts private display handles before the MDK public-profile image acquisition boundary. A decode
     * this call starts runs in a worker serving only [domain]: [publicProfiles] for a kind-0 picture, named by
     * the caller. Without a name or a declared scope it is single-use.
     */
    suspend fun load(
        url: String,
        domain: MediaTrustDomain = declaredOrUnique(),
    ): ImageBitmap? =
        if (PrivateContactAvatarLoader.isPrivate(url)) {
            PrivateContactAvatarLoader.load(url)
        } else {
            load(
                request = avatarRequest(url, domain),
                expectedGeneration = null,
                fetchLane = AvatarFetchLane.REGULAR,
            )
        }

    /**
     * Loads [url] for a full-width profile banner, decoded for a box [targetWidthPx] wide.
     *
     * The target is bucketed and bounded, and the bucket is part of the cache and in-flight keys, so
     * a banner request can never be answered by the avatar-sized bitmap of the same URL while two
     * banner surfaces at comparable widths still share one decode. The bytes come through the same
     * MarmotKit fetcher as every other profile image. A decode it starts serves only [domain].
     */
    suspend fun loadBanner(
        url: String,
        targetWidthPx: Int,
        domain: MediaTrustDomain = declaredOrUnique(),
    ): ImageBitmap? =
        load(
            request = bannerRequest(url, targetWidthPx, domain),
            expectedGeneration = null,
            fetchLane = AvatarFetchLane.REGULAR,
        )

    /** An explicit user retry retires this bitmap variant's failed result before the bounded fetch. */
    suspend fun retryBanner(
        url: String,
        targetWidthPx: Int,
        domain: MediaTrustDomain = declaredOrUnique(),
    ): ImageBitmap? {
        synchronized(lock) { failureExpiresAt.remove(bannerRequest(url, targetWidthPx, domain).cacheKey) }
        return loadBanner(url, targetWidthPx, domain)
    }

    /** Synchronous banner counterpart to [peek]; an avatar-sized entry never satisfies it. */
    fun peekBanner(
        url: String?,
        targetWidthPx: Int,
    ): ImageBitmap? {
        val key = url?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return synchronized(lock) { cache.get(bannerRequest(key, targetWidthPx, MediaTrustDomain.Unique).cacheKey) }
    }

    /** The avatar-variant request for [url], whose cache key stays the bare URL. */
    private fun avatarRequest(
        url: String,
        domain: MediaTrustDomain,
    ) = ProfileImageRequest(
        url = url,
        variant = ProfileImageVariant.AVATAR,
        maxDimension = MAX_AVATAR_DIMENSION,
        maxBytes = MAX_AVATAR_BYTES,
        domain = domain,
    )

    /** The banner-variant request for [url] at the bucket [targetWidthPx] rounds up into. */
    private fun bannerRequest(
        url: String,
        targetWidthPx: Int,
        domain: MediaTrustDomain,
    ) = ProfileImageRequest(
        url = url,
        variant = ProfileImageVariant.BANNER,
        maxDimension = profileBannerDecodeDimension(targetWidthPx),
        maxBytes = MAX_BANNER_BYTES,
        domain = domain,
    )

    /**
     * Queue an avatar fetch without delaying the caller. Chat/profile projection
     * uses this ahead of notification delivery so the first shortcut/person icon
     * can take the synchronous cache path. Work is URL-deduplicated, bounded to a
     * small network fan-out, and generation-guarded so account teardown cannot be
     * followed by an old queued warm repopulating the cache. The decode serves only [domain].
     */
    fun preWarm(
        url: String?,
        domain: MediaTrustDomain = declaredOrUnique(),
    ) {
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
                        request = avatarRequest(key, domain),
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

    /** Shares one bounded fetch, with request deduplication and generation-safe completion in one lifecycle. */
    @Suppress("LongMethod", "LongParameterList")
    private suspend fun load(
        request: ProfileImageRequest,
        expectedGeneration: Long?,
        fetchLane: AvatarFetchLane,
        waitForDetachedFetch: Boolean = false,
        expectedCacheLifetime: Long? = null,
        abandonable: Boolean = false,
        fetchImage: suspend (ProfileImageRequest) -> AvatarImageFetchResult = ::fetch,
    ): ImageBitmap? {
        val url = request.cacheKey
        val pending =
            synchronized(lock) {
                if (expectedCacheLifetime != null && !cacheLifetime.isCurrent(expectedCacheLifetime)) {
                    return@synchronized CompletedAvatarRequest(null)
                }
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
                        AvatarCacheDiagnostics.deduplicatedProfile(request.variant)
                        PendingAvatarRequest(it, abandonable)
                    }
                }
                val inFlightRequest = AvatarInFlightRequest(url, abandonable)
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
                                    if (skipsFetch(launchedGeneration, launchedRequest, fetchLane, inFlightRequest)) {
                                        AvatarImageFetchResult.Unavailable
                                    } else {
                                        fetchImage(request)
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
                                    publishAnimatedSourceLocked(url, fetchResult.animatedSource)
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
                PendingAvatarRequest(inFlightRequest, abandonable)
            }
        return pending.await(waitForDetachedFetch)
    }

    /**
     * Whether the detached fetch should not run: its lifetime was retired, or it is a stored avatar decode that only
     * abandonable callers asked for, such as chat-list rows, and that reached its slot after all of them left. Each
     * stored decode can start an isolated worker for its conversation, about half a second where workers are
     * exec-spawned, so a queue of abandoned rows would hold up the screen the user moved to. A notification or
     * private-contact read is not abandonable, so its decode still lands in the cache. The entry leaves [inFlight]
     * under the same lock its joiners take, so a later caller starts a fresh decode rather than joining the skipped
     * one. A skip records no failure, so the avatar loads normally when its row is shown again.
     */
    private fun skipsFetch(
        launchedGeneration: Long,
        launchedRequest: Long,
        lane: AvatarFetchLane,
        request: AvatarInFlightRequest,
    ): Boolean {
        if (!isCurrentRequest(launchedGeneration, launchedRequest)) return true
        return lane == AvatarFetchLane.STORED &&
            synchronized(lock) {
                val abandoned = request.abandonable && request.waiters.get() == 0
                if (abandoned) inFlight.remove(request.key, request)
                abandoned
            }
    }

    /**
     * Synchronously returns an already-cached avatar for [url], or null when
     * absent. Lets a composable seed its initial state from the in-memory
     * cache so re-entering a screen shows the cached image immediately instead
     * of flashing the placeholder while [load] re-resolves it. In-memory read
     * only — safe to call from composition. See issue #31.
     */
    @Suppress("ReturnCount") // Private handles use account validation before the ordinary public cache lookup.
    fun peek(url: String?): ImageBitmap? {
        val key = url ?: return null
        if (PrivateContactAvatarLoader.isPrivate(key)) return PrivateContactAvatarLoader.peek(key)
        return synchronized(lock) { cache.get(key) }
    }

    /**
     * Waits, without fetching, reading or decoding anything, for another consumer to publish [url]'s avatar
     * pixels in cache lifetime [lifetime], so a mounted placeholder whose own attempt failed can adopt them
     * (#3557). A banner entry never satisfies it, because banner keys are distinct. Null once the lifetime
     * ends, so a cleared cache or switched account cannot hand a retired consumer new pixels.
     */
    internal suspend fun awaitCached(
        url: String,
        lifetime: Long,
    ): ImageBitmap? =
        if (PrivateContactAvatarLoader.isPrivate(url)) {
            PrivateContactAvatarLoader.awaitCached(url, lifetime = lifetime)
        } else {
            awaitPublication(setOf(url), lifetime) { peek(url) }
        }

    /**
     * Registers for [keys] before reading through [read], so a publication between the read and the wait
     * cannot be missed, then re-reads after each publication of one of those keys. [read] decides what the
     * consumer would show, so a caller with ownership rules re-validates them on every wake.
     */
    internal suspend fun awaitPublication(
        keys: Set<String>,
        lifetime: Long,
        read: () -> ImageBitmap?,
    ): ImageBitmap? {
        while (true) {
            val registration = synchronized(lock) { publications.register(keys) }
            try {
                val image = read()
                when {
                    !synchronized(lock) { cacheLifetime.isCurrent(lifetime) } -> return null
                    image != null -> return image
                }
                registration.published.await()
            } finally {
                synchronized(lock) { publications.release(registration) }
            }
        }
    }

    /** Keys that currently have a mounted waiter, for cleanup assertions. */
    internal fun waitingPublicationKeyCount(): Int = synchronized(lock) { publications.waitingKeyCount }

    /** Whether [key] still has a fetch or decode in flight, so tests can wait for a skip without sleeping. */
    internal fun isInFlightForTest(key: String): Boolean = synchronized(lock) { inFlight.containsKey(key) }

    /**
     * The encoded GIF behind [url]'s cached first frame, or null for a static, uncached or evicted
     * image. Surfaces animate only from these bytes, so they never fetch or decode a URL themselves.
     */
    fun peekAnimatedSource(url: String?): ByteArray? {
        val key = url ?: return null
        return synchronized(lock) { animatedSources.get(key) }
    }

    /** Classification belongs to the same bounded first-frame entry, even after encoded bytes are evicted. */
    internal fun cachedAnimationClassification(key: String): Boolean? = synchronized(lock) { cache.isAnimated(key) }

    /** Test-only injection of a first frame together with its animated source. */
    internal fun putCachedAnimated(
        url: String,
        firstFrame: ImageBitmap,
        source: ByteArray,
    ) {
        putCached(url, firstFrame)
        synchronized(lock) { publishAnimatedSourceLocked(url.trim(), source) }
    }

    /** Keeps the animated source in step with the first frame just published for [url]. */
    private fun publishAnimatedSourceLocked(
        url: String,
        source: ByteArray?,
    ) {
        cache.classifyAnimation(url, source != null)
        if (source == null) animatedSources.remove(url) else animatedSources.put(url, source)
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
            animatedSources.remove(key)
        }
    }

    /**
     * Republishes stored-avatar pixels an account-switch handoff decoded before [clearStoredAvatars] ran.
     * Only `marmot-avatar:` keys are accepted, and those keys carry their owning account, so another
     * account's pixels can never be restored under this one. An entry already present is left alone.
     */
    internal fun restoreStoredAvatar(
        key: String,
        image: ImageBitmap,
        animatedSource: ByteArray?,
        animationClassification: Boolean? = null,
    ) {
        if (!isStoredAvatarKey(key)) return
        synchronized(lock) {
            if (cache.get(key) != null) return
            cache.put(key, image)
            failureExpiresAt.remove(key)
            publishAnimatedSourceLocked(key, animatedSource)
            // Missing encoded bytes may mean eviction, not a static picture. Preserve the handed-off classification.
            cache.classifyAnimation(key, if (animatedSource != null) true else animationClassification)
        }
    }

    /** Test-only injection of a banner-variant entry, for deterministic first-frame coverage. */
    internal fun putCachedBanner(
        url: String,
        targetWidthPx: Int,
        image: ImageBitmap,
    ) {
        val key = url.trim().takeIf { it.isNotEmpty() } ?: return
        val cacheKey = bannerRequest(key, targetWidthPx, MediaTrustDomain.Unique).cacheKey
        synchronized(lock) {
            cache.put(cacheKey, image)
            failureExpiresAt.remove(cacheKey)
        }
    }

    /** Android-bitmap view of [peek], for non-Compose consumers (notification icons). */
    fun peekBitmap(url: String?): android.graphics.Bitmap? = peek(url)?.asAndroidBitmap()

    /** Android-bitmap view of [load], using capacity reserved for notification icons, decoded in [domain]. */
    suspend fun loadBitmap(
        url: String,
        domain: MediaTrustDomain = declaredOrUnique(),
    ): android.graphics.Bitmap? =
        load(
            request = avatarRequest(url, domain),
            expectedGeneration = null,
            fetchLane = AvatarFetchLane.NOTIFICATION,
        )?.asAndroidBitmap()

    /** Releases LRU references without invalidating displayed pixels, deduplicated work or account fences. */
    internal fun trimMemory(trim: dev.ipf.whitenoise.android.media.PresentationMemoryTrim) {
        synchronized(lock) {
            cache.trimMemory(trim)
            animatedSources.trimToSize(trim.targetBytes(ANIMATED_SOURCE_CACHE_SIZE_BYTES))
        }
    }

    /** Wipes cached avatars and invalidates every queued or in-flight fetch. */
    fun clear() {
        dev.ipf.whitenoise.android.media.sandbox.IsolatedMediaDecoder
            .resetForAccount()
        synchronized(lock) {
            observedCacheLifetime.longValue = cacheLifetime.advance()
            cache.evictAll()
            animatedSources.clear()
            retireRequestsLocked()
            publications.retireAll()
        }
    }

    /**
     * Retires account-private stored pixels, animation bytes and reads while preserving already decoded
     * public URL images and their animated sources.
     */
    internal fun clearStoredAvatars() = clearStoredAvatarsWithPrefix(STORED_AVATAR_KEY_PREFIX)

    /**
     * [clearStoredAvatars] narrowed to the stored keys that begin with [keyPrefix], such as one contact's private
     * pictures after a Save or Clear, so every other stored avatar, such as the chat row picture a cleared choice
     * falls back to (#3635), stays decoded. Reads and the cache lifetime are still retired.
     */
    internal fun clearStoredAvatarsWithPrefix(keyPrefix: String) {
        val retired = { key: String -> isStoredAvatarKey(key) && key.startsWith(keyPrefix) }
        synchronized(lock) {
            observedCacheLifetime.longValue = cacheLifetime.advance()
            cache.evictStoredAvatars(retired)
            animatedSources.keysSnapshot().filter(retired).forEach(animatedSources::remove)
            retireRequestsLocked()
            publications.retireAll()
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

    /** Fetches bounded bytes; only list-avatar calls enter the avatar benchmark counters. */
    @Suppress("ReturnCount") // Fail-closed guards keep invalid limits and an unattached MDK adapter explicit.
    internal suspend fun fetchBytes(
        url: String,
        maxBytes: Int,
        measureAvatar: Boolean = false,
    ): AvatarByteFetchResult {
        if (maxBytes <= 0 || PrivateContactAvatarLoader.isPrivate(url)) return AvatarByteFetchResult.Failed
        val fetcher = synchronized(lock) { profileImageFetcher } ?: return AvatarByteFetchResult.Unavailable
        if (measureAvatar) AvatarCacheDiagnostics.fetch(AvatarCacheKind.PROFILE)
        val bytes = fetcher(url, maxBytes.toULong())
        return if (bytes.size <= maxBytes) AvatarByteFetchResult.Success(bytes) else AvatarByteFetchResult.Failed
    }

    /**
     * Fetches and decodes one URL-backed profile image within the requested variant's bounds. An avatar
     * whose bytes sniff as a bounded GIF also keeps those bytes as its animated source — the first frame
     * is still the static decode every other consumer (notifications, shortcuts) already uses.
     */
    private suspend fun fetch(request: ProfileImageRequest): AvatarImageFetchResult =
        when (val result = fetchBytes(request.url, request.maxBytes, request.variant == ProfileImageVariant.AVATAR)) {
            is AvatarByteFetchResult.Success ->
                decode(result.bytes, request.variant, request.maxDimension, request.domain)
                    ?.asImageBitmap()
                    ?.let { firstFrame ->
                        val animatedSource =
                            profileAvatarAnimationSource(result.bytes)
                                .takeIf { request.variant == ProfileImageVariant.AVATAR }
                        AvatarImageFetchResult.Success(firstFrame, animatedSource)
                    }
                    ?: AvatarImageFetchResult.Failed
            AvatarByteFetchResult.Failed -> AvatarImageFetchResult.Failed
            AvatarByteFetchResult.Unavailable -> AvatarImageFetchResult.Unavailable
        }

    /** The image already held for [key], whether it came from a URL fetch or MarmotKit's durable store. */
    internal fun cachedImage(key: String): ImageBitmap? = cached(key.trim())

    /** The cache lifetime a durable read must capture before it suspends; [clear] makes it stale. */
    internal fun currentCacheLifetime(): Long {
        // Subscribe Compose to invalidation, but discarded snapshots cannot rewind the async guard.
        observedCacheLifetime.longValue
        return synchronized(lock) { cacheLifetime.capture() }
    }

    /**
     * Shares local reads and off-main decodes through the existing bounded, generation-fenced loader. A
     * stored GIF keeps its bytes as an animated source under the same account-private key, so whether it
     * may animate is decided by the surface that knows it is a person's picture. A caller that cannot name the
     * asset's owner decodes it single-use.
     */
    internal suspend fun loadStored(
        key: String,
        lifetime: Long,
        readBytes: suspend () -> ByteArray?,
    ): ImageBitmap? = loadStored(key, lifetime, MediaTrustDomain.Unique, readBytes = readBytes)

    /**
     * [loadStored] whose decode runs in a worker serving only [domain], the asset owner's domain. An [abandonable]
     * caller, one serving a screen that is showing (a row, the cold-start prewarm, a prepared avatar window), lets the
     * decode be skipped if it is still queued once every caller has left. Any other caller, such as a notification,
     * keeps the decode running so its pixels still land.
     */
    internal suspend fun loadStored(
        key: String,
        lifetime: Long,
        domain: MediaTrustDomain,
        abandonable: Boolean = false,
        readBytes: suspend () -> ByteArray?,
    ): ImageBitmap? =
        load(
            request = avatarRequest(key, domain),
            expectedGeneration = null,
            expectedCacheLifetime = lifetime,
            fetchLane = AvatarFetchLane.STORED,
            abandonable = abandonable,
            fetchImage = { request ->
                AvatarCacheDiagnostics.fetch(AvatarCacheKind.PROFILE)
                val bytes = readBytes()
                bytes
                    ?.let { decode(it, request.variant, request.maxDimension, request.domain)?.asImageBitmap() }
                    ?.let { AvatarImageFetchResult.Success(it, profileAvatarAnimationSource(bytes)) }
                    ?: AvatarImageFetchResult.Unavailable
            },
        )

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
        domain: MediaTrustDomain = MediaTrustDomain.Unique,
    ): ImageBitmap? {
        val image =
            decode(bytes, ProfileImageVariant.AVATAR, MAX_AVATAR_DIMENSION, domain)?.asImageBitmap() ?: return null
        val published =
            synchronized(lock) {
                cacheLifetime.isCurrent(lifetime).also { current ->
                    if (current) {
                        putCached(key, image)
                        publishAnimatedSourceLocked(key, profileAvatarAnimationSource(bytes))
                    }
                }
            }
        return image.takeIf { published }
    }

    /** Samples and bounds decoded pixels in a worker serving only [domain]; a failed decode still counts. */
    private fun decode(
        bytes: ByteArray,
        variant: ProfileImageVariant,
        maxDimension: Int,
        domain: MediaTrustDomain,
    ): android.graphics.Bitmap? {
        if (variant == ProfileImageVariant.AVATAR) AvatarCacheDiagnostics.decode(AvatarCacheKind.PROFILE)
        return dev.ipf.whitenoise.android.media.sandbox.MediaTrustScope.within(domain) {
            // A 512px avatar always fits the byte budget. The isolated decode still inspects
            // and admits the source, so only larger targets need a separate sizing request.
            val target =
                if (maxDimension <= PROFILE_AVATAR_MAX_DIMENSION) {
                    maxDimension
                } else {
                    val info =
                        dev.ipf.whitenoise.android.media.sandbox.IsolatedMediaDecoder
                            .inspect(bytes) ?: return@within null
                    boundedDecodeDimension(info.width, info.height, maxDimension)
                }
            dev.ipf.whitenoise.android.media.MediaPipeline
                .decodeSampledBitmap(bytes, target)
        }
    }
}

private sealed interface AvatarImageFetchResult {
    class Success(
        val image: ImageBitmap,
        val animatedSource: ByteArray? = null,
    ) : AvatarImageFetchResult

    data object Failed : AvatarImageFetchResult

    data object Unavailable : AvatarImageFetchResult
}

private sealed interface AvatarRequest {
    suspend fun await(waitForDetachedFetch: Boolean): ImageBitmap?
}

private class CompletedAvatarRequest(
    private val image: ImageBitmap?,
) : AvatarRequest {
    override suspend fun await(waitForDetachedFetch: Boolean): ImageBitmap? = image
}

/**
 * One shared fetch or decode for [key]. It stays [abandonable] only while every caller that joined it allowed a skip,
 * and [waiters] counts the callers still waiting for its result.
 */
private class AvatarInFlightRequest(
    /** The [AvatarImageLoader] cache key this request is registered under while in flight. */
    val key: String,
    /** Whether every caller so far may leave it unfinished; any other caller clears this under the loader lock. */
    @Volatile var abandonable: Boolean,
) {
    val result = CompletableDeferred<ImageBitmap?>()
    val fetchCompleted = CompletableDeferred<Unit>()

    /** Callers still awaiting [result]; counted under the loader lock, released when each one returns or leaves. */
    val waiters = AtomicInteger()
}

/**
 * Created under the loader lock, so a stored decode's skip check always sees this caller counted, and a caller that
 * is not [abandonable] pins the decode before that check can run.
 */
private class PendingAvatarRequest(
    private val request: AvatarInFlightRequest,
    abandonable: Boolean,
) : AvatarRequest {
    init {
        request.waiters.incrementAndGet()
        if (!abandonable) request.abandonable = false
    }

    /** Waits for the shared result, counting this caller as waiting until it returns or is cancelled. */
    override suspend fun await(waitForDetachedFetch: Boolean): ImageBitmap? {
        try {
            val image = request.result.await()
            if (waitForDetachedFetch) request.fetchCompleted.await()
            return image
        } finally {
            request.waiters.decrementAndGet()
        }
    }
}
