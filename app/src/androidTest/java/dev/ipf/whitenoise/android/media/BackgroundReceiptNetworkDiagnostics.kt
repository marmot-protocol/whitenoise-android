package dev.ipf.whitenoise.android.media

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import org.json.JSONArray
import org.json.JSONObject

/**
 * Compares the callback-maintained app mirror with a fresh platform default-network observation on timeout.
 * Exports only closed transport/capability facts: no network handles, addresses, identities or raw dumps.
 * The default can change during a read; that instability is reported rather than hidden by retrying.
 */
internal fun backgroundReceiptNetworkSnapshot(
    context: Context,
    state: WhiteNoiseAppState,
    account: String,
    wifiEnabled: Boolean,
): JSONObject {
    val manager = context.getSystemService(ConnectivityManager::class.java)
    val network = manager?.activeNetwork
    val capabilities = manager?.getNetworkCapabilities(network)
    return JSONObject()
        .put("requested_wifi_enabled", wifiEnabled)
        .put(
            "app_networks",
            JSONArray(
                state
                    .automaticAttachmentPolicy(account)
                    .networks
                    .map { it.name }
                    .sorted(),
            ),
        )
        .put("app_active_network", state.hasActiveNetwork())
        .put("app_validated_internet", state.hasValidatedInternet())
        .put("platform_manager_available", manager != null)
        .put("platform_default_present", network != null)
        .put("platform_capabilities_available", capabilities != null)
        .put("platform_default_stable_during_read", network == manager?.activeNetwork)
        .put("platform_wifi", capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true)
        .put("platform_cellular", capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true)
        .put("platform_vpn", capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true)
        .put("platform_internet", capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true)
        .put("platform_validated", capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true)
        .put("platform_unmetered", capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true)
}
