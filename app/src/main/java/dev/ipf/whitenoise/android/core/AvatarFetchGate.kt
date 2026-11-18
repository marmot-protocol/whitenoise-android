package dev.ipf.whitenoise.android.core

import kotlinx.coroutines.sync.Semaphore
import java.util.LinkedHashMap

internal sealed interface AvatarByteFetchResult {
    data class Success(
        val bytes: ByteArray,
    ) : AvatarByteFetchResult

    data object Failed : AvatarByteFetchResult

    data object Unavailable : AvatarByteFetchResult
}

internal enum class AvatarFetchLane {
    REGULAR,
    NOTIFICATION,
    STORED,
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

    // Local decoding has its own bounded capacity; offline socket waits cannot starve it.
    private val stored = Semaphore(2)

    suspend fun <T> withPermit(
        lane: AvatarFetchLane,
        block: suspend () -> T,
    ): T =
        when (lane) {
            AvatarFetchLane.REGULAR -> regular.withPermit(block)
            AvatarFetchLane.NOTIFICATION -> notification.withPermit(block)
            AvatarFetchLane.STORED -> stored.withPermit(block)
            AvatarFetchLane.PREWARM_ADMITTED -> block()
        }

    suspend fun <T> withPreWarmAdmission(block: suspend () -> T): T = regular.withPermit(block)

    private suspend fun <T> Semaphore.withPermit(block: suspend () -> T): T {
        acquire()
        return try {
            block()
        } finally {
            release()
        }
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
