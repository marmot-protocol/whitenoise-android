package dev.ipf.whitenoise.android.notifications

/**
 * Explains whether this build and device can register for native push, keeping
 * settings presentation aligned with the runtime gate.
 */
internal enum class NativePushCapability {
    MissingPushServerConfiguration,
    GooglePlayServicesUnavailable,
    FirebaseUnavailable,
    Available,
    ;

    /** Whether token acquisition and MIP-05 registration are safe to attempt. */
    val isAvailable: Boolean
        get() = this == Available
}

/**
 * Resolves native-push prerequisites in dependency order so the first
 * actionable failure is stable when more than one prerequisite is missing.
 */
internal fun nativePushCapability(
    pushServerConfigured: Boolean,
    googlePlayServicesAvailable: Boolean,
    firebaseInitialized: Boolean,
): NativePushCapability =
    when {
        !pushServerConfigured -> NativePushCapability.MissingPushServerConfiguration
        !googlePlayServicesAvailable -> NativePushCapability.GooglePlayServicesUnavailable
        !firebaseInitialized -> NativePushCapability.FirebaseUnavailable
        else -> NativePushCapability.Available
    }
