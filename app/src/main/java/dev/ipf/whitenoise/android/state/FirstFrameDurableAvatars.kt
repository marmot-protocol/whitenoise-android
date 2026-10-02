package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AvatarAssetFfi
import dev.ipf.marmotkit.PresentedChatRowFfi
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
 * Decodes MDK's stored avatar bytes for the first visible rows before their snapshot is published (#2149).
 * Without it, a cold start's first chat-list frame drew generated initials until the asynchronous byte read
 * finished. Only the existing in-memory decoded-pixel cache is filled: no acquisition is started and no
 * protocol data is copied into an Android store. The wait is bounded by [FIRST_FRAME_DURABLE_AVATAR_BUDGET_MS].
 */
internal suspend fun WhiteNoiseAppState.prewarmFirstFrameDurableAvatars(
    accountRef: String,
    rows: List<PresentedChatRowFfi>,
) {
    val assets = firstFrameDurableAvatarAssets(rows).takeIf { it.isNotEmpty() } ?: return
    withTimeoutOrNull(FIRST_FRAME_DURABLE_AVATAR_BUDGET_MS) {
        coroutineScope {
            assets.map { asset -> async { durableAvatar(asset, accountRef, acquireMissing = false) } }.awaitAll()
        }
    }
}
