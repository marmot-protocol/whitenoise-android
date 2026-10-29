package dev.ipf.whitenoise.android.core

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.whitenoise.android.media.ByteSizeLruCache
import dev.ipf.whitenoise.android.media.MediaPipeline
import dev.ipf.whitenoise.android.media.REMOTE_PROFILE_IMAGE_MAX_BYTES
import dev.ipf.whitenoise.android.state.StalenessGuard
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

internal const val GROUP_AVATAR_MAX_PAYLOAD_BYTES = REMOTE_PROFILE_IMAGE_MAX_BYTES

/** An [encryptedGroupAvatarCacheKey] is account, group and image hash. */
private const val ENCRYPTED_GROUP_AVATAR_KEY_PARTS = 3

internal fun isGroupAvatarPayloadAccepted(bytes: ByteArray): Boolean = bytes.size <= GROUP_AVATAR_MAX_PAYLOAD_BYTES

internal fun encryptedGroupAvatarCacheKey(
    accountRef: String,
    groupIdHex: String,
    imageHashHex: String,
): String = "$accountRef|${groupIdHex.lowercase()}|${imageHashHex.lowercase()}"

/**
 * A group's image belongs to its conversation: the worker domain named by an [encryptedGroupAvatarCacheKey]'s
 * account and group, or single-use for any other key.
 */
internal fun encryptedGroupAvatarDomain(key: String): dev.ipf.whitenoise.android.media.MediaTrustDomain {
    val parts = key.split('|')
    return if (parts.size == ENCRYPTED_GROUP_AVATAR_KEY_PARTS) {
        dev.ipf.whitenoise.android.media.MediaTrustDomain
            .conversation(parts[0], parts[1])
    } else {
        dev.ipf.whitenoise.android.media.MediaTrustDomain.Unique
    }
}

