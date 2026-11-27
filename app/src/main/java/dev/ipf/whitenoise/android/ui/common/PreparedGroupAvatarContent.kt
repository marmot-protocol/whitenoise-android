package dev.ipf.whitenoise.android.ui.common

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap
import dev.ipf.marmotkit.AvatarAssetFfi
import dev.ipf.whitenoise.android.core.AvatarImageLoader
import dev.ipf.whitenoise.android.media.MediaTrustDomain
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.cacheKey
import dev.ipf.whitenoise.android.state.durableAvatar
import dev.ipf.whitenoise.android.state.isRenderable
import kotlinx.coroutines.withTimeoutOrNull

internal val LocalPreparedGroupAvatarPixels = compositionLocalOf<PreparedGroupAvatarPixels?> { null }

/**
 * The chat list's retained window, provided to the destinations opened from it. A destination's own preparation
 * draws a selected picture from it on its first frame when the shared decoded cache has evicted that picture, as
 * long as the account, runtime, cache lifetime and exact revision still match.
 */
internal val LocalRetainedGroupAvatarPixels = staticCompositionLocalOf<RetainedGroupAvatarPreparation?> { null }

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
    compatibilityForRow: ((T) -> CompatibilityAvatarSelection)? = null,
    domainForRow: ((T) -> MediaTrustDomain)? = null,
    content: @Composable () -> Unit,
) {
    val indexes = remember(rows, rowKey) { rows.withIndex().associate { (index, row) -> rowKey(row) to index } }
    val viewport by remember(rows, indexes, listState) {
        derivedStateOf {
            val visible =
                listState.layoutInfo.visibleItemsInfo
                    .mapNotNull { (it.key as? String)?.let(indexes::get) }
                    .sorted()
            avatarViewport(rows.size, visible)
        }
    }
    if (retainedPreparation == null) {
        PreparedGroupAvatarContent(
            appState,
            viewport.nearby.mapNotNull { assetForRow(rows[it]) },
            accountRef,
            surfaceIdentity,
            content,
        )
    } else {
        // A returning LazyColumn has no layout yet. Keep its last real viewport until layout restores it.
        if (viewport.visible.isNotEmpty()) {
            SideEffect {
                retainedPreparation.updateVisible(
                    viewport = viewport,
                    keyAt = { rowKey(rows[it]) },
                    indexFor = indexes::get,
                    assetAt = { assetForRow(rows[it]) },
                    compatibilityAt = { compatibilityForRow?.invoke(rows[it]) },
                    domainAt = { domainForRow?.invoke(rows[it]) },
                )
            }
        }
        key(accountRef) {
            CompositionLocalProvider(
                LocalPreparedGroupAvatarPixels provides retainedPreparation.pixels,
                content = content,
            )
        }
    }
}

internal data class AvatarViewport(
    val visible: List<Int>,
    val nearby: List<Int>,
)

/** Prepare visible rows first, then the closest neighbors on either side. Lazy headers are absent from [visible]. */
internal fun avatarViewport(
    rowCount: Int,
    visible: List<Int>,
): AvatarViewport {
    if (visible.isEmpty()) {
        return AvatarViewport(visible, (0 until minOf(rowCount, VISIBLE_GROUP_AVATAR_LIMIT)).toList())
    }
    var before = visible.first() - 1
    var after = visible.last() + 1
    val nearby =
        buildList {
            addAll(visible.take(VISIBLE_GROUP_AVATAR_LIMIT))
            while (size < VISIBLE_GROUP_AVATAR_LIMIT && (before >= 0 || after < rowCount)) {
                if (after < rowCount) add(after++)
                if (size < VISIBLE_GROUP_AVATAR_LIMIT && before >= 0) add(before--)
            }
        }
    return AvatarViewport(visible, nearby)
}

