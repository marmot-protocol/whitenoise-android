package dev.ipf.whitenoise.android.state

import androidx.compose.ui.graphics.ImageBitmap
import dev.ipf.marmotkit.AvatarAcquisitionStateFfi
import dev.ipf.marmotkit.AvatarAssetFfi
import dev.ipf.marmotkit.AvatarAvailabilityFfi
import dev.ipf.whitenoise.android.core.AvatarImageLoader

/**
 * Byte budget for one batched read, MarmotKit's documented maximum. The engine already validated and
 * stored the bytes, and returns an entry only when it fits the budget; anything larger stays deferred
 * and the URL path renders instead. White Noise on iOS reads with the same budget.
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
    return "marmot-avatar:$accountRef:$ref@$contentRevision"
}

/**
 * Bytes MarmotKit already holds for [asset], decoded and cached, or null when it has none to give. Use it
 * ahead of the network path: the bytes are durable, so this works offline and needs no fetch.
 *
 * A visible asset the engine does not hold yet is also requested here, because MarmotKit acquires avatars
 * only for targets a screen names; the acquired bytes arrive as a later projection with a new content
 * revision, which re-keys the caller. MarmotKit answers a batched read with an empty payload when the
 * entry is missing or too large for the budget, and marks it deferred; the caller then falls back to the
 * avatar URL exactly as before.
 */
internal suspend fun WhiteNoiseAppState.durableAvatar(asset: AvatarAssetFfi?): ImageBitmap? {
    if (asset?.wantsAcquisition() == true) requestAvatars(listOf(asset.target))
    val renderable = asset?.takeIf { it.isRenderable() }
    val reference = renderable?.reference
    val account = activeAccountRef
    val key = account?.let { renderable?.cacheKey(it) }
    if (reference == null || key == null || account == null) return null
    return AvatarImageLoader.cachedImage(key) ?: readDurableAvatar(account, reference, key)
}

/** One batched read of stored bytes, decoded into the shared cache; null when the engine has none to give. */
private suspend fun WhiteNoiseAppState.readDurableAvatar(
    account: String,
    reference: String,
    key: String,
): ImageBitmap? {
    val lifetime = AvatarImageLoader.currentCacheLifetime()
    val payload =
        runCatchingCancellable {
            marmotIo { readAvatarAssets(account, listOf(reference), DURABLE_AVATAR_MAX_BYTES) }
        }.getOrNull()?.firstOrNull()
    val bytes = payload?.takeUnless { it.deferred || it.bytes.isEmpty() }?.bytes ?: return null
    return AvatarImageLoader.decodeAndCache(key, bytes, lifetime)
}

/**
 * Asks MarmotKit to acquire avatars for [targets] it does not hold yet. Acquisition is the engine's own
 * bounded, retrying work, so this only names the targets a screen is showing and never waits for bytes.
 */
internal suspend fun WhiteNoiseAppState.requestAvatars(targets: Collection<String>) {
    val wanted = targets.filter { it.isNotBlank() }.distinct().takeIf { it.isNotEmpty() } ?: return
    val account = activeAccountRef ?: return
    runCatchingCancellable { marmotIo { requestAvatarAssets(account, wanted) } }
}

/** Drops the account's stored avatars; the engine re-acquires what the screens ask for next. */
internal suspend fun WhiteNoiseAppState.clearDurableAvatars() {
    val account = activeAccountRef ?: return
    runCatchingCancellable { marmotIo { clearAvatarCache(account) } }
}
