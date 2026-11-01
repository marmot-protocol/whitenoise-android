package dev.ipf.whitenoise.android.media

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import org.json.JSONObject

internal enum class FixtureWifiCommandResult { Unavailable }

internal enum class FixtureWifiRadioState { Enabled, Disabled, Unavailable }

/** Closed observations made immediately after the fixed command, not a claim about later connectivity. */
internal data class FixtureWifiCommandSnapshot(
    val result: FixtureWifiCommandResult,
    val radioAfterCommand: FixtureWifiRadioState,
)

internal fun fixtureWifiRadioState(firstLine: String): FixtureWifiRadioState =
    when (firstLine.trim()) {
        "Wifi is enabled" -> FixtureWifiRadioState.Enabled
        "Wifi is disabled" -> FixtureWifiRadioState.Disabled
        else -> FixtureWifiRadioState.Unavailable
    }

/** Closed platform observations keep synthetic fixtures independent of hidden NetworkCapabilities mutators. */
internal data class FixtureNetworkCapabilities(
    val wifi: Boolean,
    val validated: Boolean,
    val unmetered: Boolean,
)

/** Wi-Fi transport registration is not proof of radio association or selection as the default network. */
internal fun backgroundReceiptRegisteredWifiFacts(context: Context): JSONObject =
    backgroundReceiptRegisteredWifiFacts {
        val manager = checkNotNull(context.getSystemService(ConnectivityManager::class.java))
        manager.allNetworks.map { network ->
            manager.getNetworkCapabilities(network)?.let { capabilities ->
                FixtureNetworkCapabilities(
                    wifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
                    validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                    unmetered = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
                )
            }
        }
    }

internal fun backgroundReceiptRegisteredWifiFacts(read: () -> List<FixtureNetworkCapabilities?>): JSONObject {
    val observations = runCatching(read).getOrNull()
    val capabilities = observations?.filterNotNull()
    val wifi = capabilities?.filter { it.wifi }
    return JSONObject()
        .put("platform_network_inventory_available", observations != null)
        .put("platform_network_inventory_complete", observations != null && observations.none { it == null })
        .put("platform_registered_wifi_present", wifi?.isNotEmpty() == true)
        .put(
            "platform_registered_wifi_validated",
            wifi?.any {
                it.validated
            } == true,
        ).put(
            "platform_registered_wifi_unmetered",
            wifi?.any {
                it.unmetered
            } == true,
        )
}
