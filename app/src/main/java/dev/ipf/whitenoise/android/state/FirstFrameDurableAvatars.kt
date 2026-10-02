package dev.ipf.whitenoise.android.state

import androidx.compose.ui.graphics.ImageBitmap
import dev.ipf.marmotkit.AvatarAssetFfi
import dev.ipf.marmotkit.PresentedChatRowFfi
import dev.ipf.whitenoise.android.core.AvatarImageLoader
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/** Matches the chat list's first-frame avatar seed window, so every seeded row can find decoded pixels. */
internal const val FIRST_FRAME_DURABLE_AVATAR_ROWS = 24

/**
 * The longest a restored or switched-to chat list waits for local avatar decodes. MDK already holds the
 * bytes, so this is normally a few milliseconds; a slower decode keeps the ordinary lazy placeholder path.
 */
internal const val FIRST_FRAME_DURABLE_AVATAR_BUDGET_MS = 250L

/**
 * The renderable MDK-stored avatars of the rows a restored chat list draws first, in the engine's visible
 * order (pinned, then most recent activity). Archived rows are not on the first frame and are skipped.
 */
internal fun firstFrameDurableAvatarAssets(rows: List<PresentedChatRowFfi>): List<AvatarAssetFfi> =
    rows
        .asSequence()
        .filterNot { it.row.archived }
        .sortedWith(
            compareByDescending<PresentedChatRowFfi> { it.row.pinned }.thenByDescending { it.row.activitySortAt },
        ).take(FIRST_FRAME_DURABLE_AVATAR_ROWS)
        .mapNotNull { presented -> presented.avatarAsset?.takeIf(AvatarAssetFfi::isRenderable) }
        .distinctBy { asset -> asset.reference to asset.contentRevision }
        .toList()

/**
 * Decoded pixels for one stored avatar, carried by the one-shot account-switch handoff. The [key] is the
 * account-scoped `marmot-avatar:` key, so the pixels can only ever render for their own account.
 */
internal class FirstFrameDurableAvatar(
    val key: String,
    val image: ImageBitmap,
    val animatedSource: ByteArray?,
)

/**
 * Decodes MDK's stored avatar bytes for the first visible rows before their snapshot is published (#2149).
 * Without it, the first chat-list frame drew generated initials until the asynchronous byte read finished.
 * The decodes go through the in-memory pixel cache, and the decoded pixels are also returned so
 * [installFirstFrameDurableAvatars] can put them back after an interactive account switch evicts that
 * cache. No acquisition is started and nothing is written to an Android store. The wait is bounded by
 * [FIRST_FRAME_DURABLE_AVATAR_BUDGET_MS]; avatars not decoded by then keep the ordinary lazy path.
 */
internal suspend fun WhiteNoiseAppState.prewarmFirstFrameDurableAvatars(
    accountRef: String,
    rows: List<PresentedChatRowFfi>,
): List<FirstFrameDurableAvatar> {
    val assets = firstFrameDurableAvatarAssets(rows).takeIf { it.isNotEmpty() } ?: return emptyList()
    withTimeoutOrNull(FIRST_FRAME_DURABLE_AVATAR_BUDGET_MS) {
        coroutineScope {
            assets.map { asset -> async { durableAvatar(asset, accountRef, acquireMissing = false) } }.awaitAll()
        }
    }
    return assets.mapNotNull { asset ->
        val key = asset.cacheKey(accountRef) ?: return@mapNotNull null
        AvatarImageLoader.cachedImage(key)?.let { image ->
            FirstFrameDurableAvatar(key, image, AvatarImageLoader.peekAnimatedSource(key))
        }
    }
}

/**
 * Republishes the handed-off pixels right where the snapshot is staged. An interactive switch clears every
 * stored avatar between the decode and the staging, so without this the switched-to account's first frame
 * would fall back to initials. On a cold start nothing was cleared and every entry is already present.
 */
internal fun AccountSwitchLocalSnapshot.installFirstFrameDurableAvatars() {
    firstFrameAvatars.forEach { AvatarImageLoader.restoreStoredAvatar(it.key, it.image, it.animatedSource) }
}
