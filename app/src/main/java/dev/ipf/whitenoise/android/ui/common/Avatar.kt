package dev.ipf.whitenoise.android.ui.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.key
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import dev.ipf.whitenoise.android.core.AvatarImageLoader
import dev.ipf.whitenoise.android.core.IdentityFormatter

private val AvatarPalette =
    listOf(
        Color(0xFF006A6A),
        Color(0xFF8C4A00),
        Color(0xFF5B5FC7),
        Color(0xFF006D3B),
        Color(0xFF9A4055),
    )

@Composable
internal fun Avatar(
    title: String,
    seed: String,
    size: androidx.compose.ui.unit.Dp,
    pictureUrl: String? = null,
) {
    val color = AvatarPalette[avatarPaletteIndex(seed.hashCode(), AvatarPalette.size)]
    // Seed from the in-memory cache so re-entering a screen shows an
    // already-loaded avatar immediately, with no placeholder flash and no
    // re-fetch. key(seed, pictureUrl) re-creates the state holder when either
    // the row/account identity or URL changes, so a reused Column slot cannot
    // keep the previous member's bitmap while the new one loads. The outer key
    // is intentional: produceState keys restart the load coroutine, but the
    // state holder itself must also be recreated to re-seed from the new cache
    // key instead of displaying the old bitmap transiently.
    val image by key(seed, pictureUrl) {
        produceState(AvatarImageLoader.peek(pictureUrl)) {
            if (value == null && pictureUrl != null) value = AvatarImageLoader.load(pictureUrl)
        }
    }
    Box(
        modifier = Modifier.size(size).clip(CircleShape).background(color),
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) {
            Image(
                bitmap = image!!,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            // Derive the font size from the avatar diameter so wide letter
            // pairs (e.g. "MW", "WW") fit inside the circle (#312). The 0.4
            // ratio keeps the worst-case 2-letter pair clear of the bounds at
            // every render size used in the app (36dp..96dp).
            //
            // The avatar's outer Modifier.size(...) is in dp and therefore
            // does NOT scale with the user's accessibility font scale, but a
            // raw `.sp` value would — pushing wide pairs back outside the
            // circle for users on large-font settings. Divide the derived
            // value by `fontScale` so the rendered size is constant in dp
            // and tracks the avatar's actual size, then cap at titleMedium so
            // the existing look is preserved on the large profile/group-detail
            // avatars where titleMedium already fits comfortably. The cap is
            // taken in the same dp-constant space, so it also resists font
            // scale.
            val fontScale = LocalDensity.current.fontScale
            val titleMediumSp = MaterialTheme.typography.titleMedium.fontSize
            val fittedFontSize =
                minOf(size.value * 0.4f, titleMediumSp.value * fontScale).sp / fontScale
            Text(
                IdentityFormatter.initials(title),
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleMedium,
                fontSize = fittedFontSize,
                maxLines = 1,
            )
        }
    }
}

internal fun avatarPaletteIndex(
    seedHash: Int,
    paletteSize: Int,
): Int = Math.floorMod(seedHash, paletteSize)
