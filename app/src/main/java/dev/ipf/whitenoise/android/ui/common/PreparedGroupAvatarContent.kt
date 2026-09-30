package dev.ipf.whitenoise.android.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import dev.ipf.marmotkit.AvatarAssetFfi
import dev.ipf.whitenoise.android.core.AvatarImageLoader
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.cacheKey
import dev.ipf.whitenoise.android.state.durableAvatar
import dev.ipf.whitenoise.android.state.isRenderable
import kotlinx.coroutines.withTimeoutOrNull

/** Short-lived decoded-pixel pins for at most one bounded visible working set, not a protocol cache. */
internal data class PreparedGroupAvatarPixels(
    val accountRef: String?,
    val lifetime: Long,
    val images: Map<String, ImageBitmap>,
)

internal val LocalPreparedGroupAvatarPixels = staticCompositionLocalOf<PreparedGroupAvatarPixels?> { null }

/**
 * Primes only already-validated local assets before a surface's first populated frame. True misses
 * never enter this barrier or start network work. Each decode uses the existing loader's two regular
 * permits and 512px bound; sixteen pins retain at most 16 MiB of decoded pixels for the visible UI.
 * A local failure/deferred payload remains a normal lazy miss and cannot strand navigation.
 */
@Composable
@Suppress("FunctionNaming")
internal fun PreparedGroupAvatarContent(
    appState: WhiteNoiseAppState,
    assets: List<AvatarAssetFfi>,
    accountRef: String? = appState.activeAccountRef,
    surfaceIdentity: Any? = null,
    content: @Composable () -> Unit,
) {
    val lifetime = AvatarImageLoader.currentCacheLifetime()
    val selected =
        assets
            .asSequence()
            .filter { it.isRenderable() }
            .map { it.copy() }
            .distinctBy { it.cacheKey(accountRef.orEmpty()) }
            .take(VISIBLE_GROUP_AVATAR_LIMIT)
            .toList()
    val preparation = rememberAvatarPixelPreparation(appState, selected, accountRef, lifetime)
    // Keep form/selection state mounted through subsequent projection changes. Each image binding
    // still resets synchronously on owner/reference/revision changes; only first publication waits.
    var hasPublished by remember(appState, appState.runtimeGeneration, accountRef, lifetime, surfaceIdentity) {
        mutableStateOf(false)
    }
    if (preparation.ready || hasPublished) {
        SideEffect { hasPublished = true }
        key(appState, appState.runtimeGeneration, accountRef, lifetime, surfaceIdentity) {
            CompositionLocalProvider(
                LocalPreparedGroupAvatarPixels provides PreparedGroupAvatarPixels(accountRef, lifetime, preparation.images),
                content = content,
            )
        }
    } else {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    }
}

/** The bounded local decode stage is canceled and discarded with its immutable owner/asset keys. */
@Composable
private fun rememberAvatarPixelPreparation(
    appState: WhiteNoiseAppState,
    selected: List<AvatarAssetFfi>,
    accountRef: String?,
    lifetime: Long,
): AvatarPixelPreparation {
    val identities = selected.map { it.cacheKey(accountRef.orEmpty()) }
    return key(appState, appState.runtimeGeneration, accountRef, lifetime, identities) {
        val cached =
            selected
                .mapNotNull { asset ->
                    val imageKey = accountRef?.let(asset::cacheKey) ?: return@mapNotNull null
                    AvatarImageLoader.cachedImage(imageKey)?.let { imageKey to it }
                }.toMap()
        produceState(
            initialValue = AvatarPixelPreparation(cached, accountRef == null || cached.size == selected.size),
        ) {
            if (value.ready) return@produceState
            val images = cached.toMutableMap()
            withTimeoutOrNull(LOCAL_GROUP_AVATAR_PREPARATION_BUDGET_MILLIS) {
                for (asset in selected) {
                    val imageKey = checkNotNull(asset.cacheKey(checkNotNull(accountRef)))
                    if (imageKey !in images) {
                        appState.durableAvatar(asset, accountRef, acquireMissing = false)?.let { images[imageKey] = it }
                    }
                }
            }
            value = AvatarPixelPreparation(images, ready = true)
        }.value
    }
}

private data class AvatarPixelPreparation(
    val images: Map<String, ImageBitmap>,
    val ready: Boolean,
)

private const val VISIBLE_GROUP_AVATAR_LIMIT = 16
private const val LOCAL_GROUP_AVATAR_PREPARATION_BUDGET_MILLIS = 1_000L
