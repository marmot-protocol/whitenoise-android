package dev.ipf.whitenoise.android.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuditRuntimeReadinessMarkerTest {
    @Test
    fun emitsExactlyOnceOnlyAfterConfiguredRuntimeStarts() {
        val emitted = mutableListOf<String>()
        val marker = AuditRuntimeReadinessMarker(emitted::add)

        assertFalse(marker.emitAfterRuntimeStarted(true, PACKAGE, ENDPOINT, TOKEN, DATA_MODE, false))
        assertTrue(marker.emitAfterRuntimeStarted(true, PACKAGE, ENDPOINT, TOKEN, DATA_MODE, true))
        assertFalse(marker.emitAfterRuntimeStarted(true, PACKAGE, ENDPOINT, TOKEN, DATA_MODE, true))

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

    @Test
    fun missingOrFalseConfigurationNeverEmits() {
        val emitted = mutableListOf<String>()
        val marker = AuditRuntimeReadinessMarker(emitted::add)

        assertFalse(marker.emitAfterRuntimeStarted(false, PACKAGE, ENDPOINT, TOKEN, DATA_MODE, true))
        assertFalse(marker.emitAfterRuntimeStarted(true, PACKAGE, null, TOKEN, DATA_MODE, true))
        assertFalse(marker.emitAfterRuntimeStarted(true, PACKAGE, ENDPOINT, "", DATA_MODE, true))
        assertFalse(marker.emitAfterRuntimeStarted(true, PACKAGE, ENDPOINT, TOKEN, "raw", true))
        assertTrue(emitted.isEmpty())
    }

    private companion object {
        const val PACKAGE = "dev.ipf.whitenoise.android.staging"
        const val ENDPOINT = "https://audit.invalid/upload"
        const val TOKEN = "token"
        const val DATA_MODE = "obfuscated_sensitive_data"
    }
}
