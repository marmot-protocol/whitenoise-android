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

internal fun isGroupAvatarPayloadAccepted(bytes: ByteArray): Boolean = bytes.size <= GROUP_AVATAR_MAX_PAYLOAD_BYTES

internal fun encryptedGroupAvatarCacheKey(
    accountRef: String,
    groupIdHex: String,
    imageHashHex: String,
): String = "$accountRef|${groupIdHex.lowercase()}|${imageHashHex.lowercase()}"

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
    private val cache =
        ByteSizeLruCache<String, ImageBitmap>(
            maxBytes = CACHE_SIZE_BYTES.toLong(),
            sizeOf = { image -> image.asAndroidBitmap().byteCount.coerceAtLeast(1) },
        )
    private val inFlight = mutableMapOf<String, CompletableDeferred<ImageBitmap?>>()
    private val cacheLifetime = StalenessGuard()
    private val requestLifetime = StalenessGuard()

    fun peek(key: String?): ImageBitmap? =
        key?.let {
            synchronized(lock) { cache.get(it) }
        }

    /** Test-only injection for deterministic first-frame composition coverage. */
    internal fun putCached(
        key: String,
        image: ImageBitmap,
    ) {
        val cacheKey = key.trim()
        if (cacheKey.isEmpty()) return
        synchronized(lock) {
            cache.put(cacheKey, image)
        }
    }

    /** Loads one group avatar and publishes it only within the current cache lifetime. */
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
                val launchedGeneration = cacheLifetime.capture()
                val launchedRequest = requestLifetime.capture()
                scope.launch {
                    val image =
                        runCatching {
                            loadPermits.withPermit {
                                if (!isCurrentGeneration(launchedGeneration, launchedRequest)) return@withPermit null
                                val bytes = fetchBytes()
                                if (!isCurrentGeneration(launchedGeneration, launchedRequest)) return@withPermit null
                                if (!isGroupAvatarPayloadAccepted(bytes)) return@withPermit null
                                MediaPipeline
                                    .decodeSampledBitmap(bytes, MAX_EDGE_PX)
                                    ?.asImageBitmap()
                            }
                        }.getOrNull()
                    synchronized(lock) {
                        val current = isCurrentGeneration(launchedGeneration, launchedRequest)
                        if (current && image != null) {
                            cache.put(key, image)
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

    /** Clears cached group avatars and invalidates every pending completion. */
    fun clear() {
        synchronized(lock) {
            cacheLifetime.advance()
            scope.coroutineContext.cancelChildren()
            cache.clear()
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
