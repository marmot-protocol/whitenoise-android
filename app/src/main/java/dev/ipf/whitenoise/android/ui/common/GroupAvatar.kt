package dev.ipf.whitenoise.android.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.Dp
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.AvatarAssetFfi
import dev.ipf.whitenoise.android.core.AvatarImageLoader
import dev.ipf.whitenoise.android.core.GroupAvatarImageLoader
import dev.ipf.whitenoise.android.core.ProfileSanitizer
import dev.ipf.whitenoise.android.core.encryptedGroupAvatarCacheKey
import dev.ipf.whitenoise.android.state.ChatListAvatarSeed
import dev.ipf.whitenoise.android.state.ChatListAvatarSource
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.cacheKey
import dev.ipf.whitenoise.android.state.durableAvatar

/** Resolves encrypted pixels only within the current owner, runtime, and authoritative image identity. */
@Composable
internal fun rememberEncryptedGroupAvatar(
    appState: WhiteNoiseAppState,
    group: AppGroupRecordFfi,
    accountRef: String? = appState.activeAccountRef,
): ImageBitmap? {
    val cacheKey = encryptedGroupAvatarCacheKey(accountRef, group)
    val image by key(appState, appState.runtimeGeneration, cacheKey) {
        rememberRecoverableAvatar(
            initialImage = GroupAvatarImageLoader.peek(cacheKey),
            enabled = cacheKey != null && accountRef != null,
        ) {
            GroupAvatarImageLoader.load(checkNotNull(cacheKey)) {
                appState.marmotIo {
                    downloadGroupBlossomImage(checkNotNull(accountRef), group.groupIdHex)
                }
            }
        }
    }
    return image
}

/**
 * Renders the URL component first, then the encrypted MDK image, then an
 * optional DM-peer profile URL, matching AppGroupRecordFfi precedence.
 */
@Composable
@Suppress("FunctionNaming")
internal fun GroupAvatar(
    appState: WhiteNoiseAppState,
    group: AppGroupRecordFfi,
    title: String,
    seed: String,
    size: Dp,
    fallbackPictureUrl: String? = null,
    firstFrameAvatar: ChatListAvatarSeed? = null,
    // MarmotKit 0.10.1 keeps avatars durably; its bytes are preferred over fetching the URL, so a row
    // keeps its picture offline and costs no request. Null falls back to the URL path unchanged.
    durableAvatar: AvatarAssetFfi? = null,
) {
    val durableImage = rememberDurableAvatar(appState, durableAvatar)
    val legacyUrl = ProfileSanitizer.protocolImageUrl(group.avatarUrl)
    val encryptedCacheKey = encryptedGroupAvatarCacheKey(appState.activeAccountRef, group)
    val loadedEncryptedImage = rememberEncryptedGroupAvatar(appState, group)
    val encryptedImage =
        firstFrameAvatar
            ?.takeIf {
                it.source == ChatListAvatarSource.ENCRYPTED_GROUP &&
                    it.key == encryptedCacheKey
            }?.image
            ?: loadedEncryptedImage
    val seededUrlImage =
        when (firstFrameAvatar?.source) {
            ChatListAvatarSource.LEGACY_URL ->
                firstFrameAvatar.image.takeIf { firstFrameAvatar.key == legacyUrl }
            ChatListAvatarSource.FALLBACK_URL ->
                firstFrameAvatar.image.takeIf {
                    legacyUrl == null &&
                        firstFrameAvatar.key == fallbackPictureUrl &&
                        encryptedImage == null
                }
            ChatListAvatarSource.ENCRYPTED_GROUP,
            null,
            -> null
        }
    Avatar(
        title = title,
        seed = seed,
        size = size,
        pictureUrl =
            (legacyUrl ?: fallbackPictureUrl?.takeIf { encryptedImage == null })
                ?.takeIf { durableImage == null },
        picture = durableImage ?: seededUrlImage ?: encryptedImage,
    )
}

/**
 * The bytes MarmotKit already stores for [asset], decoded once and held in the shared avatar cache. The
 * read is keyed by reference and content revision, so a refreshed avatar reloads and an unchanged one
 * never does. Null while it loads, or when the engine has nothing to give.
 */
@Composable
private fun rememberDurableAvatar(
    appState: WhiteNoiseAppState,
    asset: AvatarAssetFfi?,
): ImageBitmap? {
    val key = asset?.cacheKey()
    return produceState<ImageBitmap?>(initialValue = key?.let(AvatarImageLoader::cachedImage), key) {
        value = key?.let { appState.durableAvatar(asset) }
    }.value
}
