package dev.ipf.whitenoise.android.ui.common

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import dev.ipf.marmotkit.AvatarAssetFfi
import dev.ipf.whitenoise.android.core.AvatarImageLoader
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.cacheKey
import dev.ipf.whitenoise.android.state.durableAvatar
import dev.ipf.whitenoise.android.state.isRenderable
import kotlinx.coroutines.withTimeoutOrNull

internal val LocalPreparedGroupAvatarPixels = compositionLocalOf<PreparedGroupAvatarPixels?> { null }

/** Scroll offsets are observed in this scope; unchanged row windows do not invalidate list content. */
@Composable
@Suppress("FunctionNaming")
internal fun <T> PreparedVisibleGroupAvatarContent(
    appState: WhiteNoiseAppState,
    rows: List<T>,
    listState: LazyListState,
    rowKey: (T) -> String,
    assetForRow: (T) -> AvatarAssetFfi?,
    accountRef: String? = appState.activeAccountRef,
    surfaceIdentity: Any? = null,
    content: @Composable () -> Unit,
) {
    val assets by remember(rows, listState, rowKey, assetForRow) {
        derivedStateOf {
            visibleGroupAvatarWindow(rows, listState.layoutInfo.visibleItemsInfo.map { it.key }, rowKey)
                .mapNotNull(assetForRow)
        }
    }
    PreparedGroupAvatarContent(appState, assets, accountRef, surfaceIdentity, content)
}

/**
 * Primes validated local assets without withholding navigation or editable screen state. Local
 * decoding uses two dedicated permits and a 512px bound. Sixteen visible pins retain at most 16 MiB;
 * cached pictures are available synchronously and cold local reads fill their avatar slots in place.
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
            .map { it.copy() }
            .filter { asset -> asset.isRenderable() && accountRef?.let(asset::cacheKey) != null }
            .distinctBy { it.cacheKey(accountRef.orEmpty()) }
            .take(VISIBLE_GROUP_AVATAR_LIMIT)
            .toList()
    val preparation = rememberAvatarPixelPreparation(appState, selected, accountRef, lifetime, surfaceIdentity)
    // Pixel owners may be recreated; the stable account key preserves restored editable screen state.
    key(accountRef) {
        CompositionLocalProvider(
            LocalPreparedGroupAvatarPixels provides
                PreparedGroupAvatarPixels(accountRef, appState.runtimeGeneration, lifetime, preparation.images),
            content = content,
        )
    }
}

/** The bounded local decode stage is canceled and discarded with its immutable owner/asset keys. */
@Composable
private fun rememberAvatarPixelPreparation(
    appState: WhiteNoiseAppState,
    selected: List<AvatarAssetFfi>,
    accountRef: String?,
    lifetime: Long,
    surfaceIdentity: Any?,
): AvatarPixelPreparation {
    val identities = selected.map { it.cacheKey(accountRef.orEmpty()) }
    return key(appState, appState.runtimeGeneration, accountRef, lifetime, surfaceIdentity, identities) {
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

internal const val VISIBLE_GROUP_AVATAR_LIMIT = 16

/** Lazy headers and banners do not count as rows when choosing the bounded avatar window. */
internal fun <T> visibleGroupAvatarWindow(
    rows: List<T>,
    visibleKeys: Collection<Any>,
    rowKey: (T) -> String,
): List<T> {
    val keys = visibleKeys.toHashSet()
    val firstRow = rows.indexOfFirst { rowKey(it) in keys }.coerceAtLeast(0)
    return rows.subList(firstRow, minOf(rows.size, firstRow + VISIBLE_GROUP_AVATAR_LIMIT))
}

private const val LOCAL_GROUP_AVATAR_PREPARATION_BUDGET_MILLIS = 1_000L
