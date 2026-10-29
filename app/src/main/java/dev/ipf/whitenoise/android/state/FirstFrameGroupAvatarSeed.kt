package dev.ipf.whitenoise.android.state

import androidx.compose.ui.graphics.ImageBitmap
import dev.ipf.whitenoise.android.core.AvatarImageLoader
import dev.ipf.whitenoise.android.core.GroupAvatarImageLoader
import dev.ipf.whitenoise.android.core.GroupProjector
import dev.ipf.whitenoise.android.core.ProfileSanitizer
import dev.ipf.whitenoise.android.core.encryptedGroupAvatarCacheKey

/** The ordered, authoritative display sources remain identifiable after their decoded bytes are evicted. */
internal fun firstFrameGroupAvatarSources(
    item: ChatListItem,
    accountRef: String?,
    avatarUrl: (String) -> String?,
): Sequence<Pair<ChatListAvatarSource, String>> =
    sequence {
        item.selectedAvatarAsset?.let { asset ->
            val key = accountRef?.let { asset.takeIf { it.isRenderable() }?.cacheKey(it) }
            if (key != null) yield(ChatListAvatarSource.DURABLE to key)
            return@sequence
        }
        val legacyUrl = ProfileSanitizer.protocolImageUrl(item.group.avatarUrl)
        if (legacyUrl != null) {
            yield(ChatListAvatarSource.LEGACY_URL to legacyUrl)
            return@sequence
        }
        encryptedGroupAvatarCacheKey(accountRef, item.group)?.let {
            yield(ChatListAvatarSource.ENCRYPTED_GROUP to it)
        }
        val fallback =
            GroupProjector
                .avatarAccount(item.group, item.presentationOtherMemberAccount, item.presentationMemberCount)
                ?.let(avatarUrl)
        if (fallback != null) yield(ChatListAvatarSource.FALLBACK_URL to fallback)
    }

/** Reads cached pixels in source order; a hit never evaluates a lower-priority profile fallback. */
internal fun firstFrameGroupAvatarSeed(
    item: ChatListItem,
    accountRef: String?,
    avatarUrl: (String) -> String?,
): ChatListAvatarSeed? =
    firstFrameGroupAvatarSeed(
        sources = firstFrameGroupAvatarSources(item, accountRef, avatarUrl),
        accountRef = accountRef,
    )

/** A durable or legacy miss stays authoritative; only the encrypted source permits the existing peer fallback. */
internal fun firstFrameGroupAvatarSeed(
    sources: Sequence<Pair<ChatListAvatarSource, String>>,
    accountRef: String?,
): ChatListAvatarSeed? =
    sources.firstNotNullOfOrNull { (source, key) ->
        val image =
            when (source) {
                ChatListAvatarSource.DURABLE -> AvatarImageLoader.cachedImage(key)
                ChatListAvatarSource.ENCRYPTED_GROUP -> GroupAvatarImageLoader.peek(key)
                else -> AvatarImageLoader.peek(key)
            }
        image?.let { avatarSeed(accountRef, source, key, it) }
    }

/** A retained navigation seed is bound to the account and decoded-cache lifetime. */
private fun avatarSeed(
    accountRef: String?,
    source: ChatListAvatarSource,
    key: String,
    image: ImageBitmap,
) = ChatListAvatarSeed(
    source,
    key,
    image,
    accountRef,
    AvatarImageLoader.currentCacheLifetime(),
    AvatarImageLoader.cachedAnimationClassification(key),
)
