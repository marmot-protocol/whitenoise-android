package dev.ipf.whitenoise.android.ui.conversation.share

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.location.LocationManager
import android.os.CancellationSignal
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

/**
 * Text fallback until a structured location message kind exists. The
 * `maps?q=lat,lng` form is the precise point query every maps app resolves,
 * so a peer on any client at least gets a tappable, accurate link — and our
 * own clients can parse the coordinates back out with
 * [parseSharedLocationFromText] to draw a map bubble.
 */
internal fun formatLocationShareText(location: SharedLocation): String =
    "Location: https://maps.google.com/maps?q=${formatCoordinate(location.latitude)},${formatCoordinate(location.longitude)}"

private val MAPS_QUERY_COORDINATE =
    Regex(
        """\s*(?:Location:\s*)?https://maps\.google\.com/(?:maps)?\?q=(-?\d+(?:\.\d+)?)(?:,|%2C)(-?\d+(?:\.\d+)?)\s*""",
        RegexOption.IGNORE_CASE,
    )

/**
 * Recovers coordinates from a shared-location message so the bubble can draw a
 * map. Deliberately lenient — it accepts the `?q=` and `/maps?q=` forms and a
 * `%2C`-encoded comma, so a location shared by an older build (or another
 * client that pasted only a maps link) still renders a map instead of raw
 * text. The whole body must match; prose around a maps URL stays visible.
 */
internal fun parseSharedLocationFromText(text: String): SharedLocation? {
    val match = MAPS_QUERY_COORDINATE.matchEntire(text) ?: return null
    val lat = match.groupValues[1].toDoubleOrNull() ?: return null
    val lng = match.groupValues[2].toDoubleOrNull() ?: return null
    if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return null
    return SharedLocation(latitude = lat, longitude = lng, accuracyMeters = null)
}

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
