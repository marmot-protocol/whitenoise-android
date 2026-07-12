package dev.ipf.whitenoise.android.ui.conversation.share

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.ln
import kotlin.math.sinh
import kotlin.math.tan

/** OpenStreetMap slippy-map tile coordinate plus the pin's fractional offset within it. */
internal data class OsmTile(
    val zoom: Int,
    val x: Int,
    val y: Int,
    val offsetXFraction: Float,
    val offsetYFraction: Float,
)

internal fun osmTileFor(
    latitude: Double,
    longitude: Double,
    zoom: Int,
): OsmTile {
    val n = 1 shl zoom
    val latRad = latitude * PI / 180.0
    val xExact = (longitude + 180.0) / 360.0 * n
    val yExact = (1.0 - ln(tan(latRad) + 1.0 / kotlin.math.cos(latRad)) / PI) / 2.0 * n
    val x = xExact.toInt().coerceIn(0, n - 1)
    val y = yExact.toInt().coerceIn(0, n - 1)
    return OsmTile(
        zoom = zoom,
        x = x,
        y = y,
        offsetXFraction = (xExact - x).toFloat().coerceIn(0f, 1f),
        offsetYFraction = (yExact - y).toFloat().coerceIn(0f, 1f),
    )
}

/** Center latitude of a tile — used only to sanity-check the math in tests. */
internal fun osmTileCenterLatitude(
    y: Int,
    zoom: Int,
): Double {
    val n = 1 shl zoom
    val latRad = atan(sinh(PI * (1 - 2.0 * (y + 0.5) / n)))
    return latRad * 180.0 / PI
}

// A single tile is ~200 KB decoded; a small cap keeps a scrolled transcript of
// location bubbles cheap while respecting the OSM tile-usage policy (no bulk
// refetch). Bitmaps live in the cache and are not recycled by callers.
private val tileCache = object : LruCache<String, android.graphics.Bitmap>(24) {}

private suspend fun loadOsmTile(tile: OsmTile): android.graphics.Bitmap? {
    val key = "${tile.zoom}/${tile.x}/${tile.y}"
    tileCache.get(key)?.let { return it }
    return withContext(Dispatchers.IO) {
        runCatching {
            val url = URL("https://tile.openstreetmap.org/${tile.zoom}/${tile.x}/${tile.y}.png")
            val connection = (url.openConnection() as HttpURLConnection)
            try {
                // The OSM tile-usage policy requires an identifying User-Agent.
                connection.setRequestProperty("User-Agent", "dev.ipf.whitenoise.android")
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000
                connection.inputStream.use { BitmapFactory.decodeStream(it) }
            } finally {
                connection.disconnect()
            }
        }.getOrNull()?.also { tileCache.put(key, it) }
    }
}

@Composable
private fun rememberOsmTile(
    latitude: Double,
    longitude: Double,
    zoom: Int,
): Pair<ImageBitmap?, OsmTile> {
    val tile = remember(latitude, longitude, zoom) { osmTileFor(latitude, longitude, zoom) }
    var bitmap by remember(tile) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(tile) { bitmap = loadOsmTile(tile) }
    return remember(bitmap) { bitmap?.asImageBitmap() } to tile
}

@Composable
internal fun LocationMessageBubble(
    location: SharedLocation,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier =
            modifier
                .width(260.dp)
                .clip(RoundedCornerShape(16.dp))
                .clickable(onClick = onOpen),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(1.6f)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                val (image, tile) = rememberOsmTile(location.latitude, location.longitude, zoom = 15)
                if (image != null) {
                    Image(
                        bitmap = image,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    // Anchor the pin at the point's fractional position inside
                    // the tile so it marks the real coordinate, not the center.
                    Box(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .padding(
                                    start = (260.dp * tile.offsetXFraction),
                                    top = (162.dp * tile.offsetYFraction),
                                ),
                    ) {
                        Icon(
                            Icons.Default.LocationOn,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                } else {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.LocationOn,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(
                        stringResource(R.string.share_location_title),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        "${formatCoordinate(location.latitude)}, ${formatCoordinate(location.longitude)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
