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

/** Wi-Fi transport registration is not proof of radio association or selection as the default network. */
internal fun backgroundReceiptRegisteredWifiFacts(context: Context): JSONObject {
    val capabilities =
        runCatching {
            val manager = checkNotNull(context.getSystemService(ConnectivityManager::class.java))
            manager.allNetworks.map { checkNotNull(manager.getNetworkCapabilities(it)) }
        }.getOrNull()
    val wifi = capabilities?.filter { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) }
    return JSONObject()
        .put("platform_network_inventory_available", capabilities != null)
        .put("platform_registered_wifi_present", wifi?.isNotEmpty() == true)
        .put(
            "platform_registered_wifi_validated",
            wifi?.any {
                it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            } == true,
        ).put(
            "platform_registered_wifi_unmetered",
            wifi?.any {
                it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            } == true,
        )
}
