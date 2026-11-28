package dev.ipf.whitenoise.android.ui.group

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.media.ImageSearchResult
import dev.ipf.whitenoise.android.ui.theme.Radii
import dev.ipf.whitenoise.android.ui.theme.ScrimAlpha

/** One image search result as a selectable rounded tile, outlined in the primary colour while staged. */
@Composable
internal fun GroupImageSearchTile(
    hit: ImageSearchResult,
    isSelected: Boolean,
    imageLoader: suspend (String) -> ImageBitmap?,
    imageCacheLookup: (String) -> ImageBitmap?,
    onTap: () -> Unit,
) {
    val thumbnailKey = hit.thumbnailUrl ?: hit.imageUrl
    val thumbnail by produceState<ImageBitmap?>(
        initialValue = imageCacheLookup(thumbnailKey),
        key1 = thumbnailKey,
    ) {
        if (value == null) value = imageLoader(thumbnailKey)
    }
    val borderColor =
        if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    val resultDescription = hit.title.ifBlank { hit.sourceHost.orEmpty() }
    // Surface(onClick) clips the press highlight to the tile's rounded corners.
    Surface(
        onClick = onTap,
        modifier =
            Modifier
                .aspectRatio(1f)
                // TalkBack: announce the current selection so users hear
                // which thumbnail is staged. Without this, the only cue is
                // the border color change, which is inaccessible.
                .semantics {
                    selected = isSelected
                    contentDescription = resultDescription
                },
        shape = RoundedCornerShape(Radii.sm),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(if (isSelected) 2.dp else 1.dp, borderColor),
    ) {
        Box(contentAlignment = Alignment.Center) {
            val bmp = thumbnail
            if (bmp != null) {
                Image(
                    bitmap = bmp,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Footer strip: source host (left) and pixel dimensions (right).
            // Surfacing the host helps avoid picking trackable hotlinked
            // images by mistake; the dimensions hint at "is this big enough
            // to use as an avatar" before the user commits.
            val host = hit.sourceHost?.takeIf { it.isNotBlank() }
            val dims = hit.dimensionsLabel?.takeIf { it.isNotBlank() }
            if (host != null || dims != null) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomCenter)
                            .background(Color.Black.copy(alpha = ScrimAlpha.TILE))
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (host != null) {
                        Text(
                            host,
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                    if (dims != null) {
                        if (host != null) Spacer(Modifier.weight(1f))
                        Text(
                            dims,
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}
