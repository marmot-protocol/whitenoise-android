package dev.ipf.whitenoise.android.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import dev.ipf.marmotkit.AvatarAssetFfi
import dev.ipf.whitenoise.android.core.AvatarImageLoader
import dev.ipf.whitenoise.android.core.AvatarLoadRecovery
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.cacheKey
import dev.ipf.whitenoise.android.state.durableAvatar
import dev.ipf.whitenoise.android.state.isRenderable
import dev.ipf.whitenoise.android.state.wantsAcquisition
import kotlinx.coroutines.flow.first

/**
 * The bytes MarmotKit already stores for [asset], decoded once and held in the shared avatar cache. The
 * read is keyed by reference and content revision, so a refreshed avatar reloads and an unchanged one
 * never does; a cached image is returned on the first frame so re-entering a screen never blanks it.
 * While a new content revision of the same drawable reference is read, the pixels this call site last
 * showed stay up and are swapped in place once the replacement is ready, or kept if the read fails (#3284).
 * A returning list may supply the same valid fallback through its owner-bound prepared window. Exact
 * completed loads join that window even when they finish after its preparation budget; fallback pixels
 * keep their original revision. Null when no valid picture is known; authoritative misses stay placeholders.
 * The decode runs in [domain]'s worker, or the domain the chat list names for this asset when null.
 */
@Composable
internal fun rememberDurableAvatar(
    appState: WhiteNoiseAppState,
    asset: AvatarAssetFfi?,
    accountRef: String? = appState.activeAccountRef,
    domain: dev.ipf.whitenoise.android.media.MediaTrustDomain? = null,
): ImageBitmap? {
    val ownedAsset = asset?.copy()
    val lifetime = AvatarImageLoader.currentCacheLifetime()
    val cacheKey = accountRef?.let { ownedAsset?.takeIf { it.isRenderable() }?.cacheKey(it) }
    val pins =
        LocalPreparedGroupAvatarPixels.current?.takeIf {
            it.accountRef == accountRef &&
                it.runtimeGeneration == appState.runtimeGeneration &&
                it.lifetime == AvatarImageLoader.currentCacheLifetime()
        }
    val displayed = remember(appState) { DisplayedAvatarPixels() }
    val owner = displayedAvatarOwner(ownedAsset, accountRef, appState.runtimeGeneration, lifetime)
    // The availability rides in the key so a `MISSING` asset with the same reference still re-runs the
    // acquisition request once the engine reports it.
    val loaded =
        key(appState, appState.runtimeGeneration, accountRef, lifetime, cacheKey, ownedAsset?.availability) {
            produceState<ImageBitmap?>(
                initialValue = cacheKey?.let { pins?.images?.get(it) ?: AvatarImageLoader.cachedImage(it) },
                ownedAsset?.target,
                ownedAsset?.acquisition,
            ) {
                if (ownedAsset == null || (value != null && !ownedAsset.wantsAcquisition())) return@produceState
                var completedRevision: Long? = null
                AvatarLoadRecovery.revision.first { revision ->
                    if (completedRevision != revision) {
                        appState.durableAvatar(ownedAsset, accountRef, domain = domain)?.let { value = it }
                        // A recovery during the read remains available for the next attempt.
                        completedRevision = revision
                    }
                    value != null
                }
            }.value
        }
    val shown = loaded ?: displayed.retainedFor(owner) ?: pins?.retainedFor(ownedAsset)
    SideEffect {
        displayed.owner = owner
        displayed.image = shown
        // Only exact loaded pixels may enter the window; a prior revision used as fallback stays prior.
        pins?.recordCompleted(ownedAsset, loaded)
    }
    return shown
}

/** Pixels a [rememberDurableAvatar] call site last committed, and the selection they were drawn for. */
private class DisplayedAvatarPixels {
    var owner: DisplayedAvatarOwner? = null
    var image: ImageBitmap? = null

    /** The committed pixels while [current] is still the selection they were drawn for, else null. */
    fun retainedFor(current: DisplayedAvatarOwner?): ImageBitmap? = image.takeIf { current != null && owner == current }
}

/**
 * Everything but the content revision being refreshed: another account, runtime, cache lifetime, target or
 * reference never inherits displayed pixels, and an asset that is no longer drawable has no owner at all.
 */
private fun displayedAvatarOwner(
    asset: AvatarAssetFfi?,
    accountRef: String?,
    runtimeGeneration: Int,
    lifetime: Long,
): DisplayedAvatarOwner? {
    val drawable = asset?.takeIf { it.isRenderable() }
    return if (drawable == null || accountRef == null) {
        null
    } else {
        DisplayedAvatarOwner(accountRef, runtimeGeneration, lifetime, drawable.target, drawable.reference)
    }
}

/** The drawable selection a displayed avatar belongs to, apart from the content revision being refreshed. */
private data class DisplayedAvatarOwner(
    val accountRef: String,
    val runtimeGeneration: Int,
    val lifetime: Long,
    val target: String,
    val reference: String?,
)
