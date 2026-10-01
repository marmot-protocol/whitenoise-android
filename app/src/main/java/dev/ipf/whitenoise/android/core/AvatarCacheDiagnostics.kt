package dev.ipf.whitenoise.android.core

import dev.ipf.whitenoise.android.BuildConfig
import java.util.concurrent.atomic.AtomicLongArray

/** Counts avatar presentation work during an explicit debug benchmark window, without keys or pixels. */
internal object AvatarCacheDiagnostics {
    private const val METRIC_COUNT = 6
    private val profile = AtomicLongArray(METRIC_COUNT)
    private val group = AtomicLongArray(METRIC_COUNT)

    @Volatile
    private var collecting = false

    /** Starts a bounded measurement; release builds never enable collection. */
    fun start() {
        if (!BuildConfig.DEBUG) return
        collecting = false
        for (index in 0 until METRIC_COUNT) {
            profile.set(index, 0)
            group.set(index, 0)
        }
        collecting = true
    }

    /** Stops collection before taking a stable, identifier-free snapshot. */
    fun stop(): AvatarCacheSnapshot {
        collecting = false
        return snapshot()
    }

    /** Reads counters between scroll passes without resetting the active measurement. */
    fun snapshot(): AvatarCacheSnapshot = AvatarCacheSnapshot(profile.snapshot(), group.snapshot())

    /** Records a lookup, whether it came from first-frame peeking or an explicit load. */
    fun lookup(
        kind: AvatarCacheKind,
        hit: Boolean,
    ) = count(kind, if (hit) AvatarCacheMetric.HIT else AvatarCacheMetric.MISS)

    /** Records one actual Android-to-MDK fetch-adapter invocation. It does not imply HTTP. */
    fun fetch(kind: AvatarCacheKind) = count(kind, AvatarCacheMetric.FETCH)

    /** Records one bitmap decode attempt. */
    fun decode(kind: AvatarCacheKind) = count(kind, AvatarCacheMetric.DECODE)

    /** Records one in-flight request joined by another caller. */
    fun deduplicated(kind: AvatarCacheKind) = count(kind, AvatarCacheMetric.DEDUPLICATED)

    /** Counts joined URL or stored-avatar work without treating banner requests as avatars. */
    fun deduplicatedProfile(variant: ProfileImageVariant) {
        if (variant == ProfileImageVariant.AVATAR) deduplicated(AvatarCacheKind.PROFILE)
    }

    /** Records only a capacity eviction, excluding replacement, clear, and account teardown. */
    fun evicted(kind: AvatarCacheKind) = count(kind, AvatarCacheMetric.EVICTED)

    /** Adds one counter only while an explicit debug measurement is active. */
    private fun count(
        kind: AvatarCacheKind,
        metric: AvatarCacheMetric,
    ) {
        if (!collecting || !BuildConfig.DEBUG) return
        (if (kind == AvatarCacheKind.PROFILE) profile else group).incrementAndGet(metric.ordinal)
    }

    /** Copies atomic values into an immutable per-cache result. */
    private fun AtomicLongArray.snapshot() =
        AvatarCacheCounts(
            hits = get(AvatarCacheMetric.HIT.ordinal),
            misses = get(AvatarCacheMetric.MISS.ordinal),
            evictions = get(AvatarCacheMetric.EVICTED.ordinal),
            fetchCalls = get(AvatarCacheMetric.FETCH.ordinal),
            decodeCalls = get(AvatarCacheMetric.DECODE.ordinal),
            deduplicated = get(AvatarCacheMetric.DEDUPLICATED.ordinal),
        )
}

/** The two independent 16 MiB chat-list avatar caches. */
internal enum class AvatarCacheKind { PROFILE, GROUP }

private enum class AvatarCacheMetric { HIT, MISS, EVICTED, FETCH, DECODE, DEDUPLICATED }

/** One cache's counters; values never include a URL, account, group, or image. */
internal data class AvatarCacheCounts(
    val hits: Long,
    val misses: Long,
    val evictions: Long,
    val fetchCalls: Long,
    val decodeCalls: Long,
    val deduplicated: Long,
)

/** A bounded measurement of the two independent decoded-bitmap caches. */
internal data class AvatarCacheSnapshot(
    val profile: AvatarCacheCounts,
    val group: AvatarCacheCounts,
)
