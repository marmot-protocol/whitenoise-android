package dev.ipf.whitenoise.android.media

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
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
    val wifiState = backgroundReceiptWifiRadioState(context)
    val radio = wifiState?.first
    val supplicant = wifiState?.second
    val wifiCapabilities =
        runCatching {
            manager
                ?.allNetworks
                ?.mapNotNull { manager.getNetworkCapabilities(it) }
                ?.filter { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) }
        }.getOrNull()
    return JSONObject()
        .put("wifi_radio_observation_available", radio != null)
        .put("wifi_radio_enabled", radio ?: JSONObject.NULL)
        .put("wifi_supplicant_state", supplicant ?: JSONObject.NULL)
        .put("wifi_network_observation_available", wifiCapabilities != null)
        .put("wifi_network_present", wifiCapabilities?.isNotEmpty() ?: JSONObject.NULL)
        .put(
            "wifi_network_internet",
            wifiCapabilities?.any { it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) } ?: JSONObject.NULL,
        ).put(
            "wifi_network_validated",
            wifiCapabilities?.any { it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) } ?: JSONObject.NULL,
        ).put(
            "wifi_network_unmetered",
            wifiCapabilities?.any { it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) }
                ?: JSONObject.NULL,
        ).put("requested_wifi_enabled", wifiEnabled)
        .put(
            "app_networks",
            JSONArray(
                state
                    .automaticAttachmentPolicy(account)
                    .networks
                    .map { it.name }
                    .sorted(),
            ),
        ).put("app_active_network", state.hasActiveNetwork())
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

private fun backgroundReceiptWifiRadioState(context: Context): Pair<Boolean?, String?>? {
    val wifiManager = context.applicationContext.getSystemService(WifiManager::class.java)
    // The application intentionally has no Wi-Fi-state permission; adopt it only for these test observations.
    return runCatching {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.adoptShellPermissionIdentity("android.permission.ACCESS_WIFI_STATE")
        try {
            Pair(wifiManager?.isWifiEnabled, wifiManager?.connectionInfo?.supplicantState?.name)
        } finally {
            automation.dropShellPermissionIdentity()
        }
    }.getOrNull()
}

/** Traces the existing association commands under their original shared deadline, with no raw shell output. */
internal fun <T> traceBackgroundReceiptWifiAssociation(
    deadline: Long,
    report: (JSONObject) -> Unit = ControlledAttachmentProbe::report,
    monotonicMillis: () -> Long = SystemClock::elapsedRealtime,
    block: () -> T,
): T {
    val startedAt = monotonicMillis()

    fun emit(event: String) {
        runCatching {
            val now = monotonicMillis()
            report(
                JSONObject()
                    .put("phase", "background-receipt-wifi-association")
                    .put("event", event)
                    .put("elapsed_ms", now - startedAt)
                    .put("remaining_budget_ms", (deadline - now).coerceAtLeast(0L)),
            )
        }
    }
    emit("entry")
    return try {
        block().also { emit("complete") }
    } catch (failure: Throwable) {
        emit("failed")
        throw failure
    }
}
