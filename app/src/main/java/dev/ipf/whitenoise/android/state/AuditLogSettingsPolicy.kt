package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AuditLogSettingsFfi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
