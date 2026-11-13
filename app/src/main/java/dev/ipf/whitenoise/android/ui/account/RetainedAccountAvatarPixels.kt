package dev.ipf.whitenoise.android.ui.account

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.Dp
import dev.ipf.whitenoise.android.core.AvatarImageLoader
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.common.Avatar

/** Holds the last drawn top-bar pixels for the shell's current signed-in accounts. */
internal class RetainedAccountAvatarPixels {
    private data class Picture(
        val url: String,
        val image: ImageBitmap,
    )

    private val pictures = mutableMapOf<String, Picture>()

    /** An unresolved profile can use its last pixels. A resolved profile must match the exact URL. */
    fun imageFor(
        accountId: String,
        url: String?,
        unresolved: Boolean,
    ): ImageBitmap? = pictures[accountId]?.takeIf { it.url == url || (url == null && unresolved) }?.image

    /** Drops pixels when the resolved picture changes, then records the new picture once it is drawn. */
    fun record(
        accountId: String,
        url: String?,
        unresolved: Boolean,
        image: ImageBitmap?,
    ) {
        if (url == null && unresolved) return
        if (url != pictures[accountId]?.url) pictures.remove(accountId)
        if (url != null && image != null) pictures[accountId] = Picture(url, image)
    }
}

/** Retires last-drawn pixels after identity removal, runtime/cache reset, or profile publication. */
@Composable
internal fun rememberRetainedAccountAvatarPixels(appState: WhiteNoiseAppState): RetainedAccountAvatarPixels {
    val accounts =
        appState.accounts
            .filterNot { it.signedOut }
            .map { it.accountIdHex }
            .toSet()
    return remember(
        appState,
        appState.runtimeGeneration,
        appState.profilePresentationEpochForCompose,
        AvatarImageLoader.currentCacheLifetime(),
        accounts,
    ) {
        RetainedAccountAvatarPixels()
    }
}

/** Seeds only the current account and picture while preserving the regular URL loader and animation. */
@Composable
@Suppress("FunctionNaming", "LongParameterList")
internal fun RetainedTopBarAccountAvatar(
    appState: WhiteNoiseAppState,
    retained: RetainedAccountAvatarPixels?,
    accountId: String,
    title: String,
    size: Dp,
    url: String?,
) {
    val unresolved = retained != null && url == null && appState.isProfilePresentationUnresolved(accountId)
    key(retained, unresolved) {
        Avatar(
            title = title,
            seed = accountId,
            size = size,
            pictureUrl = url,
            pictureDomain = AvatarImageLoader.publicProfiles(),
            initialUrlImage = retained?.imageFor(accountId, url, unresolved),
            onUrlImageDisplayed = retained?.let { { image -> it.record(accountId, url, unresolved, image) } },
        )
    }
}
