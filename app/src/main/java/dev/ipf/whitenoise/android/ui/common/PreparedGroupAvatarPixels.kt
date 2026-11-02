package dev.ipf.whitenoise.android.ui.common

import androidx.compose.ui.graphics.ImageBitmap
import dev.ipf.marmotkit.AvatarAssetFfi
import dev.ipf.whitenoise.android.state.isRenderable

/** Short-lived decoded-pixel pins for at most one bounded visible working set, not a protocol cache. */
internal data class PreparedGroupAvatarPixels(
    val accountRef: String?,
    val runtimeGeneration: Int,
    val lifetime: Long,
    val images: Map<String, ImageBitmap>,
    val retained: List<PreparedAvatarImage> = emptyList(),
    val recordLoaded: ((AvatarAssetFfi, ImageBitmap) -> Unit)? = null,
) {
    /** Records an exact completed load; absent loads and previous-revision fallback never enter the window. */
    fun recordCompleted(
        asset: AvatarAssetFfi?,
        image: ImageBitmap?,
    ) {
        if (asset != null && image != null) recordLoaded?.invoke(asset, image)
    }

    /** Classification is reusable only for the exact selected revision, never fallback pixels. */
    fun animationClassificationFor(asset: AvatarAssetFfi?): Boolean? =
        asset?.takeIf { it.isRenderable() }?.let { current ->
            retained
                .firstOrNull {
                    it.asset.target == current.target &&
                        it.asset.reference == current.reference &&
                        it.asset.contentRevision == current.contentRevision
                }?.animationClassification
        }

    /** A revision refresh may reuse known pixels, but a removed or different selection never can. */
    fun retainedFor(asset: AvatarAssetFfi?): ImageBitmap? =
        asset?.takeIf { it.isRenderable() }?.let { current ->
            retained.firstOrNull { it.asset.target == current.target && it.asset.reference == current.reference }?.image
        }
}

/** The exact validated revision these pixels depict; never label fallback pixels as a newer revision. */
internal data class PreparedAvatarImage(
    val asset: AvatarAssetFfi,
    val image: ImageBitmap,
    val animationClassification: Boolean? = null,
)
