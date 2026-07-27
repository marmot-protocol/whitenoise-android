package dev.ipf.whitenoise.android.state

import android.content.SharedPreferences
import dev.ipf.marmotkit.AuditDataModeFfi
import dev.ipf.marmotkit.AuditLogSettingsFfi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal object AuditLogSettingsPolicy {
    private const val LEGACY_REDACT_KEY = "redact_sensitive_audit_data"
    private const val MIGRATION_COMPLETE_KEY = "audit_log_safe_migration_complete"

    fun redactsSensitiveData(settings: AuditLogSettingsFfi): Boolean = settings.dataMode != AuditDataModeFfi.FULL_DATA

    fun settingsWithEnabled(
        settings: AuditLogSettingsFfi,
        enabled: Boolean,
    ): AuditLogSettingsFfi = settings.copy(enabled = enabled)

    fun settingsWithRedaction(
        settings: AuditLogSettingsFfi,
        redact: Boolean,
    ): AuditLogSettingsFfi =
        settings.copy(
            dataMode =
                if (redact) {
                    AuditDataModeFfi.OBFUSCATED_SENSITIVE_DATA
                } else {
                    AuditDataModeFfi.FULL_DATA
                },
        )

    fun hasLegacyRedactionPreference(preferences: SharedPreferences): Boolean = preferences.contains(LEGACY_REDACT_KEY)

    @Suppress("MaxLineLength")
    fun isMigrationComplete(preferences: SharedPreferences): Boolean = preferences.getBoolean(MIGRATION_COMPLETE_KEY, false)

    fun evaluateMigration(
        preferences: SharedPreferences,
        settings: AuditLogSettingsFfi,
    ): MigrationAction =
        when {
            isMigrationComplete(preferences) -> MigrationAction.None
            hasLegacyRedactionPreference(preferences) &&
                !preferences.getBoolean(LEGACY_REDACT_KEY, true) -> MigrationAction.PreserveAndComplete
            settings.dataMode == AuditDataModeFfi.FULL_DATA ->
                MigrationAction.MigrateToObfuscated(settingsWithRedaction(settings, redact = true))
            else -> MigrationAction.CompleteOnly
        }

    suspend fun completeMigration(preferences: SharedPreferences): Boolean =
        withContext(Dispatchers.IO) {
            preferences
                .edit()
                .remove(LEGACY_REDACT_KEY)
                .putBoolean(MIGRATION_COMPLETE_KEY, true)
                .commit()
        }

    sealed interface MigrationAction {
        data object None : MigrationAction

        data object PreserveAndComplete : MigrationAction

        data object CompleteOnly : MigrationAction

        data class MigrateToObfuscated(
            val settings: AuditLogSettingsFfi,
        ) : MigrationAction
    }
}

internal suspend fun updateAuditLogSettingsSerialized(
    mutex: Mutex,
    cachedSettings: () -> AuditLogSettingsFfi?,
    storeCachedSettings: (AuditLogSettingsFfi) -> Unit,
    loadFromEngine: suspend () -> AuditLogSettingsFfi,
    transform: (AuditLogSettingsFfi) -> AuditLogSettingsFfi,
    persistToEngine: suspend (AuditLogSettingsFfi) -> AuditLogSettingsFfi,
): AuditLogSettingsFfi =
    mutex.withLock {
        val current = cachedSettings() ?: loadFromEngine()
        val updated = persistToEngine(transform(current))
        storeCachedSettings(updated)
        updated
    }

internal suspend fun executeAuditLogSettingsMigration(
    action: AuditLogSettingsPolicy.MigrationAction,
    persist: suspend (AuditLogSettingsFfi) -> AuditLogSettingsFfi,
    complete: suspend () -> Boolean,
): AuditLogSettingsFfi? =
    when (action) {
        AuditLogSettingsPolicy.MigrationAction.None -> null
        AuditLogSettingsPolicy.MigrationAction.PreserveAndComplete,
        AuditLogSettingsPolicy.MigrationAction.CompleteOnly,
        -> {
            if (!complete()) error("audit log migration marker write failed")
            null
        }
        is AuditLogSettingsPolicy.MigrationAction.MigrateToObfuscated -> {
            val persisted = persist(action.settings)
            if (!complete()) error("audit log migration marker write failed")
            persisted
        }
    }
