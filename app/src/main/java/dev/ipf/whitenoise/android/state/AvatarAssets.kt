package dev.ipf.whitenoise.android.state

import androidx.compose.ui.graphics.ImageBitmap
import dev.ipf.marmotkit.AvatarAcquisitionStateFfi
import dev.ipf.marmotkit.AvatarAssetFfi
import dev.ipf.marmotkit.AvatarAvailabilityFfi
import dev.ipf.marmotkit.AvatarBytesFfi
import dev.ipf.whitenoise.android.core.AvatarImageLoader
import dev.ipf.whitenoise.android.core.STORED_AVATAR_KEY_PREFIX

/**
 * Byte budget for one batched read, MarmotKit's documented maximum. The engine already validated and
 * stored the bytes, and returns an entry only when it fits the budget; anything larger stays deferred
 * and renders a placeholder until MDK supplies a usable asset. No separate URL acquisition is started.
 */
internal const val DURABLE_AVATAR_MAX_BYTES: ULong = 16_777_216uL

/**
 * Whether MarmotKit should be asked to acquire this asset. `READY` needs nothing, and an acquisition the
 * engine already queued or is fetching would only be repeated; everything else names a visible target the
 * engine does not hold yet, or holds stale.
 */
internal fun AvatarAssetFfi.wantsAcquisition(): Boolean =
    availability != AvatarAvailabilityFfi.READY &&
        acquisition != AvatarAcquisitionStateFfi.QUEUED &&
        acquisition != AvatarAcquisitionStateFfi.FETCHING

/**
 * Whether this asset can be drawn right now. `STALE` still has usable bytes, so it renders while the
 * account worker refreshes; `MISSING` and `INVALIDATED` have nothing to show.
 */
internal fun AvatarAssetFfi.isRenderable(): Boolean =
    reference != null &&
        (availability == AvatarAvailabilityFfi.READY || availability == AvatarAvailabilityFfi.STALE)

/**
 * The cache key a durable avatar occupies in [AvatarImageLoader]. The account and content revision are
 * part of it, so another account's bytes and a refreshed avatar's previous bytes never render, and it can
 * never collide with a URL key.
 */
internal fun AvatarAssetFfi.cacheKey(accountRef: String): String? {
    val ref = reference ?: return null
    return "$STORED_AVATAR_KEY_PREFIX$accountRef:$ref@$contentRevision"
}

/**
 * Bytes MarmotKit already holds for [asset], decoded and cached, or null when it has none to give. Use it
 * ahead of the network path: the bytes are durable, so this works offline and needs no fetch.
 *
 * A visible asset the engine does not hold yet is also requested here, because MarmotKit acquires avatars
 * only for targets a screen names; the acquired bytes arrive as a later projection with a new content
 * revision, which re-keys the caller. Missing or deferred local bytes remain a lazy placeholder; the
 * caller never substitutes a second URL acquisition for an authoritative MDK asset.
 */
internal suspend fun WhiteNoiseAppState.durableAvatar(
    asset: AvatarAssetFfi?,
    accountRef: String? = activeAccountRef,
    acquireMissing: Boolean = true,
): ImageBitmap? {
    val ownedAsset = asset?.copy()
    val lifetime = AvatarImageLoader.currentCacheLifetime()
    if (acquireMissing && ownedAsset?.wantsAcquisition() == true) requestAvatars(listOf(ownedAsset.target), accountRef)
    val renderable = ownedAsset?.takeIf { it.isRenderable() }
    val reference = renderable?.reference
    val account = accountRef
    val key = account?.let { renderable?.cacheKey(it) }
    if (reference == null || key == null || account == null) return null
    return AvatarImageLoader.loadStored(key, lifetime) {
        readDurableAvatarBytes(account, checkNotNull(renderable))
    }
}

/** Accepts bytes only for the immutable reference/revision selected by this presentation. */
internal fun AvatarBytesFfi.matchesAvatarAsset(asset: AvatarAssetFfi): Boolean {
    val ownsSelection = reference == asset.reference && contentRevision == asset.contentRevision
    val readable = availability == AvatarAvailabilityFfi.READY || availability == AvatarAvailabilityFfi.STALE
    val boundedPayload = !deferred && bytes.isNotEmpty() && bytes.size.toULong() <= DURABLE_AVATAR_MAX_BYTES
    return ownsSelection && readable && boundedPayload
}

/** A coalesced off-main read from MDK's existing validated store; never substitutes an unrelated payload. */
private suspend fun WhiteNoiseAppState.readDurableAvatarBytes(
    account: String,
    asset: AvatarAssetFfi,
): ByteArray? {
    val payload =
        runCatchingCancellable {
            marmotIo { readAvatarAssets(account, listOf(checkNotNull(asset.reference)), DURABLE_AVATAR_MAX_BYTES) }
        }.getOrNull()?.singleOrNull()
    return payload?.takeIf { it.matchesAvatarAsset(asset) }?.bytes
}

/** Captures an export of the original retained image, without acquisition or cross-owner completion. */
internal fun WhiteNoiseAppState.retainedAvatarBytesReader(
    asset: AvatarAssetFfi?,
    accountRef: String?,
): (suspend () -> ByteArray?)? {
    val selected = asset?.takeIf { it.isRenderable() }?.copy()
    val account = accountRef
    val owner = if (selected != null && account != null) captureHostPerformanceRuntimeOwner() else null
    if (selected == null || account == null || owner == null) return null
    val lifetime = AvatarImageLoader.currentCacheLifetime()
    return {
        if (ownsHostPerformanceRuntimeOwner(owner) && AvatarImageLoader.currentCacheLifetime() == lifetime) {
            readDurableAvatarBytes(account, selected)?.takeIf {
                ownsHostPerformanceRuntimeOwner(owner) && AvatarImageLoader.currentCacheLifetime() == lifetime
            }
        } else {
            null
        }
    }
}

/**
 * Asks MarmotKit to acquire avatars for [targets] it does not hold yet. Acquisition is the engine's own
 * bounded, retrying work, so this only names the targets a screen is showing and never waits for bytes.
 */
internal suspend fun WhiteNoiseAppState.requestAvatars(
    targets: Collection<String>,
    accountRef: String? = activeAccountRef,
) {
    val wanted = targets.filter { it.isNotBlank() }.distinct().takeIf { it.isNotEmpty() } ?: return
    val account = accountRef ?: return
    runCatchingCancellable { marmotIo { requestAvatarAssets(account, wanted) } }
}

/** Drops the account's stored avatars; the engine re-acquires what the screens ask for next. */
internal suspend fun WhiteNoiseAppState.clearDurableAvatars() {
    val account = activeAccountRef ?: return
    runCatchingCancellable { marmotIo { clearAvatarCache(account) } }
}
