package dev.ipf.whitenoise.android.ui.common

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
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
    retainedPreparation: RetainedGroupAvatarPreparation? = null,
    content: @Composable () -> Unit,
) {
    val window by remember(rows, listState, rowKey, assetForRow) {
        derivedStateOf {
            val visibleKeys = listState.layoutInfo.visibleItemsInfo.map { it.key }.toSet()
            val visibleRows = visibleGroupAvatarWindow(rows, visibleKeys, rowKey)
            VisibleGroupAvatarAssets(
                assets = visibleRows.mapNotNull(assetForRow),
                hasVisibleRows = visibleRows.any { rowKey(it) in visibleKeys },
            )
        }
    }
    if (retainedPreparation == null) {
        PreparedGroupAvatarContent(appState, window.assets, accountRef, surfaceIdentity, content)
    } else {
        // A returning LazyColumn has no layout yet. Keep its last real viewport until layout restores it.
        if (window.hasVisibleRows) SideEffect { retainedPreparation.updateVisible(window.assets) }
        key(accountRef) {
            CompositionLocalProvider(LocalPreparedGroupAvatarPixels provides retainedPreparation.pixels, content = content)
        }
    }
}

/** The actual row window distinguishes a restored viewport from a not-yet-measured lazy list. */
private data class VisibleGroupAvatarAssets(
    val assets: List<AvatarAssetFfi>,
    val hasVisibleRows: Boolean,
)

/** Transfers the existing bounded preparation between a shell and its disposable chat-list slot. */
internal class RetainedGroupAvatarPreparation(
    val pixels: PreparedGroupAvatarPixels,
    val updateVisible: (List<AvatarAssetFfi>) -> Unit,
)

/**
 * Keeps one list's existing sixteen-image preparation alive during conversation navigation. Replacement
 * owners get an empty selection; callbacks from an outgoing account/runtime/cache lifetime own only the
 * retired state. The selected descriptors and pixels are presentation-only and never enter saved state.
 */
@Composable
internal fun rememberRetainedGroupAvatarPreparation(
    appState: WhiteNoiseAppState,
    accountRef: String?,
    surfaceIdentity: Any,
): RetainedGroupAvatarPreparation {
    val lifetime = AvatarImageLoader.currentCacheLifetime()
    val selected =
        remember(appState, accountRef, appState.runtimeGeneration, lifetime, surfaceIdentity) {
            mutableStateOf<List<AvatarAssetFfi>>(emptyList())
        }
    val updateVisible =
        remember(selected) {
            { assets: List<AvatarAssetFfi> -> selected.value = assets.take(VISIBLE_GROUP_AVATAR_LIMIT).map { it.copy() } }
        }
    return RetainedGroupAvatarPreparation(
        rememberPreparedGroupAvatarPixels(appState, selected.value, accountRef, surfaceIdentity),
        updateVisible,
    )
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
    val pixels = rememberPreparedGroupAvatarPixels(appState, assets, accountRef, surfaceIdentity)
    // Pixel owners may be recreated; the stable account key preserves restored editable screen state.
    key(accountRef) {
        CompositionLocalProvider(LocalPreparedGroupAvatarPixels provides pixels, content = content)
    }
}

/** Shares the same local-only decode stage between mounted surfaces and a retained navigation owner. */
@Composable
private fun rememberPreparedGroupAvatarPixels(
    appState: WhiteNoiseAppState,
    assets: List<AvatarAssetFfi>,
    accountRef: String?,
    surfaceIdentity: Any?,
): PreparedGroupAvatarPixels {
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
    return PreparedGroupAvatarPixels(accountRef, appState.runtimeGeneration, lifetime, preparation.images)
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
