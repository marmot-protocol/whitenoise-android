package dev.ipf.whitenoise.android.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import dev.ipf.marmotkit.AvatarAssetFfi
import dev.ipf.whitenoise.android.core.AvatarImageLoader
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.cacheKey
import dev.ipf.whitenoise.android.state.durableAvatar
import dev.ipf.whitenoise.android.state.isRenderable
import dev.ipf.whitenoise.android.state.wantsAcquisition

/**
 * The bytes MarmotKit already stores for [asset], decoded once and held in the shared avatar cache. The
 * read is keyed by reference and content revision, so a refreshed avatar reloads and an unchanged one
 * never does; a cached image is returned on the first frame so re-entering a screen never blanks it.
 * Null while it loads, or when MDK has no drawable bytes; authoritative misses stay placeholders.
 */
@Composable
internal fun rememberDurableAvatar(
    appState: WhiteNoiseAppState,
    asset: AvatarAssetFfi?,
    accountRef: String? = appState.activeAccountRef,
): ImageBitmap? {
    val ownedAsset = asset?.copy()
    val lifetime = AvatarImageLoader.currentCacheLifetime()
    val cacheKey = accountRef?.let { ownedAsset?.takeIf { it.isRenderable() }?.cacheKey(it) }
    val pins =
        LocalPreparedGroupAvatarPixels.current?.takeIf {
            it.accountRef == accountRef && it.lifetime == AvatarImageLoader.currentCacheLifetime()
        }
    // The availability rides in the key so a `MISSING` asset with the same reference still re-runs the
    // acquisition request once the engine reports it.
    return key(appState, appState.runtimeGeneration, accountRef, lifetime, cacheKey, ownedAsset?.availability) {
        produceState<ImageBitmap?>(
            initialValue = cacheKey?.let { pins?.images?.get(it) ?: AvatarImageLoader.cachedImage(it) },
            ownedAsset?.target,
            ownedAsset?.acquisition,
        ) {
            if (value == null || ownedAsset?.wantsAcquisition() == true) {
                appState.durableAvatar(ownedAsset, accountRef)?.let { value = it }
            }
        }.value
    }
}
