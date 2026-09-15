package dev.ipf.whitenoise.android.state

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ipf.marmotkit.MarmotInterface

/** Android disclosure acknowledgement, not a copy of MDK's recording settings or protocol data. */
internal class AuditUploadConsent(
    private val preferences: SharedPreferences,
) {
    private val revisionKey = "audit_upload_disclosure_revision"
    private val renewalKey = "audit_upload_disclosure_pending"

    val granted: Boolean get() = preferences.getInt(revisionKey, 0) == 1
    var requiresChoice by mutableStateOf(preferences.getBoolean(renewalKey, false))
        private set

    fun choose(enabled: Boolean) {
        check(
            preferences
                .edit()
                .putInt(revisionKey, if (enabled) 1 else 0)
                .putBoolean(renewalKey, false)
                .commit(),
        ) {
            "Could not save audit upload consent"
        }
        requiresChoice = false
    }

    /**
     * Runs before native startup, including background startup.
     * An old local-log choice cannot authorize uploads.
     */
    suspend fun prepare(runtime: MarmotInterface) {
        runtime.configureAuditRuntime(uploadConsentGranted = false)
        if (!granted && runtime.auditLogSettings().enabled) {
            check(preferences.edit().putBoolean(renewalKey, true).commit()) {
                "Could not save audit disclosure renewal"
            }
            requiresChoice = true
            runtime.setAuditLogSettings(runtime.auditLogSettings().copy(enabled = false))
        }
        if (granted) runtime.configureAuditRuntime(uploadConsentGranted = true)
    }
}
