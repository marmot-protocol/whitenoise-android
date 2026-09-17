package dev.ipf.whitenoise.android.state

import androidx.compose.ui.graphics.ImageBitmap
import dev.ipf.marmotkit.AvatarAssetFfi
import dev.ipf.marmotkit.AvatarAvailabilityFfi
import dev.ipf.whitenoise.android.core.AvatarImageLoader

/**
 * Largest avatar payload accepted from one batched read. MarmotKit validates and stores the bytes, and
 * returns them only when a complete entry fits this budget; anything larger stays deferred and the URL
 * path renders instead.
 */
internal const val DURABLE_AVATAR_MAX_BYTES: ULong = 2_097_152uL

/**
 * Whether this asset can be drawn right now. `STALE` still has usable bytes, so it renders while the
 * account worker refreshes; `MISSING` and `INVALIDATED` have nothing to show.
 */
internal fun AvatarAssetFfi.isRenderable(): Boolean =
    reference != null &&
        (availability == AvatarAvailabilityFfi.READY || availability == AvatarAvailabilityFfi.STALE)

/**
 * The cache key a durable avatar occupies in [AvatarImageLoader]. The content revision is part of it so a
 * refreshed avatar never renders from the previous bytes, and it can never collide with a URL key.
 */
internal fun AvatarAssetFfi.cacheKey(): String? = reference?.let { "marmot-avatar:$it@$contentRevision" }

/**
 * Bytes MarmotKit already holds for [asset], decoded and cached, or null when it has none to give. Use it
 * ahead of the network path: the bytes are durable, so this works offline and needs no fetch.
 *
 * MarmotKit answers a batched read with an empty payload when the entry is missing or too large for the
 * budget, and marks it deferred; the caller then falls back to the avatar URL exactly as before.
 */
internal suspend fun WhiteNoiseAppState.durableAvatar(asset: AvatarAssetFfi?): ImageBitmap? {
    val renderable = asset?.takeIf { it.isRenderable() }
    val reference = renderable?.reference
    val key = renderable?.cacheKey()
    val account = activeAccountRef
    if (reference == null || key == null || account == null) return null
    return AvatarImageLoader.cachedImage(key) ?: readDurableAvatar(account, reference, key)
}

/** One batched read of stored bytes, decoded into the shared cache; null when the engine has none to give. */
private suspend fun WhiteNoiseAppState.readDurableAvatar(
    account: String,
    reference: String,
    key: String,
): ImageBitmap? {
    val payload =
        runCatchingCancellable {
            marmotIo { readAvatarAssets(account, listOf(reference), DURABLE_AVATAR_MAX_BYTES) }
        }.getOrNull()?.firstOrNull()
    val bytes = payload?.takeUnless { it.deferred || it.bytes.isEmpty() }?.bytes ?: return null
    return AvatarImageLoader.decodeAndCache(key, bytes)
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
