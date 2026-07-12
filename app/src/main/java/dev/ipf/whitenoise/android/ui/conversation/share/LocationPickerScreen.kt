package dev.ipf.whitenoise.android.ui.conversation.share

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.ipf.whitenoise.android.R
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView

private const val PICKER_ZOOM = 16.0

// Marker Island, a neutral mid-ocean fallback when no fix is available yet, so
// the map is never centered on a misleading real place before a locate.
private val FALLBACK_CENTER = GeoPoint(0.0, 0.0)

private fun configureOsmdroid(context: Context) {
    // The OSM tile-usage policy requires an identifying User-Agent; osmdroid
    // reads it from this singleton before any tile request.
    Configuration.getInstance().userAgentValue = context.packageName
}

@Composable
internal fun LocationPickerScreen(
    hasFineGrant: Boolean,
    onDismiss: () -> Unit,
    onPick: (SharedLocation) -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties =
            DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
            ),
    ) {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        var locating by remember { mutableStateOf(true) }
        val mapView =
            remember {
                configureOsmdroid(context)
                MapView(context).apply {
                    setTileSource(TileSourceFactory.MAPNIK)
                    setMultiTouchControls(true)
                    zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
                    controller.setZoom(PICKER_ZOOM)
                    controller.setCenter(FALLBACK_CENTER)
                }
            }
        DisposableEffect(mapView) {
            mapView.onResume()
            onDispose { mapView.onDetach() }
        }

        fun centerOnCurrent() {
            locating = true
            scope.launch {
                val current = fetchCurrentLocation(context, hasFineGrant)
                if (current != null) {
                    mapView.controller.animateTo(GeoPoint(current.latitude, current.longitude))
                    mapView.controller.setZoom(PICKER_ZOOM)
                }
                locating = false
            }
        }
        LaunchedEffect(Unit) { centerOnCurrent() }

        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())

            // Fixed center pin — the map pans beneath it, and its map coordinate
            // at send time is the picked point (standard map-picker pattern).
            Icon(
                Icons.Default.LocationOn,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier =
                    Modifier
                        .align(Alignment.Center)
                        .padding(bottom = 36.dp)
                        .size(44.dp),
            )

            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.close))
                    }
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    stringResource(R.string.share_location_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            Column(
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(16.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                FilledIconButton(onClick = { centerOnCurrent() }) {
                    if (locating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Icon(Icons.Default.MyLocation, contentDescription = stringResource(R.string.location_use_current))
                    }
                }
                Button(
                    onClick = {
                        val center = mapView.mapCenter
                        onPick(
                            SharedLocation(
                                latitude = center.latitude,
                                longitude = center.longitude,
                                accuracyMeters = null,
                            ),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.location_send_this))
                }
            }
        }
    }
}
