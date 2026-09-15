package dev.ipf.whitenoise.android.state

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ipf.marmotkit.AuditLogSettingsFfi
import dev.ipf.marmotkit.MarmotInterface
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

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
     * The caller serializes this with startup and other settings updates.
     * Clear authorization before changing either durable store. A failed clear
     * leaves the existing receipt untouched and is reported as a failed action.
     */
    @Suppress("TooGenericExceptionCaught") // All native/storage/cancellation failures need cleanup before rethrow.
    suspend fun applyChoice(
        settings: AuditLogSettingsFfi,
        configureUpload: suspend (Boolean) -> Unit,
        persistSettings: suspend (AuditLogSettingsFfi) -> AuditLogSettingsFfi,
    ): AuditLogSettingsFfi {
        configureUpload(false)
        if (!settings.enabled) {
            // Denial survives recorder-cleanup failure; startup cannot restore the token.
            try {
                choose(false)
            } catch (failure: Exception) {
                // If the receipt store fails, persist the stopped recorder so a
                // later process cannot upload using the old acknowledgement.
                withContext(NonCancellable) {
                    runCatching { persistSettings(settings) }.exceptionOrNull()?.let(failure::addSuppressed)
                }
                throw failure
            }
            return persistSettings(settings)
        }
        // Recording is configured without upload authorization. A failed recorder
        // write or receipt write cannot authorize a fresh grant.
        return try {
            val stored = persistSettings(settings)
            choose(true)
            configureUpload(true)
            stored
        } catch (failure: Exception) {
            // No failed grant should be retried as an accepted choice on startup.
            // Keep cleanup alive if the UI coroutine was cancelled at the FFI boundary.
            withContext(NonCancellable) {
                runCatching { configureUpload(false) }.exceptionOrNull()?.let(failure::addSuppressed)
                runCatching { choose(false) }.exceptionOrNull()?.let(failure::addSuppressed)
                runCatching { persistSettings(settings.copy(enabled = false)) }
                    .exceptionOrNull()
                    ?.let(failure::addSuppressed)
            }
            throw failure
        }
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
