package dev.ipf.whitenoise.android.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativePushCapabilityTest {
    /** Gives absent build configuration precedence over unavailable runtime prerequisites. */
    @Test
    fun missingPushServerConfigurationTakesPrecedence() {
        assertEquals(
            NativePushCapability.MissingPushServerConfiguration,
            nativePushCapability(
                pushServerConfigured = false,
                googlePlayServicesAvailable = false,
                firebaseInitialized = false,
            ),
        )
    }

    /** Keeps a missing Play runtime distinct from an otherwise absent Firebase app. */
    @Test
    fun unavailableGooglePlayServicesPrecedesFirebase() {
        assertEquals(
            NativePushCapability.GooglePlayServicesUnavailable,
            nativePushCapability(
                pushServerConfigured = true,
                googlePlayServicesAvailable = false,
                firebaseInitialized = false,
            ),
        )
    }

    /** Reports the packaging/configuration cause only after Play services are usable. */
    @Test
    fun missingFirebaseInitializationIsReportedAfterRuntimeAvailability() {
        assertEquals(
            NativePushCapability.FirebaseUnavailable,
            nativePushCapability(
                pushServerConfigured = true,
                googlePlayServicesAvailable = true,
                firebaseInitialized = false,
            ),
        )
    }

    /** Makes token and registration work eligible only when every prerequisite is met. */
    @Test
    fun everyPrerequisiteProducesAvailableCapability() {
        val capability =
            nativePushCapability(
                pushServerConfigured = true,
                googlePlayServicesAvailable = true,
                firebaseInitialized = true,
            )

        assertEquals(NativePushCapability.Available, capability)
        assertTrue(capability.isAvailable)
        assertFalse(NativePushCapability.FirebaseUnavailable.isAvailable)
    }
}
