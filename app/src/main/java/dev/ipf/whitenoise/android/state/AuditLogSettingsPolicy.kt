package dev.ipf.whitenoise.android.state

import android.content.SharedPreferences
import dev.ipf.marmotkit.AuditDataModeFfi
import dev.ipf.marmotkit.AuditLogSettingsFfi

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

    fun completeMigration(preferences: SharedPreferences) {
        preferences
            .edit()
            .remove(LEGACY_REDACT_KEY)
            .putBoolean(MIGRATION_COMPLETE_KEY, true)
            .apply()
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

internal suspend fun applyAuditLogSettingsUpdate(
    current: AuditLogSettingsFfi,
    transform: (AuditLogSettingsFfi) -> AuditLogSettingsFfi,
    persist: suspend (AuditLogSettingsFfi) -> AuditLogSettingsFfi,
): AuditLogSettingsFfi {
    val requested = transform(current)
    return persist(requested)
}

internal suspend fun executeAuditLogSettingsMigration(
    action: AuditLogSettingsPolicy.MigrationAction,
    persist: suspend (AuditLogSettingsFfi) -> AuditLogSettingsFfi,
    complete: () -> Unit,
): AuditLogSettingsFfi? =
    when (action) {
        AuditLogSettingsPolicy.MigrationAction.None -> null
        AuditLogSettingsPolicy.MigrationAction.PreserveAndComplete,
        AuditLogSettingsPolicy.MigrationAction.CompleteOnly,
        -> {
            complete()
            null
        }
        is AuditLogSettingsPolicy.MigrationAction.MigrateToObfuscated ->
            persist(action.settings).also { complete() }
    }

internal sealed interface AuditRedactionToggleDecision {
    data object RequireFullDataConfirmation : AuditRedactionToggleDecision

    data class ApplyImmediately(
        val redact: Boolean,
    ) : AuditRedactionToggleDecision
}

internal fun auditRedactionToggleDecision(requestedRedact: Boolean): AuditRedactionToggleDecision =
    if (requestedRedact) {
        AuditRedactionToggleDecision.ApplyImmediately(redact = true)
    } else {
        AuditRedactionToggleDecision.RequireFullDataConfirmation
    }
