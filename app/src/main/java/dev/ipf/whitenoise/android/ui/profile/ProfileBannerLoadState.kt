package dev.ipf.whitenoise.android.ui.profile

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.core.AvatarImageLoader
import dev.ipf.whitenoise.android.ui.theme.Dimens
import dev.ipf.whitenoise.android.ui.theme.Radii

/**
 * [AvatarImageLoader.load] answers null both while it is working and when the
 * fetch failed, so completion has to be tracked separately: a banner that will
 * never arrive is dropped instead of spinning forever.
 */
internal data class ProfileBannerLoadState(
    val image: ImageBitmap?,
    val settled: Boolean,
) {
    /** False once a load has failed, so callers collapse as if there were no banner. */
    val visible: Boolean get() = image != null || !settled
}

/**
 * Banner load state for a surface [targetWidthPx] physical pixels wide.
 *
 * The width selects the loader's bounded banner decode, so the image is resolved for the box it
 * actually fills instead of being upscaled from the avatar cap (#2762).
 */
@Composable
internal fun rememberProfileBannerLoadState(
    bannerUrl: String,
    targetWidthPx: Int,
    peek: (String, Int) -> ImageBitmap? = AvatarImageLoader::peekBanner,
    load: suspend (String, Int) -> ImageBitmap? = { url, width ->
        // A kind-0 banner is published, so it decodes in the public profiles.
        AvatarImageLoader.loadBanner(url, width, AvatarImageLoader.publicProfiles())
    },
): ProfileBannerLoadState {
    var image by remember(bannerUrl, targetWidthPx) { mutableStateOf(peek(bannerUrl, targetWidthPx)) }
    var settled by remember(bannerUrl, targetWidthPx) { mutableStateOf(image != null) }
    LaunchedEffect(bannerUrl, targetWidthPx) {
        if (image == null) {
            image = load(bannerUrl, targetWidthPx)
            settled = true
        }
    }
    return ProfileBannerLoadState(image = image, settled = settled)
}

@Composable
@Suppress("FunctionNaming")
internal fun ProfileBannerImage(
    bannerUrl: String,
    peek: (String, Int) -> ImageBitmap? = AvatarImageLoader::peekBanner,
    load: suspend (String, Int) -> ImageBitmap? = { url, width ->
        // A kind-0 banner is published, so it decodes in the public profiles.
        AvatarImageLoader.loadBanner(url, width, AvatarImageLoader.publicProfiles())
    },
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val targetWidthPx = profileBannerTargetWidthPx(maxWidth)
        ProfileBannerImage(rememberProfileBannerLoadState(bannerUrl, targetWidthPx, peek, load))
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
@Suppress("FunctionNaming")
internal fun ProfileBannerImage(state: ProfileBannerLoadState) {
    val bitmap = state.image
    if (!state.visible) return
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.spaceLg)
                .aspectRatio(2f)
                .clip(RoundedCornerShape(Radii.md))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .testTag(PROFILE_BANNER_TAG),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            LoadingIndicator(modifier = Modifier.size(20.dp).testTag(PROFILE_BANNER_LOADING_TAG))
        }
    }
}

internal const val PROFILE_BANNER_TAG = "profile-banner"
internal const val PROFILE_BANNER_LOADING_TAG = "profile-banner-loading"
