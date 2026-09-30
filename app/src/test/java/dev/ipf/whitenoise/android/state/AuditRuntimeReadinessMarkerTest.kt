package dev.ipf.whitenoise.android.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies the marker reflects the effective sender rather than raw release inputs. */
class AuditRuntimeReadinessMarkerTest {
    /** A configured sender emits once after the recorder starts. */
    @Test
    fun emitsExactlyOnceOnlyAfterConfiguredRuntimeStarts() {
        val emitted = mutableListOf<String>()
        val marker = AuditRuntimeReadinessMarker(emitted::add)
        val uploadConfig = configuredUpload(consent = true, endpoint = ENDPOINT)

        assertFalse(marker.emitAfterRuntimeStarted(true, PACKAGE, uploadConfig, DATA_MODE, false))
        assertTrue(marker.emitAfterRuntimeStarted(true, PACKAGE, uploadConfig, DATA_MODE, true))
        assertFalse(marker.emitAfterRuntimeStarted(true, PACKAGE, uploadConfig, DATA_MODE, true))

        assertEquals(
            listOf(
                "WHITENOISE_AUDIT_READY_V1 " +
                    "{\"schema_version\":1,\"package_name\":\"$PACKAGE\"," +
                    "\"enabled\":true,\"recorder_started\":true," +
                    "\"upload_configured\":true,\"data_mode\":\"$DATA_MODE\"}",
            ),
            emitted,
        )
    }

    /** Missing consent or an invalid nonblank endpoint never declares upload ready. */
    @Test
    fun missingOrFalseConfigurationNeverEmits() {
        val emitted = mutableListOf<String>()
        val marker = AuditRuntimeReadinessMarker(emitted::add)
        val configured = configuredUpload(consent = true, endpoint = ENDPOINT)
        val noConsent = configuredUpload(consent = false, endpoint = ENDPOINT)
        val badEndpoint = configuredUpload(consent = true, endpoint = "https://audit.invalid/upload")
        val missingToken = auditOtlpConfigV5(true, ENDPOINT, "", "staging")

        assertFalse(marker.emitAfterRuntimeStarted(false, PACKAGE, configured, DATA_MODE, true))
        assertFalse(marker.emitAfterRuntimeStarted(true, PACKAGE, noConsent, DATA_MODE, true))
        assertFalse(marker.emitAfterRuntimeStarted(true, PACKAGE, badEndpoint, DATA_MODE, true))
        assertFalse(marker.emitAfterRuntimeStarted(true, PACKAGE, missingToken, DATA_MODE, true))
        assertFalse(marker.emitAfterRuntimeStarted(true, PACKAGE, configured, "raw", true))
        assertTrue(emitted.isEmpty())
    }

    private fun configuredUpload(
        consent: Boolean,
        endpoint: String,
    ) = auditOtlpConfigV5(consent, endpoint, TOKEN, "staging")

    private companion object {
        const val PACKAGE = "dev.ipf.whitenoise.android.staging"
        const val ENDPOINT = "https://audit.invalid/v1/logs"
        const val TOKEN = "token"
        const val DATA_MODE = "obfuscated_sensitive_data"
    }
}