/** Transfers the list's pixel preparation between a shell and its disposable chat-list slot. */
internal class RetainedGroupAvatarPreparation(
    private val accountRef: String?,
    private val runtimeGeneration: Int,
    private val lifetime: Long,
) {
    var assets by mutableStateOf<List<Pair<AvatarAssetFfi, MediaTrustDomain?>>>(emptyList())
        private set
    private var retained by mutableStateOf<Map<Pair<String, String?>, MutableState<PreparedAvatarImage?>>>(emptyMap())
    private var recentRows = emptyList<String>()
    private var allowedAssets = emptyList<AvatarAssetFfi>()
    private val compatibility = RetainedGroupCompatibilityPixels()

    /** Keep the provider stable. Each row observes only the state of its own picture on load. */
    val pixels =
        PreparedGroupAvatarPixels(
            accountRef,
            runtimeGeneration,
            lifetime,
            images = emptyMap(),
            recordLoaded = ::recordLoaded,
            compatibility = compatibility,
            lookup = { retained[it.selectionKey()]?.value },
        )

    /** Protect visible and nearby rows first, then the most recently visible rows still in this list. */
    fun updateVisible(
        viewport: AvatarViewport,
        keyAt: (Int) -> String,
        indexFor: (String) -> Int?,
        assetAt: (Int) -> AvatarAssetFfi?,
        compatibilityAt: (Int) -> CompatibilityAvatarSelection? = { null },
        domainAt: (Int) -> MediaTrustDomain? = { null },
    ) {
        val visibleKeys = viewport.visible.map(keyAt).toSet()
        recentRows =
            (recentRows.filter { it !in visibleKeys && indexFor(it) != null } + visibleKeys)
                .takeLast(RECENT_AVATAR_ROW_LIMIT)
        val nearby = viewport.nearby
        val allowedRows = (nearby + recentRows.mapNotNull(indexFor)).distinct()
        allowedAssets = allowedRows.mapNotNull(assetAt).filter { it.isRenderable() }
        retained =
            allowedAssets.associate { asset ->
                val key = asset.selectionKey()
                key to (retained[key] ?: mutableStateOf(null))
            }
        compatibility.select(allowedRows.filter { assetAt(it) == null }.mapNotNull(compatibilityAt))
        assets =
            nearby
                .mapNotNull { index ->
                    assetAt(index)?.takeIf { it.isRenderable() }?.let { it.copy() to domainAt(index) }
                }.distinctBy { (asset, _) -> Triple(asset.target, asset.reference, asset.contentRevision) }
    }

    /** Accept exact loaded pixels only for a still-selected revision, with at most one image per selection. */
    fun recordLoaded(
        asset: AvatarAssetFfi,
        image: ImageBitmap,
    ) {
        val key =
            accountRef?.let { account ->
                asset.cacheKey(account)?.takeIf { candidate ->
                    allowedAssets.any { sameSelection(it, asset) && it.cacheKey(account) == candidate }
                }
            } ?: return
        val slot = retained[asset.selectionKey()] ?: return
        val previous = slot.value
        if (previous?.asset != asset || previous.image !== image) {
            val classification =
                AvatarImageLoader.cachedAnimationClassification(key)
                    ?: previous?.takeIf { it.asset.contentRevision == asset.contentRevision }?.animationClassification
            slot.value = PreparedAvatarImage(asset.copy(), image, classification)
        }
    }

    fun hasExactRevision(asset: AvatarAssetFfi): Boolean =
        retained[asset.selectionKey()]?.value?.asset?.contentRevision == asset.contentRevision

    /** Target and reference identify a drawable picture independently of a content refresh. */
    private fun AvatarAssetFfi.selectionKey(): Pair<String, String?> = target to reference

    private fun sameSelection(
        first: AvatarAssetFfi,
        second: AvatarAssetFfi,
    ): Boolean = first.selectionKey() == second.selectionKey()
}

/** Keep only presentation pixels for this account, runtime and cache lifetime, including during navigation. */
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
            RetainedGroupAvatarPreparation(accountRef, runtimeGeneration, lifetime)
        }
    PrepareRetainedAvatarWindow(appState, accountRef, window)
    return window
}

/** Overlapping selections keep preparing even when the viewport moves by a row. */
@Composable
@Suppress("FunctionNaming")
private fun PrepareRetainedAvatarWindow(
    appState: WhiteNoiseAppState,
    accountRef: String?,
    window: RetainedGroupAvatarPreparation,
) {
    if (accountRef == null) return
    for ((asset, domain) in window.assets) {
        key(asset.target, asset.reference, asset.contentRevision) {
            LaunchedEffect(window, asset.availability, domain) {
                if (window.hasExactRevision(asset)) return@LaunchedEffect
                withTimeoutOrNull(LOCAL_GROUP_AVATAR_PREPARATION_BUDGET_MILLIS) {
                    appState.durableAvatar(asset, accountRef, acquireMissing = false, domain, abandonable = true)?.let {
                        window.recordLoaded(asset, it)
                    }
                }
            }
        }
    }
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
    val source = LocalRetainedGroupAvatarPixels.current
    return key(appState, appState.runtimeGeneration, accountRef, lifetime, surfaceIdentity, identities) {
        val handoff = retainedHandoff(source, accountRef, appState.runtimeGeneration, lifetime)
        val cached =
            selected
                .mapNotNull { asset ->
                    val imageKey = accountRef?.let(asset::cacheKey) ?: return@mapNotNull null
                    val retained = Snapshot.withoutReadObservation { handoff?.imageFor(asset, imageKey) }
                    (AvatarImageLoader.cachedImage(imageKey) ?: retained)?.let {
                        imageKey to
                            PreparedAvatarImage(
                                asset.copy(),
                                it,
                                AvatarImageLoader.cachedAnimationClassification(imageKey)
                                    ?: Snapshot.withoutReadObservation { handoff?.animationClassificationFor(asset) },
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
                        appState.durableAvatar(asset, accountRef, acquireMissing = false, abandonable = true)?.let {
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

/**
 * The chat list's retained pixels a destination may start from: only the list's own account, runtime and cache
 * lifetime qualify, and its exact-revision images are keyed like the destination's. Read without observing the
 * window, so later list scrolling does not recompose the destination.
 */
private fun retainedHandoff(
    source: RetainedGroupAvatarPreparation?,
    accountRef: String?,
    runtimeGeneration: Int,
    lifetime: Long,
): PreparedGroupAvatarPixels? =
    Snapshot
        .withoutReadObservation { source?.pixels }
        ?.takeIf {
            accountRef != null && it.matchesOwner(accountRef, runtimeGeneration, lifetime)
        }

private data class AvatarPixelPreparation(
    val frames: Map<String, PreparedAvatarImage>,
    val ready: Boolean,
) {
    val images: Map<String, ImageBitmap> get() = frames.mapValues { it.value.image }
}

internal const val VISIBLE_GROUP_AVATAR_LIMIT = 16

// A viewport may prepare sixteen more. Recency is tracked by row, not encoded asset or bitmap data.
internal const val RECENT_AVATAR_ROW_LIMIT = 32

private const val LOCAL_GROUP_AVATAR_PREPARATION_BUDGET_MILLIS = 1_000L
