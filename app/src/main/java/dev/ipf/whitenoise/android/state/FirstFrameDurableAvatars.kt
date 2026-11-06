package dev.ipf.whitenoise.android.state

import androidx.compose.ui.graphics.ImageBitmap
import dev.ipf.marmotkit.AvatarAssetFfi
import dev.ipf.marmotkit.PresentedChatRowFfi
import dev.ipf.whitenoise.android.core.AvatarImageLoader
import dev.ipf.whitenoise.android.media.MediaTrustDomain
import dev.ipf.whitenoise.android.media.sandbox.IsolatedMediaDecoder
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
 * The renderable stored avatars of the rows the restored chat list draws first. The rows are projected and
 * ordered exactly as the first frame orders them: [chatListItemFromProjection] and [sortChatListItems] with
 * the same [draftedAtSeconds] lookup `ChatsController` uses (pending confirmation, pinned block and manual pin
 * position, draft-aware recency, arrival order, title). Archived rows are not on the first frame and are
 * skipped. Each asset is the one the row itself adopts, so a picture the row would not draw is not decoded.
 */
internal fun firstFrameDurableAvatarAssets(
    rows: List<PresentedChatRowFfi>,
    draftedAtSeconds: (groupIdHex: String) -> ULong? = { null },
): List<AvatarAssetFfi> = firstFrameDurableAvatarSelections(rows, null, draftedAtSeconds).map { it.first }

/** [firstFrameDurableAvatarAssets] with each asset's worker domain, from the row that selects it. */
internal fun firstFrameDurableAvatarSelections(
    rows: List<PresentedChatRowFfi>,
    accountRef: String?,
    draftedAtSeconds: (groupIdHex: String) -> ULong? = { null },
): List<Pair<AvatarAssetFfi, dev.ipf.whitenoise.android.media.MediaTrustDomain>> =
    sortChatListItems(
        rows.map { chatListItemFromProjection(it.row, it.presentation, it.avatarAsset) },
    ) { item -> draftedAtSeconds(item.group.groupIdHex) }
        .asSequence()
        .filterNot { it.group.archived }
        .take(FIRST_FRAME_DURABLE_AVATAR_ROWS)
        .mapNotNull { item ->
            item.selectedAvatarAsset
                ?.takeIf(AvatarAssetFfi::isRenderable)
                ?.let { it to chatListAvatarDomain(item, accountRef) }
        }.distinctBy { (asset, _) -> asset.reference to asset.contentRevision }
        .toList()

/**
 * Decoded pixels for one stored avatar, carried by the one-shot account-switch handoff. The [key] is the
 * account-scoped `marmot-avatar:` key, so the pixels can only ever render for their own account.
 */
internal class FirstFrameDurableAvatar(
    val key: String,
    val image: ImageBitmap,
    val animatedSource: ByteArray?,
    val animationClassification: Boolean? = null,
)

/**
 * Decodes MDK's stored avatar bytes for the first visible rows before their snapshot is published (#2149).
 * Without it, the first chat-list frame drew generated initials until the asynchronous byte read finished.
 * The decodes go through the in-memory pixel cache, and the decoded pixels are also returned so
 * [installFirstFrameDurableAvatars] can put them back after an interactive account switch evicts that
 * cache. No acquisition is started and nothing is written to an Android store. The wait is bounded by
 * [FIRST_FRAME_DURABLE_AVATAR_BUDGET_MS]; avatars not decoded by then keep the ordinary lazy path.
 *
 * Unless [awaitColdWorkers], only avatars already decoded or whose domain has a worker ready are awaited. A quick
 * account switch sets it false: the switch resets the decoder workers, so each group picture needs a new isolated
 * worker, which can take longer than the whole budget to start (#3496). Waiting for one would delay the switch
 * and still draw the generated initials.
 */
internal suspend fun WhiteNoiseAppState.prewarmFirstFrameDurableAvatars(
    accountRef: String,
    rows: List<PresentedChatRowFfi>,
    awaitColdWorkers: Boolean = true,
): List<FirstFrameDurableAvatar> {
    val selections =
        firstFrameDurableAvatarSelections(rows, accountRef) { groupIdHex ->
            draftStore.draftedAtSecondsFor(accountRef, groupIdHex)
        }.takeIf { it.isNotEmpty() } ?: return emptyList()
    val awaited = if (awaitColdWorkers) selections else selectionsServableNow(selections, accountRef)
    withTimeoutOrNull(FIRST_FRAME_DURABLE_AVATAR_BUDGET_MS) {
        coroutineScope {
            awaited
                .map { (asset, domain) -> async { durableAvatar(asset, accountRef, acquireMissing = false, domain) } }
                .awaitAll()
        }
    }
    return selections.map { it.first }.mapNotNull { asset ->
        val key = asset.cacheKey(accountRef) ?: return@mapNotNull null
        AvatarImageLoader.cachedImage(key)?.let { image ->
            FirstFrameDurableAvatar(
                key,
                image,
                AvatarImageLoader.peekAnimatedSource(key),
                AvatarImageLoader.cachedAnimationClassification(key),
            )
        }
    }
}

/** The [selections] that need no new decoder worker: pixels already cached, or a domain a worker serves now. */
private fun selectionsServableNow(
    selections: List<Pair<AvatarAssetFfi, MediaTrustDomain>>,
    accountRef: String,
): List<Pair<AvatarAssetFfi, MediaTrustDomain>> {
    val ready = IsolatedMediaDecoder.servableNow(selections.map { it.second })
    return selections.filter { (asset, domain) ->
        domain in ready || asset.cacheKey(accountRef)?.let(AvatarImageLoader::cachedImage) != null
    }
}

/**
 * Republishes the handed-off pixels right where the snapshot is staged. An interactive switch clears every
 * stored avatar between the decode and the staging, so without this the switched-to account's first frame
 * would fall back to initials. On a cold start nothing was cleared and every entry is already present.
 */
internal fun AccountSwitchLocalSnapshot.installFirstFrameDurableAvatars() {
    firstFrameAvatars.forEach {
        AvatarImageLoader.restoreStoredAvatar(it.key, it.image, it.animatedSource, it.animationClassification)
    }
}
