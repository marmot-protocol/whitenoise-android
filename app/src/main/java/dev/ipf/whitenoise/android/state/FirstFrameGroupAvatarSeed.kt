package dev.ipf.whitenoise.android.state

import androidx.compose.ui.graphics.ImageBitmap
import dev.ipf.whitenoise.android.core.AvatarImageLoader
import dev.ipf.whitenoise.android.core.GroupAvatarImageLoader
import dev.ipf.whitenoise.android.core.GroupProjector
import dev.ipf.whitenoise.android.core.ProfileSanitizer
import dev.ipf.whitenoise.android.core.encryptedGroupAvatarCacheKey

@Suppress("ReturnCount") // Mirrors [GroupAvatar] URL-over-encrypted precedence with early exits.
internal fun firstFrameGroupAvatarSeed(
    item: ChatListItem,
    accountRef: String?,
    avatarUrl: (String) -> String?,
): ChatListAvatarSeed? {
    item.selectedAvatarAsset?.let { asset ->
        val key = accountRef?.let { asset.takeIf { it.isRenderable() }?.cacheKey(it) }
        return key?.let(AvatarImageLoader::cachedImage)?.let { image ->
            avatarSeed(accountRef, ChatListAvatarSource.DURABLE, checkNotNull(key), image)
        }
    }
    val legacyUrl = ProfileSanitizer.protocolImageUrl(item.group.avatarUrl)
    if (legacyUrl != null) {
        return AvatarImageLoader.peek(legacyUrl)?.let { image ->
            avatarSeed(accountRef, ChatListAvatarSource.LEGACY_URL, legacyUrl, image)
        }
    }

    val encryptedCacheKey = encryptedGroupAvatarCacheKey(accountRef, item.group)
    if (encryptedCacheKey != null) {
        GroupAvatarImageLoader.peek(encryptedCacheKey)?.let { image ->
            return avatarSeed(accountRef, ChatListAvatarSource.ENCRYPTED_GROUP, encryptedCacheKey, image)
        }
    }

    val fallbackUrl =
        GroupProjector
            .avatarAccount(item.group, item.presentationOtherMemberAccount, item.presentationMemberCount)
            ?.let(avatarUrl)
    return fallbackUrl?.let { url ->
        AvatarImageLoader.peek(url)?.let { image ->
            avatarSeed(accountRef, ChatListAvatarSource.FALLBACK_URL, url, image)
        }
    }
}

/** A retained navigation seed is bound to the account and decoded-cache lifetime. */
private fun avatarSeed(
    accountRef: String?,
    source: ChatListAvatarSource,
    key: String,
    image: ImageBitmap,
) = ChatListAvatarSeed(source, key, image, accountRef, AvatarImageLoader.currentCacheLifetime())
