package dev.ipf.whitenoise.android.ui.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.key
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import dev.ipf.whitenoise.android.core.AvatarImageLoader
import dev.ipf.whitenoise.android.core.IdentityFormatter
import dev.ipf.whitenoise.android.core.PrivateContactAvatarLoader
import dev.ipf.whitenoise.android.core.formatIdentityMonogram
import dev.ipf.whitenoise.android.ui.EmojiLabel
import dev.ipf.whitenoise.android.ui.EmojiShortcodes
import dev.ipf.whitenoise.android.ui.LocalCustomEmoji
import dev.ipf.whitenoise.android.ui.icons.filled.Image
import dev.ipf.whitenoise.android.ui.theme.AvatarPalette

/**
 * Shows identity-scoped cached pixels or initials, recovering missing URL images while visible. A URL
 * picture whose cached bytes are a GIF animates over its first frame under the shared profile policy; a
 * supplied [picture] animates only when the caller names its stored person-picture [animationKey]. A URL
 * this starts decoding runs in [pictureDomain]'s worker, single-use unless the caller names one, such as
 * [AvatarImageLoader.publicProfiles] for a person's published picture. [followedAccount] names the person this avatar
 * shows, as public-key hex, so it carries the followed-person badge while the active account follows them.
 */
@Composable
internal fun Avatar(
    title: String,
    seed: String,
    size: androidx.compose.ui.unit.Dp,
    pictureUrl: String? = null,
    picture: ImageBitmap? = null,
    animationKey: String? = null,
    groupAnimation: GroupAvatarAnimation? = null,
    pictureDomain: dev.ipf.whitenoise.android.media.MediaTrustDomain =
        dev.ipf.whitenoise.android.media.MediaTrustDomain.Unique,
    initialUrlImage: ImageBitmap? = null,
    onUrlImageDisplayed: ((ImageBitmap?) -> Unit)? = null,
    followedAccount: String? = null,
) {
    val color = AvatarPalette.colors[avatarPaletteIndex(seed.hashCode(), AvatarPalette.colors.size)]
    // Seed from the in-memory cache so re-entering a screen shows an
    // already-loaded avatar immediately, with no placeholder flash and no
    // re-fetch. key(seed, pictureUrl) re-creates the state holder when either
    // the row/account identity or URL changes, so a reused Column slot cannot
    // keep the previous member's bitmap while the new one loads. The outer key
    // is intentional: produceState keys restart the load coroutine, but the
    // state holder itself must also be recreated to re-seed from the new cache
    // key instead of displaying the old bitmap transiently.
    val privateSource = PrivateContactAvatarLoader.isPrivate(pictureUrl)
    val lifetime = AvatarImageLoader.currentCacheLifetime()
    val loadedUrlImage by key(seed, pictureUrl, picture, lifetime) {
        rememberRecoverableAvatar(
            initialImage = AvatarImageLoader.peek(pictureUrl) ?: initialUrlImage,
            enabled = pictureUrl != null && (picture == null || privateSource),
            awaitCached = { AvatarImageLoader.awaitCached(checkNotNull(pictureUrl), lifetime) },
        ) {
            val url = checkNotNull(pictureUrl)
            AvatarImageLoader.load(url, pictureDomain)
        }
    }
    val privateOwnerCurrent =
        !privateSource ||
            PrivateContactAvatarLoader.belongsToActiveAccount(checkNotNull(pictureUrl))
    val image =
        when {
            !privateOwnerCurrent -> null
            privateSource -> loadedUrlImage ?: picture
            else -> picture ?: loadedUrlImage
        }
    if (onUrlImageDisplayed != null) {
        SideEffect { onUrlImageDisplayed(loadedUrlImage.takeIf { image === loadedUrlImage }) }
    }
    val animatedKey =
        if (privateSource) {
            null
        } else if (picture == null) {
            pictureUrl
        } else {
            animationKey
        }
    if (followedAccount != null) {
        FollowedPersonAvatarFrame(followedAccount, size) {
            AvatarCircle(title, size, color, image, animatedKey, groupAnimation)
        }
    } else {
        AvatarCircle(title, size, color, image, animatedKey, groupAnimation)
    }
}

