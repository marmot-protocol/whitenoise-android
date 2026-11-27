package dev.ipf.whitenoise.android.ui.common

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import dev.ipf.marmotkit.AvatarAssetFfi
import dev.ipf.whitenoise.android.core.PrivateContactAvatarLoader
import dev.ipf.whitenoise.android.core.encryptedGroupAvatarCacheKey
import dev.ipf.whitenoise.android.state.ChatListAvatarSource
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.firstFrameGroupAvatarSources
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
    val lookup: ((AvatarAssetFfi) -> PreparedAvatarImage?)? = null,
) {
    fun matchesOwner(
        accountRef: String?,
        runtimeGeneration: Int,
        lifetime: Long,
    ): Boolean =
        this.accountRef == accountRef &&
            this.runtimeGeneration == runtimeGeneration &&
            this.lifetime == lifetime

    /** Records an exact completed load; absent loads and previous-revision fallback never enter the window. */
    fun recordCompleted(
        asset: AvatarAssetFfi?,
        image: ImageBitmap?,
    ) {
        if (asset != null && image != null) recordLoaded?.invoke(asset, image)
    }

    fun imageFor(
        asset: AvatarAssetFfi?,
        key: String?,
    ): ImageBitmap? = key?.let { images[it] ?: exactFrame(asset)?.image }

    /** Classification is reusable only for the exact selected revision, never fallback pixels. */
    fun animationClassificationFor(asset: AvatarAssetFfi?): Boolean? = exactFrame(asset)?.animationClassification

    /** A revision refresh may reuse known pixels, but a removed or different selection never can. */
    fun retainedFor(asset: AvatarAssetFfi?): ImageBitmap? = retainedFrame(asset)?.image

    private fun exactFrame(asset: AvatarAssetFfi?): PreparedAvatarImage? {
        val frame = retainedFrame(asset)
        return frame?.takeIf { it.asset.contentRevision == asset?.contentRevision }
    }

    private fun retainedFrame(asset: AvatarAssetFfi?): PreparedAvatarImage? =
        asset?.takeIf { it.isRenderable() }?.let { current ->
            lookup?.invoke(current)
                ?: retained.firstOrNull { it.asset.target == current.target && it.asset.reference == current.reference }
        }
}

internal data class CompatibilityAvatarSelection(
    val row: String,
    val sources: Map<ChatListAvatarSource, String>,
)

/** The encrypted fallback remains drawable while a legacy URL is unavailable. */
internal fun chatRowCompatibilityAvatarSelection(
    item: ChatListItem,
    accountRef: String?,
    avatarUrl: (String) -> String?,
): CompatibilityAvatarSelection {
    val sources = firstFrameGroupAvatarSources(item, accountRef) { item.selectedAvatarUrl ?: avatarUrl(it) }.toMap()
    val encryptedFallback =
        encryptedGroupAvatarCacheKey(accountRef, item.group)?.let { ChatListAvatarSource.ENCRYPTED_GROUP to it }
    return CompatibilityAvatarSelection(
        item.group.groupIdHex,
        if (encryptedFallback == null) sources else sources + encryptedFallback,
    )
}

/** A compatibility image belongs to a selected row and the exact URL or encrypted image key. */
internal class RetainedGroupCompatibilityPixels {
    private data class Picture(
        val source: ChatListAvatarSource,
        val key: String,
        val image: ImageBitmap,
    )

    private var rows by mutableStateOf(emptyMap<String, Map<ChatListAvatarSource, String>>())
    private val pictures = mutableMapOf<String, Picture>()

    /** Retire pictures when their row leaves the working set or the projection changes its source. */
    fun select(selected: List<CompatibilityAvatarSelection>) {
        val available = selected.associate { it.row to it.sources }
        pictures.entries.removeAll { (row, picture) -> available[row]?.get(picture.source) != picture.key }
        rows = available
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
        if (rows[row]?.get(source) != key || PrivateContactAvatarLoader.isPrivate(key)) return
        if (key != null && image != null) pictures[row] = Picture(source, key, image)
    }
}

/** The exact validated revision these pixels depict; never label fallback pixels as a newer revision. */
internal data class PreparedAvatarImage(
    val asset: AvatarAssetFfi,
    val image: ImageBitmap,
    val animationClassification: Boolean? = null,
)
