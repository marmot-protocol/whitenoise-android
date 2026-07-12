package dev.ipf.whitenoise.android.ui.conversation.share

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.location.LocationManager
import android.os.CancellationSignal
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.ipf.whitenoise.android.R
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/**
 * One-shot location payload for the outgoing share flow — never stored
 * anywhere else, never part of a tracking session. Isolated from the send
 * path so a structured location bubble can replace the text fallback later.
 */
internal data class SharedLocation(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Int?,
)

/** Always dot-decimal — a locale comma separator would break the URL. */
internal fun formatCoordinate(value: Double): String = String.format(Locale.US, "%.6f", value)

/** Text fallback until a structured location message kind exists. */
internal fun formatLocationShareText(location: SharedLocation): String =
    "Location: https://maps.google.com/?q=${formatCoordinate(location.latitude)},${formatCoordinate(location.longitude)}"

internal fun locationGrantAllowsSharing(grants: Map<String, Boolean>): Boolean =
    grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
        grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true

/**
 * GPS needs the fine grant; fused/network/passive work with approximate-only,
 * so a user who chose "approximate location" still gets a fix.
 */
internal fun selectLocationProvider(
    enabledProviders: List<String>,
    hasFineGrant: Boolean,
): String? {
    val preferred =
        if (hasFineGrant) {
            listOf(
                LocationManager.FUSED_PROVIDER,
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER,
            )
        } else {
            listOf(
                LocationManager.FUSED_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER,
            )
        }
    return preferred.firstOrNull { it in enabledProviders }
}

/** Single current-location request; the caller has already secured a grant. */
@SuppressLint("MissingPermission")
internal suspend fun fetchCurrentLocation(
    context: Context,
    hasFineGrant: Boolean,
): SharedLocation? {
    val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
    val provider = selectLocationProvider(manager.getProviders(true), hasFineGrant) ?: return null
    return suspendCancellableCoroutine { continuation ->
        val signal = CancellationSignal()
        continuation.invokeOnCancellation { signal.cancel() }
        runCatching {
            manager.getCurrentLocation(provider, signal, context.mainExecutor) { location ->
                continuation.resume(
                    location?.let {
                        SharedLocation(
                            latitude = it.latitude,
                            longitude = it.longitude,
                            accuracyMeters = if (it.hasAccuracy()) it.accuracy.roundToInt() else null,
                        )
                    },
                )
            }
        }.onFailure { continuation.resume(null) }
    }
}

@Composable
internal fun LocationSharePreviewDialog(
    location: SharedLocation,
    onDismiss: () -> Unit,
    onSend: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.share_location_title)) },
        text = {
            Column {
                Text("${formatCoordinate(location.latitude)}, ${formatCoordinate(location.longitude)}")
                location.accuracyMeters?.let { accuracy ->
                    Text(
                        stringResource(R.string.location_accuracy_format, accuracy),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onSend) {
                Text(stringResource(R.string.send))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
