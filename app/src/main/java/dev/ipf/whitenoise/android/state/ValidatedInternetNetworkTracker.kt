package dev.ipf.whitenoise.android.state

import android.net.NetworkCapabilities

internal fun isValidatedNonVpnInternet(
    hasInternetCapability: Boolean,
    hasValidatedCapability: Boolean,
    hasNotVpnCapability: Boolean,
): Boolean = hasInternetCapability && hasValidatedCapability && hasNotVpnCapability

internal fun NetworkCapabilities.providesValidatedNonVpnInternet(): Boolean =
    isValidatedNonVpnInternet(
        hasInternetCapability = hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
        hasValidatedCapability = hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
        hasNotVpnCapability = hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN),
    )

/**
 * Tracks every validated physical internet network Android currently exposes.
 * A set is required because a Wi-Fi/cellular handover briefly exposes both;
 * losing either network must not report offline while the other remains valid.
 */
internal class ValidatedInternetNetworkTracker {
    private val validatedNetworkHandles = mutableSetOf<Long>()

    @Synchronized
    fun update(
        networkHandle: Long,
        available: Boolean,
    ): Boolean {
        if (available) {
            validatedNetworkHandles += networkHandle
        } else {
            validatedNetworkHandles -= networkHandle
        }
        return validatedNetworkHandles.isNotEmpty()
    }

    @Synchronized
    fun remove(networkHandle: Long): Boolean {
        validatedNetworkHandles -= networkHandle
        return validatedNetworkHandles.isNotEmpty()
    }

    @Synchronized
    fun hasValidatedInternet(): Boolean = validatedNetworkHandles.isNotEmpty()
}
