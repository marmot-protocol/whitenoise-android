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

internal fun hasUsableValidatedInternet(
    hasActiveDefaultNetwork: Boolean,
    hasValidatedPhysicalNetwork: Boolean,
): Boolean = hasActiveDefaultNetwork && hasValidatedPhysicalNetwork

/** Aggregate usable-internet state and whether this update restored it. */
internal data class UsableValidatedInternetUpdate(
    val hasUsableInternet: Boolean,
    val restored: Boolean,
)

/**
 * Collapses default-route and validated-upstream callbacks into one recovery
 * edge. Either callback may arrive first or repeat during a handover; only a
 * real aggregate offline-to-online transition emits a wake.
 */
internal class UsableValidatedInternetRecoveryTracker {
    private var initialized = false
    private var hasUsableInternet = false

    /** Establish the initial state without treating process startup as recovery. */
    @Synchronized
    fun seed(
        hasActiveDefaultNetwork: Boolean,
        hasValidatedPhysicalNetwork: Boolean,
    ): UsableValidatedInternetUpdate {
        hasUsableInternet =
            hasUsableValidatedInternet(
                hasActiveDefaultNetwork = hasActiveDefaultNetwork,
                hasValidatedPhysicalNetwork = hasValidatedPhysicalNetwork,
            )
        initialized = true
        return UsableValidatedInternetUpdate(
            hasUsableInternet = hasUsableInternet,
            restored = false,
        )
    }

    /** Update either aggregate input and report one recovery edge at most. */
    @Synchronized
    fun update(
        hasActiveDefaultNetwork: Boolean,
        hasValidatedPhysicalNetwork: Boolean,
    ): UsableValidatedInternetUpdate {
        val wasUsable = hasUsableInternet
        hasUsableInternet =
            hasUsableValidatedInternet(
                hasActiveDefaultNetwork = hasActiveDefaultNetwork,
                hasValidatedPhysicalNetwork = hasValidatedPhysicalNetwork,
            )
        return UsableValidatedInternetUpdate(
            hasUsableInternet = hasUsableInternet,
            restored = initialized && !wasUsable && hasUsableInternet,
        )
    }
}

/**
 * Identity-aware mirror of Android's default-network callback. During a
 * Wi-Fi/mobile handoff Android may announce the replacement before delivering
 * the old network's onLost callback; that stale loss must not clear the new
 * default and briefly report Offline.
 */
internal data class ActiveDefaultNetworkUpdate(
    val hasActiveNetwork: Boolean,
    val identityChanged: Boolean,
)

internal class ActiveDefaultNetworkTracker {
    private var currentNetworkHandle: Long? = null

    @Synchronized
    fun seed(networkHandle: Long?): Boolean {
        currentNetworkHandle = networkHandle
        return currentNetworkHandle != null
    }

    @Synchronized
    fun available(networkHandle: Long): ActiveDefaultNetworkUpdate {
        val identityChanged = currentNetworkHandle != networkHandle
        currentNetworkHandle = networkHandle
        return ActiveDefaultNetworkUpdate(hasActiveNetwork = true, identityChanged = identityChanged)
    }

    @Synchronized
    fun isCurrent(networkHandle: Long): Boolean = currentNetworkHandle == networkHandle

    /** Returns null when [networkHandle] is a stale loss for an older default. */
    @Synchronized
    fun lost(
        networkHandle: Long,
        replacementNetworkHandle: Long?,
    ): ActiveDefaultNetworkUpdate? {
        if (currentNetworkHandle != networkHandle) return null
        val replacement = replacementNetworkHandle?.takeUnless { it == networkHandle }
        val identityChanged = currentNetworkHandle != replacement
        currentNetworkHandle = replacement
        return ActiveDefaultNetworkUpdate(
            hasActiveNetwork = currentNetworkHandle != null,
            identityChanged = identityChanged,
        )
    }
}

/**
 * Tracks every validated physical internet network Android currently exposes.
 * A set is required because a Wi-Fi/cellular handover briefly exposes both;
 * losing either network must not report offline while the other remains valid.
 */
internal class ValidatedInternetNetworkTracker {
    private val validatedNetworkHandles = mutableSetOf<Long>()

    /**
     * Publish the initial network snapshot without letting callback updates race
     * ahead of, and then get overwritten by, that snapshot. The caller must
     * register the callback inside [snapshot] before reading current networks.
     * Callback [update]/[remove] calls share this monitor, so any delivery during
     * the seed is applied immediately after the snapshot handoff.
     */
    @Synchronized
    fun seedAtomically(snapshot: () -> Map<Long, Boolean>): Boolean {
        val networkAvailability = snapshot()
        validatedNetworkHandles.clear()
        networkAvailability.forEach { (networkHandle, available) ->
            if (available) validatedNetworkHandles += networkHandle
        }
        return validatedNetworkHandles.isNotEmpty()
    }

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
