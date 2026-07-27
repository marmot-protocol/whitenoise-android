package dev.ipf.whitenoise.android.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.Dp
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.whitenoise.android.core.GroupAvatarImageLoader
import dev.ipf.whitenoise.android.core.ProfileSanitizer
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState

internal fun encryptedGroupAvatarCacheKey(
    accountRef: String,
    groupIdHex: String,
    imageHashHex: String,
): String = "$accountRef|${groupIdHex.lowercase()}|${imageHashHex.lowercase()}"

@Composable
internal fun rememberEncryptedGroupAvatar(
    appState: WhiteNoiseAppState,
    group: AppGroupRecordFfi,
): ImageBitmap? {
    val accountRef = appState.activeAccountRef
    val hash =
        group.imageHashHex
            ?.takeIf { !group.pendingConfirmation && group.avatarUrl.isNullOrBlank() }
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    val cacheKey =
        if (accountRef != null && hash != null) {
            encryptedGroupAvatarCacheKey(accountRef, group.groupIdHex, hash)
        } else {
            null
        }
    val image by key(cacheKey) {
        produceState(GroupAvatarImageLoader.peek(cacheKey)) {
            if (value == null && cacheKey != null && accountRef != null) {
                value =
                    GroupAvatarImageLoader.load(cacheKey) {
                        appState.marmotIo {
                            downloadGroupBlossomImage(accountRef, group.groupIdHex)
                        }
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
) {
    val legacyUrl = ProfileSanitizer.imageUrl(group.avatarUrl)
    val encryptedImage = rememberEncryptedGroupAvatar(appState, group)
    Avatar(
        title = title,
        seed = seed,
        size = size,
        pictureUrl = legacyUrl ?: fallbackPictureUrl?.takeIf { encryptedImage == null },
        picture = encryptedImage,
    )
}