internal fun encryptedGroupAvatarCacheKey(
    accountRef: String?,
    group: AppGroupRecordFfi,
): String? {
    val hash =
        group.imageHashHex
            ?.takeIf { !group.pendingConfirmation && group.avatarUrl.isNullOrBlank() }
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    return if (accountRef != null && hash != null) {
        encryptedGroupAvatarCacheKey(accountRef, group.groupIdHex, hash)
    } else {
        null
    }
}

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

    // MDK currently applies its generic encrypted-media network cap before
    // returning these bytes. Serialize group-avatar fetches so a custom client
    // cannot multiply that temporary allocation on Android.
    private val loadPermits = Semaphore(permits = 1)

    private data class CachedAvatar(
        val image: ImageBitmap,
        val animation: ByteArray?,
    )

    private var trimmingMemory = false
    private val cache =
        ByteSizeLruCache<String, CachedAvatar>(
            maxBytes = CACHE_SIZE_BYTES.toLong(),
            sizeOf = { entry ->
                entry.image
                    .asAndroidBitmap()
                    .byteCount
                    .coerceAtLeast(1) + (entry.animation?.size ?: 0)
            },
            onEvicted = {
                AvatarCacheDiagnostics.evicted(AvatarCacheKind.GROUP)
                if (!trimmingMemory) AvatarCacheChanges.published()
            },
        )
    private val inFlight = mutableMapOf<String, CompletableDeferred<ImageBitmap?>>()
    private val cacheLifetime = StalenessGuard()
    private val requestLifetime = StalenessGuard()

    /** Returns already decoded pixels without starting a group-image fetch. */
    fun peek(key: String?): ImageBitmap? =
        key?.let {
            synchronized(lock) { cache.get(it)?.image }
                .also { image -> AvatarCacheDiagnostics.lookup(AvatarCacheKind.GROUP, image != null) }
        }

    /** Original GIF bytes retained by the first-frame load, within the same bitmap/source byte budget. */
    fun peekAnimatedSource(key: String?): ByteArray? = key?.let { synchronized(lock) { cache.get(it)?.animation } }

    /** Test-only injection for deterministic first-frame composition coverage. */
    internal fun putCached(
        key: String,
        image: ImageBitmap,
    ) {
        val cacheKey = key.trim()
        if (cacheKey.isEmpty()) return
        synchronized(lock) {
            cache.put(cacheKey, CachedAvatar(image, null))
            AvatarCacheChanges.published()
        }
    }

    /**
     * Loads one group avatar and publishes it only within the current cache lifetime. The decoding worker
     * serves only the group's conversation, named by the key.
     */
    suspend fun load(
        key: String,
        fetchBytes: suspend () -> ByteArray,
    ): ImageBitmap? {
        peek(key)?.let { return it }
        val domain = encryptedGroupAvatarDomain(key)
        val request =
            synchronized(lock) {
                cache.get(key)?.let {
                    AvatarCacheDiagnostics.lookup(AvatarCacheKind.GROUP, true)
                    return@synchronized CompletedGroupAvatarRequest(it.image)
                }
                AvatarCacheDiagnostics.lookup(AvatarCacheKind.GROUP, false)
                inFlight[key]?.let {
                    AvatarCacheDiagnostics.deduplicated(AvatarCacheKind.GROUP)
                    return@synchronized PendingGroupAvatarRequest(it)
                }

                val deferred = CompletableDeferred<ImageBitmap?>()
                inFlight[key] = deferred
                val launchedGeneration = cacheLifetime.capture()
                val launchedRequest = requestLifetime.capture()
                scope.launch {
                    var animation: ByteArray? = null
                    val image =
                        runCatching {
                            loadPermits.withPermit {
                                if (!isCurrentGeneration(launchedGeneration, launchedRequest)) return@withPermit null
                                AvatarCacheDiagnostics.fetch(AvatarCacheKind.GROUP)
                                val bytes = fetchBytes()
                                if (!isCurrentGeneration(launchedGeneration, launchedRequest)) return@withPermit null
                                if (!isGroupAvatarPayloadAccepted(bytes)) return@withPermit null
                                animation = profileAvatarAnimationSource(bytes)
                                AvatarCacheDiagnostics.decode(AvatarCacheKind.GROUP)
                                MediaPipeline
                                    .decodeSampledBitmap(bytes, MAX_EDGE_PX, domain = domain)
                                    ?.asImageBitmap()
                            }
                        }.getOrNull()
                    synchronized(lock) {
                        val current = isCurrentGeneration(launchedGeneration, launchedRequest)
                        if (current && image != null) {
                            cache.put(key, CachedAvatar(image, animation))
                            AvatarCacheChanges.published()
                        }
                        inFlight.remove(key, deferred)
                        deferred.complete(if (current) image else null)
                    }
                }
                PendingGroupAvatarRequest(deferred)
            }
        return request.await()
    }

    /** Rejects work retired by either an account clear or a connectivity recovery. */
    private fun isCurrentGeneration(
        candidate: Long,
        request: Long,
    ): Boolean =
        synchronized(lock) {
            cacheLifetime.isCurrent(candidate) && requestLifetime.isCurrent(request)
        }

    /** Releases old waiters without canceling a detached download or discarding useful pixels. */
    internal fun prepareForRecovery() {
        synchronized(lock) { retireRequestsLocked() }
    }

    /** Invalidates queued work before making a new request discoverable under the same image key. */
    private fun retireRequestsLocked() {
        requestLifetime.advance()
        inFlight.values.forEach { it.complete(null) }
        inFlight.clear()
    }

    /** Releases only cached pixels/source references; existing surfaces and in-flight reads keep their owners. */
    internal fun trimMemory(trim: dev.ipf.whitenoise.android.media.PresentationMemoryTrim) {
        synchronized(lock) {
            trimmingMemory = true
            try {
                cache.trimToSize(trim.targetBytes(CACHE_SIZE_BYTES.toLong()))
            } finally {
                trimmingMemory = false
            }
        }
    }

    /** Clears cached group avatars and invalidates every pending completion. */
    fun clear() {
        synchronized(lock) {
            cacheLifetime.advance()
            scope.coroutineContext.cancelChildren()
            cache.clear()
            AvatarCacheChanges.published()
            retireRequestsLocked()
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
