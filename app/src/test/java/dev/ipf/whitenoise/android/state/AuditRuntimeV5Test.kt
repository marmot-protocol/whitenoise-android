package dev.ipf.whitenoise.android.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Covers consent and environment isolation at the v5 destination boundary. */
class AuditRuntimeV5Test {
    /** One shared token can route safely when the destination identity names staging. */
    @Test
    fun consentedStagingUsesDedicatedLogsDestination() {
        val config =
            auditOtlpConfigV5(
                uploadConsentGranted = true,
                endpoint = " https://audit.example.test/v1/logs ",
                authorizationBearerToken = " token ",
                deploymentEnvironment = "staging",
            )

        assertTrue(config.enabled)
        assertEquals("whitenoise-android-staging", config.destination)
        assertEquals("https://audit.example.test/v1/logs", config.endpoint)
        assertEquals("token", config.authorizationBearerToken)
        assertFalse(config.allowLoopbackDev)
    }

    /** Revocation, missing credentials and non-v5 URLs remove the whole destination. */
    @Test
    fun missingConsentOrCredentialsClearsTheInMemoryDestination() {
        listOf(
            Triple(false, "https://audit.example.test/v1/logs", "token"),
            Triple(true, "", "token"),
            Triple(true, "https://audit.example.test/v1/logs", ""),
            Triple(true, "https://audit.example.test/upload", "token"),
            Triple(true, "http://audit.example.test/v1/logs", "token"),
        ).forEach { (consent, endpoint, token) ->
            val config =
                auditOtlpConfigV5(
                    uploadConsentGranted = consent,
                    endpoint = endpoint,
                    authorizationBearerToken = token,
                    deploymentEnvironment = "production",
                )
            assertFalse(config.enabled)
            assertNull(config.destination)
            assertNull(config.endpoint)
            assertNull(config.authorizationBearerToken)
        }
    }
}