/** The clipped picture or monogram; [Avatar] decides which pixels and who they show. */
@Composable
@Suppress("FunctionNaming")
private fun AvatarCircle(
    title: String,
    size: Dp,
    color: Color,
    image: ImageBitmap?,
    animatedKey: String?,
    groupAnimation: GroupAvatarAnimation?,
) {
    Box(
        modifier = Modifier.size(size).clip(CircleShape).background(color),
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            AvatarAnimationLayers(animatedKey, image, groupAnimation, size)
        } else {
            AvatarMonogram(title, size)
        }
    }
}

/** Fits initials to the fixed-dp circle, including when the system font is enlarged. */
@Composable
@Suppress("FunctionNaming")
private fun AvatarMonogram(
    title: String,
    size: Dp,
) {
    val fontScale = LocalDensity.current.fontScale
    val headlineSp = MaterialTheme.typography.headlineMedium.fontSize
    val fittedFontSize = minOf(size.value * MONOGRAM_DIAMETER_RATIO, headlineSp.value * fontScale).sp / fontScale
    IdentityMonogram(
        title,
        style =
            avatarMonogramStyle(MaterialTheme.typography.titleMedium, fittedFontSize)
                .merge(TextStyle(fontSize = fittedFontSize, fontWeight = FontWeight.SemiBold)),
        color = Color.White,
    )
}

/**
 * The one-glyph monogram for [title] (see [IdentityFormatter.initials]). A custom emoji the device can draw is
 * picked like a Unicode emoji and drawn as artwork; a blank title shows [blank] when one is given.
 */
@Composable
@Suppress("FunctionNaming")
internal fun IdentityMonogram(
    title: String,
    style: TextStyle,
    color: Color = Color.Unspecified,
    blank: String? = null,
) {
    val custom = LocalCustomEmoji.current
    val monogram =
        remember(title, custom, blank) {
            blank?.takeIf { title.isBlank() }
                ?: formatIdentityMonogram(title) { code -> EmojiShortcodes.art(code, custom, emptyMap()) != null }
        }
    EmojiLabel(monogram, color = color, style = style, maxLines = 1)
}

/**
 * [Avatar] for a person's published picture, which decodes in the active account's public profiles; a private
 * contact handle still decodes in its own local domain. It names that domain so a person row cannot fall back to
 * a single-use worker by omission.
 */
@Composable
@Suppress("FunctionNaming")
internal fun PersonAvatar(
    title: String,
    seed: String,
    size: Dp,
    pictureUrl: String?,
    followedAccount: String? = null,
) {
    Avatar(
        title,
        seed,
        size,
        pictureUrl,
        pictureDomain = AvatarImageLoader.publicProfiles(),
        followedAccount = followedAccount,
    )
}

/** Keeps group admission and person-picture intent separate while sharing the avatar's pixels. */
@Composable
@Suppress("FunctionNaming")
private fun AvatarAnimationLayers(
    animatedKey: String?,
    image: ImageBitmap,
    groupAnimation: GroupAvatarAnimation?,
    size: Dp,
) {
    val maxEdgePx = with(LocalDensity.current) { size.roundToPx() }
    if (groupAnimation != null) {
        AnimatedGroupAvatarOverlay(groupAnimation, Modifier.fillMaxSize(), maxEdgePx)
    }
    if (animatedKey != null) {
        AnimatedProfileAvatarOverlay(animatedKey, image, Modifier.fillMaxSize(), maxEdgePx)
    }
}

/** Pins the line box to the glyph and trims font padding, so the monogram sits optically centered in the circle. */
private fun avatarMonogramStyle(
    base: TextStyle,
    fontSize: TextUnit,
): TextStyle =
    base.copy(
        lineHeight = fontSize,
        letterSpacing = 0.sp,
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
        platformStyle = PlatformTextStyle(includeFontPadding = false),
    )

private const val MONOGRAM_DIAMETER_RATIO = 0.48f

internal fun avatarPaletteIndex(
    seedHash: Int,
    paletteSize: Int,
): Int = Math.floorMod(seedHash, paletteSize)
