package dev.ipf.whitenoise.android.ui.common

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import dev.ipf.marmotkit.AvatarAssetFfi
import dev.ipf.whitenoise.android.core.PrivateContactAvatarLoader
import dev.ipf.whitenoise.android.state.ChatListAvatarSource
import dev.ipf.whitenoise.android.state.isRenderable

/** Short-lived decoded-pixel pins for at most one bounded visible working set, not a protocol cache. */
internal data class PreparedGroupAvatarPixels(
    val accountRef: String?,
    val runtimeGeneration: Int,
    val lifetime: Long,
    val images: Map<String, ImageBitmap>,
    val retained: List<PreparedAvatarImage> = emptyList(),
    val recordLoaded: ((AvatarAssetFfi, ImageBitmap) -> Unit)? = null,
    val compatibility: RetainedGroupCompatibilityPixels? = null,
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

/** A compatibility image belongs to a selected row and the exact URL or encrypted image key. */
internal class RetainedGroupCompatibilityPixels {
    private data class Picture(
        val source: ChatListAvatarSource,
        val key: String,
        val image: ImageBitmap,
    )

    private var rows by mutableStateOf(emptySet<String>())
    private val pictures = mutableMapOf<String, Picture>()

    /** Keeps only rows in the current bounded window that do not have a durable asset. */
    fun select(visibleRows: Set<String>) {
        rows = visibleRows
        pictures.keys.retainAll(rows)
    }

    /** A row that drew before its first measurement can record on the next composition. */
    fun includes(row: String): Boolean = row in rows

    /** A changed URL or image hash can never reuse the former picture. */
    fun imageFor(
        row: String,
        source: ChatListAvatarSource,
        key: String,
    ): ImageBitmap? = pictures[row]?.takeIf { it.source == source && it.key == key }?.image

    /** Records displayed compatibility pixels after layout selection. Private pictures retain their own owner. */
    fun record(
        row: String,
        source: ChatListAvatarSource,
        key: String?,
        image: ImageBitmap?,
    ) {
        if (row !in rows || PrivateContactAvatarLoader.isPrivate(key)) return
        val previous = pictures[row]
        if (previous != null && (source != previous.source || key != previous.key)) pictures.remove(row)
        if (key != null && image != null) pictures[row] = Picture(source, key, image)
    }
}

/** The exact validated revision these pixels depict; never label fallback pixels as a newer revision. */
internal data class PreparedAvatarImage(
    val asset: AvatarAssetFfi,
    val image: ImageBitmap,
    val animationClassification: Boolean? = null,
)
