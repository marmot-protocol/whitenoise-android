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

private const val MAPS_QUERY_COORDINATE_PATTERN =
    """https://maps\.google\.com/(?:maps)?\?q=(-?\d+(?:\.\d+)?)(?:,|%2C)(-?\d+(?:\.\d+)?)"""

// A search must not consume leading whitespace: retrying that prefix at every
// position in a whitespace run makes ordinary prose expensive to scan.
private val MAPS_QUERY_COORDINATE = Regex(MAPS_QUERY_COORDINATE_PATTERN, RegexOption.IGNORE_CASE)

private val BARE_MAPS_QUERY_COORDINATE =
    Regex(
        """\s*(?:Location:\s*)?$MAPS_QUERY_COORDINATE_PATTERN\s*""",
        RegexOption.IGNORE_CASE,
    )

/**
 * Recovers coordinates from a shared-location message so the bubble can draw a
 * map. Deliberately lenient — it accepts the `?q=` and `/maps?q=` forms and a
 * `%2C`-encoded comma, so a location shared by an older build (or another
 * client that pasted only a maps link) still renders a map instead of raw
 * text. The link may sit anywhere in the body ("Meet me here: <link>");
 * [isBareLocationShare] decides whether that prose also renders. The first
 * link with in-range coordinates wins, so a bad link cannot hide a later one.
 */
internal fun parseSharedLocationFromText(text: String): SharedLocation? {
    val matches = MAPS_QUERY_COORDINATE.findAll(text)
    return matches.firstNotNullOfOrNull(::locationOf)
}

private fun locationOf(match: MatchResult): SharedLocation? {
    val lat = match.groupValues[1].toDoubleOrNull()?.takeIf { it in -90.0..90.0 }
    val lng = match.groupValues[2].toDoubleOrNull()?.takeIf { it in -180.0..180.0 }
    if (lat == null || lng == null) {
        return null
    }
    return SharedLocation(latitude = lat, longitude = lng, accuracyMeters = null)
}

/** True when the body is only the maps link, so the map card replaces the text. */
internal fun isBareLocationShare(text: String): Boolean = BARE_MAPS_QUERY_COORDINATE.matches(text)

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
