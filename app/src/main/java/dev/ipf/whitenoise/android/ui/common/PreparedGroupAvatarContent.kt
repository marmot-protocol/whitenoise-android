package dev.ipf.whitenoise.android.ui.common

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
            val visibleKeys =
                listState.layoutInfo.visibleItemsInfo
                    .map { it.key }
                    .toSet()
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
            CompositionLocalProvider(
                LocalPreparedGroupAvatarPixels provides retainedPreparation.pixels,
                content = content,
            )
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
    private val readPixels: () -> PreparedGroupAvatarPixels,
    val updateVisible: (List<AvatarAssetFfi>) -> Unit,
) {
    /** Pixel changes belong to the consuming content's restart scope, never the shell owner factory. */
    val pixels: PreparedGroupAvatarPixels get() = readPixels()
}

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
    val runtimeGeneration = appState.runtimeGeneration
    val window =
        remember(appState, accountRef, runtimeGeneration, lifetime, surfaceIdentity) {
            RetainedAvatarWindow(accountRef)
        }
    PrepareRetainedAvatarWindow(appState, accountRef, window)
    return remember(window) {
        RetainedGroupAvatarPreparation(
            readPixels = {
                PreparedGroupAvatarPixels(
                    accountRef,
                    runtimeGeneration,
                    lifetime,
                    images = window.exactImages(),
                    retained = window.retained,
                    recordLoaded = window::recordLoaded,
                )
            },
            updateVisible = window::select,
        )
    }
}

/** Window changes restart only local preparation; the shell does not observe selection or decode state. */
@Composable
@Suppress("FunctionNaming")
private fun PrepareRetainedAvatarWindow(
    appState: WhiteNoiseAppState,
    accountRef: String?,
    window: RetainedAvatarWindow,
) {
    val selected = window.assets.filter { it.isRenderable() }
    LaunchedEffect(window, selected) {
        if (accountRef == null) return@LaunchedEffect
        withTimeoutOrNull(LOCAL_GROUP_AVATAR_PREPARATION_BUDGET_MILLIS) {
            for (asset in selected) {
                if (!window.hasExactRevision(asset)) {
                    appState.durableAvatar(asset, accountRef, acquireMissing = false)?.let {
                        window.recordLoaded(asset, it)
                    }
                }
            }
        }
    }
}

/** One owner retains only its latest measured window, including row loads that outlive preparation. */
private class RetainedAvatarWindow(
    private val accountRef: String?,
) {
    var assets by mutableStateOf<List<AvatarAssetFfi>>(emptyList())
        private set
    var retained by mutableStateOf<List<PreparedAvatarImage>>(emptyList())
        private set

    /** Drops departed or invalidated selections, keeping valid prior revisions until their replacement loads. */
    fun select(visible: List<AvatarAssetFfi>) {
        assets = visible.take(VISIBLE_GROUP_AVATAR_LIMIT).map { it.copy() }
        retained = retained.filter { known -> assets.any { it.isRenderable() && sameSelection(it, known.asset) } }
    }

    /** Existing exact pins need neither a new preparation read nor a second retained bitmap map. */
    fun hasExactRevision(selectedAsset: AvatarAssetFfi): Boolean =
        retained.any { known ->
            sameSelection(known.asset, selectedAsset) && known.asset.contentRevision == selectedAsset.contentRevision
        }

    /** Accepts exact loaded pixels only for a still-selected revision, with at most one image per selection. */
    fun recordLoaded(
        asset: AvatarAssetFfi,
        image: ImageBitmap,
    ) {
        val current = assets.firstOrNull { it.isRenderable() && sameSelection(it, asset) }
        if (current == null || accountRef == null || current.cacheKey(accountRef) != asset.cacheKey(accountRef)) return
        val previous = retained.firstOrNull { sameSelection(it.asset, asset) }
        if (previous?.asset == asset && previous.image === image) return
        val classification =
            AvatarImageLoader.cachedAnimationClassification(checkNotNull(asset.cacheKey(accountRef)))
                ?: previous?.takeIf { it.asset.contentRevision == asset.contentRevision }?.animationClassification
        retained = retained.filterNot { sameSelection(it.asset, asset) } +
            PreparedAvatarImage(asset.copy(), image, classification)
    }

    /** Seeds only the revision these pixels actually depict; revision fallback remains a separate lookup. */
    fun exactImages(): Map<String, ImageBitmap> =
        retained
            .mapNotNull { known -> accountRef?.let(known.asset::cacheKey)?.let { it to known.image } }
            .toMap()

    /** Target and selected reference jointly identify a drawable picture independently of content refresh. */
    private fun sameSelection(
        first: AvatarAssetFfi,
        second: AvatarAssetFfi,
    ): Boolean = first.target == second.target && first.reference == second.reference
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

/** Builds the local-only preparation for surfaces whose lifetime already matches their visible content. */
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
    return PreparedGroupAvatarPixels(
        accountRef,
        appState.runtimeGeneration,
        lifetime,
        preparation.images,
        preparation.frames.values.toList(),
    )
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
                    AvatarImageLoader.cachedImage(imageKey)?.let {
                        imageKey to
                            PreparedAvatarImage(
                                asset.copy(),
                                it,
                                AvatarImageLoader.cachedAnimationClassification(imageKey),
                            )
                    }
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
                        appState.durableAvatar(asset, accountRef, acquireMissing = false)?.let {
                            images[imageKey] =
                                PreparedAvatarImage(
                                    asset.copy(),
                                    it,
                                    AvatarImageLoader.cachedAnimationClassification(imageKey),
                                )
                        }
                    }
                }
            }
            value = AvatarPixelPreparation(images, ready = true)
        }.value
    }
}

private data class AvatarPixelPreparation(
    val frames: Map<String, PreparedAvatarImage>,
    val ready: Boolean,
) {
    val images: Map<String, ImageBitmap> get() = frames.mapValues { it.value.image }
}

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
