package dev.ipf.whitenoise.android.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import dev.ipf.marmotkit.AvatarAssetFfi
import dev.ipf.whitenoise.android.core.AvatarImageLoader
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.cacheKey
import dev.ipf.whitenoise.android.state.durableAvatar

/**
 * The bytes MarmotKit already stores for [asset], decoded once and held in the shared avatar cache. The
 * read is keyed by reference and content revision, so a refreshed avatar reloads and an unchanged one
 * never does; a cached image is returned on the first frame so re-entering a screen never blanks it.
 * Null while it loads, or when the engine has nothing to give and the URL path should draw instead.
 */
@Composable
internal fun rememberDurableAvatar(
    appState: WhiteNoiseAppState,
    asset: AvatarAssetFfi?,
): ImageBitmap? {
    val key = asset?.cacheKey()
    // The availability rides in the key so a `MISSING` asset with the same reference still re-runs the
    // acquisition request once the engine reports it.
    return produceState<ImageBitmap?>(
        initialValue = key?.let(AvatarImageLoader::cachedImage),
        key,
        asset?.availability,
    ) {
        value = appState.durableAvatar(asset)
    }.value
}
