package dev.ipf.whitenoise.android.state

import android.content.Context
import dev.ipf.marmotkit.AuditDataModeFfi
import dev.ipf.marmotkit.AuditLogSettingsFfi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AuditLogSettingsPolicyTest {
    private val preferences
        get() =
            RuntimeEnvironment
                .getApplication()
                .applicationContext
                .getSharedPreferences("whitenoise-audit-test", Context.MODE_PRIVATE)

    @Before
    fun clearPreferences() {
        preferences.edit().clear().commit()
    }

    @Test
    fun redactionProjectionReflectsEngineModeWhileLoggingDisabled() {
        val obfuscated =
            AuditLogSettingsFfi(
                enabled = false,
                dataMode = AuditDataModeFfi.OBFUSCATED_SENSITIVE_DATA,
            )
        val fullData =
            AuditLogSettingsFfi(
                enabled = false,
                dataMode = AuditDataModeFfi.FULL_DATA,
            )

        assertTrue(AuditLogSettingsPolicy.redactsSensitiveData(obfuscated))
        assertFalse(AuditLogSettingsPolicy.redactsSensitiveData(fullData))
    }

    @Test
    fun redactionProjectionReflectsEngineModeWhileLoggingEnabled() {
        val obfuscated =
            AuditLogSettingsFfi(
                enabled = true,
                dataMode = AuditDataModeFfi.OBFUSCATED_SENSITIVE_DATA,
            )
        val fullData =
            AuditLogSettingsFfi(
                enabled = true,
                dataMode = AuditDataModeFfi.FULL_DATA,
            )

        assertTrue(AuditLogSettingsPolicy.redactsSensitiveData(obfuscated))
        assertFalse(AuditLogSettingsPolicy.redactsSensitiveData(fullData))
    }

    @Test
    fun enabledLegacyFullDataWithoutPreferenceRequiresSafeMigration() {
        val legacySettings =
            AuditLogSettingsFfi(
                enabled = true,
                dataMode = AuditDataModeFfi.FULL_DATA,
            )

        assertFalse(AuditLogSettingsPolicy.hasLegacyRedactionPreference(preferences))
        assertFalse(AuditLogSettingsPolicy.isMigrationComplete(preferences))
        val action = AuditLogSettingsPolicy.evaluateMigration(preferences, legacySettings)
        assertEquals(
            AuditLogSettingsPolicy.MigrationAction.MigrateToObfuscated(
                AuditLogSettingsFfi(
                    enabled = true,
                    dataMode = AuditDataModeFfi.OBFUSCATED_SENSITIVE_DATA,
                ),
            ),
            action,
        )
    }

    @Test
    fun disabledLegacyFullDataWithoutPreferenceRequiresSafeMigration() {
        val legacySettings =
            AuditLogSettingsFfi(
                enabled = false,
                dataMode = AuditDataModeFfi.FULL_DATA,
            )

        assertFalse(AuditLogSettingsPolicy.hasLegacyRedactionPreference(preferences))
        val action = AuditLogSettingsPolicy.evaluateMigration(preferences, legacySettings)
        assertEquals(
            AuditLogSettingsPolicy.MigrationAction.MigrateToObfuscated(
                AuditLogSettingsFfi(
                    enabled = false,
                    dataMode = AuditDataModeFfi.OBFUSCATED_SENSITIVE_DATA,
                ),
            ),
            action,
        )
    }

    @Test
    fun legacyFalsePreservesFullDataAndCompletesMigration() =
        runTest {
            preferences
                .edit()
                .putBoolean("redact_sensitive_audit_data", false)
                .commit()
            val settings =
                AuditLogSettingsFfi(
                    enabled = true,
                    dataMode = AuditDataModeFfi.FULL_DATA,
                )

            assertEquals(
                AuditLogSettingsPolicy.MigrationAction.PreserveAndComplete,
                AuditLogSettingsPolicy.evaluateMigration(preferences, settings),
            )

            assertTrue(AuditLogSettingsPolicy.completeMigration(preferences))

            assertFalse(AuditLogSettingsPolicy.hasLegacyRedactionPreference(preferences))
            assertTrue(AuditLogSettingsPolicy.isMigrationComplete(preferences))
            assertEquals(
                AuditLogSettingsPolicy.MigrationAction.None,
                AuditLogSettingsPolicy.evaluateMigration(preferences, settings),
            )
        }

    @Test
    fun legacyRedactionPreferenceIsRetiredWithoutEncodingMode() =
        runTest {
            preferences
                .edit()
                .putBoolean("redact_sensitive_audit_data", true)
                .commit()
            val obfuscated =
                AuditLogSettingsFfi(
                    enabled = true,
                    dataMode = AuditDataModeFfi.OBFUSCATED_SENSITIVE_DATA,
                )

            assertEquals(
                AuditLogSettingsPolicy.MigrationAction.CompleteOnly,
                AuditLogSettingsPolicy.evaluateMigration(preferences, obfuscated),
            )

            assertTrue(AuditLogSettingsPolicy.completeMigration(preferences))

            assertFalse(AuditLogSettingsPolicy.hasLegacyRedactionPreference(preferences))
            assertTrue(AuditLogSettingsPolicy.isMigrationComplete(preferences))
        }

    @Test
    fun explicitFullDataAfterMigrationCompleteSurvivesEvaluation() =
        runTest {
            assertTrue(AuditLogSettingsPolicy.completeMigration(preferences))
            val settings =
                AuditLogSettingsFfi(
                    enabled = true,
                    dataMode = AuditDataModeFfi.FULL_DATA,
                )

            assertEquals(
                AuditLogSettingsPolicy.MigrationAction.None,
                AuditLogSettingsPolicy.evaluateMigration(preferences, settings),
            )
        }

    @Test
    fun migrationPersistsEngineModeBeforeCompletingPolicy() =
        runTest {
            val settings =
                AuditLogSettingsFfi(
                    enabled = false,
                    dataMode = AuditDataModeFfi.FULL_DATA,
                )
            val action = AuditLogSettingsPolicy.evaluateMigration(preferences, settings)
            val recorded = mutableListOf<AuditLogSettingsFfi>()
            var completionCount = 0

            val migrated =
                executeAuditLogSettingsMigration(
                    action = action,
                    persist = { requested ->
                        assertEquals(0, completionCount)
                        recorded += requested
                        requested
                    },
                    complete = {
                        completionCount += 1
                        true
                    },
                )

            assertEquals(
                AuditLogSettingsFfi(
                    enabled = false,
                    dataMode = AuditDataModeFfi.OBFUSCATED_SENSITIVE_DATA,
                ),
                migrated,
            )
            assertEquals(listOf(migrated), recorded)
            assertEquals(1, completionCount)
        }

    @Test
    fun migrationFailureLeavesPolicyPendingForRetry() =
        runTest {
            val settings =
                AuditLogSettingsFfi(
                    enabled = true,
                    dataMode = AuditDataModeFfi.FULL_DATA,
                )
            val action = AuditLogSettingsPolicy.evaluateMigration(preferences, settings)
            var completionCount = 0

            val result =
                runCatching {
                    executeAuditLogSettingsMigration(
                        action = action,
                        persist = { throw IllegalStateException("mdk write failed") },
                        complete = {
                            completionCount += 1
                            true
                        },
                    )
                }

            assertTrue(result.isFailure)
            assertEquals(0, completionCount)
            assertFalse(AuditLogSettingsPolicy.isMigrationComplete(preferences))
            assertEquals(action, AuditLogSettingsPolicy.evaluateMigration(preferences, settings))
        }

    @Test
    fun migrationMarkerWriteFailureLeavesPolicyPendingForRetry() {
        runBlocking {
            val settings =
                AuditLogSettingsFfi(
                    enabled = true,
                    dataMode = AuditDataModeFfi.FULL_DATA,
                )
            val action = AuditLogSettingsPolicy.evaluateMigration(preferences, settings)

            val result =
                runCatching {
                    executeAuditLogSettingsMigration(
                        action = action,
                        persist = { it },
                        complete = { false },
                    )
                }

            assertTrue(result.isFailure)
            assertFalse(AuditLogSettingsPolicy.isMigrationComplete(preferences))
            assertEquals(action, AuditLogSettingsPolicy.evaluateMigration(preferences, settings))
        }
    }

    @Test
    fun migrationMarkerCommitFailureLeavesPolicyPendingForRetry() {
        runBlocking {
            val settings =
                AuditLogSettingsFfi(
                    enabled = true,
                    dataMode = AuditDataModeFfi.FULL_DATA,
                )
            val action = AuditLogSettingsPolicy.evaluateMigration(preferences, settings)
            val failingPreferences = FailingCommitSharedPreferences(preferences, failCommit = true)

            val result =
                runCatching {
                    executeAuditLogSettingsMigration(
                        action = action,
                        persist = { it },
                        complete = { AuditLogSettingsPolicy.completeMigration(failingPreferences) },
                    )
                }

            assertTrue(result.isFailure)
            assertFalse(AuditLogSettingsPolicy.isMigrationComplete(preferences))
            assertEquals(action, AuditLogSettingsPolicy.evaluateMigration(preferences, settings))
        }
    }

    @Test
    fun migrationMarkerWriteRetriesAfterTransientFailure() {
        runBlocking {
            val settings =
                AuditLogSettingsFfi(
                    enabled = false,
                    dataMode = AuditDataModeFfi.FULL_DATA,
                )
            val action = AuditLogSettingsPolicy.evaluateMigration(preferences, settings)
            val failingPreferences = FailingCommitSharedPreferences(preferences, failCommit = true)

            val firstAttempt =
                runCatching {
                    executeAuditLogSettingsMigration(
                        action = action,
                        persist = { it },
                        complete = { AuditLogSettingsPolicy.completeMigration(failingPreferences) },
                    )
                }
            assertTrue(firstAttempt.isFailure)

            failingPreferences.failCommit = false
            val migrated =
                executeAuditLogSettingsMigration(
                    action = AuditLogSettingsPolicy.evaluateMigration(preferences, settings),
                    persist = { it },
                    complete = { AuditLogSettingsPolicy.completeMigration(failingPreferences) },
                )

            assertEquals(
                AuditLogSettingsFfi(
                    enabled = false,
                    dataMode = AuditDataModeFfi.OBFUSCATED_SENSITIVE_DATA,
                ),
                migrated,
            )
            assertTrue(AuditLogSettingsPolicy.isMigrationComplete(preferences))
        }
    }

    @Test
    fun setEnabledPreservesEngineDataMode() =
        runTest {
            val mutex = Mutex()
            val initial =
                AuditLogSettingsFfi(
                    enabled = true,
                    dataMode = AuditDataModeFfi.FULL_DATA,
                )
            var cached: AuditLogSettingsFfi? = initial
            val recorded = mutableListOf<AuditLogSettingsFfi>()

            cached =
                updateAuditLogSettingsSerialized(
                    mutex = mutex,
                    cachedSettings = { cached },
                    storeCachedSettings = { cached = it },
                    loadFromEngine = { initial },
                    transform = { AuditLogSettingsPolicy.settingsWithEnabled(it, false) },
                    persistToEngine = {
                        recorded += it
                        it
                    },
                )
            cached =
                updateAuditLogSettingsSerialized(
                    mutex = mutex,
                    cachedSettings = { cached },
                    storeCachedSettings = { cached = it },
                    loadFromEngine = { initial },
                    transform = { AuditLogSettingsPolicy.settingsWithEnabled(it, true) },
                    persistToEngine = {
                        recorded += it
                        it
                    },
                )

            assertEquals(
                listOf(
                    AuditLogSettingsFfi(enabled = false, dataMode = AuditDataModeFfi.FULL_DATA),
                    AuditLogSettingsFfi(enabled = true, dataMode = AuditDataModeFfi.FULL_DATA),
                ),
                recorded,
            )
        }

    @Test
    fun setRedactionPersistsThroughEngineWhileLoggingDisabled() =
        runTest {
            val mutex = Mutex()
            val initial =
                AuditLogSettingsFfi(
                    enabled = false,
                    dataMode = AuditDataModeFfi.OBFUSCATED_SENSITIVE_DATA,
                )
            var cached: AuditLogSettingsFfi? = initial
            val recorded = mutableListOf<AuditLogSettingsFfi>()

            val updated =
                updateAuditLogSettingsSerialized(
                    mutex = mutex,
                    cachedSettings = { cached },
                    storeCachedSettings = { cached = it },
                    loadFromEngine = { initial },
                    transform = { AuditLogSettingsPolicy.settingsWithRedaction(it, redact = false) },
                    persistToEngine = {
                        recorded += it
                        it
                    },
                )

            assertEquals(
                listOf(
                    AuditLogSettingsFfi(
                        enabled = false,
                        dataMode = AuditDataModeFfi.FULL_DATA,
                    ),
                ),
                recorded,
            )
            assertFalse(AuditLogSettingsPolicy.redactsSensitiveData(updated))
        }

    @Test
    fun setRedactionHotSwapsActiveRecorderWhileLoggingEnabled() =
        runTest {
            val mutex = Mutex()
            val initial =
                AuditLogSettingsFfi(
                    enabled = true,
                    dataMode = AuditDataModeFfi.OBFUSCATED_SENSITIVE_DATA,
                )
            var cached: AuditLogSettingsFfi? = initial
            val recorded = mutableListOf<AuditLogSettingsFfi>()

            updateAuditLogSettingsSerialized(
                mutex = mutex,
                cachedSettings = { cached },
                storeCachedSettings = { cached = it },
                loadFromEngine = { initial },
                transform = { AuditLogSettingsPolicy.settingsWithRedaction(it, redact = false) },
                persistToEngine = {
                    recorded += it
                    it
                },
            )

            assertEquals(
                listOf(
                    AuditLogSettingsFfi(
                        enabled = true,
                        dataMode = AuditDataModeFfi.FULL_DATA,
                    ),
                ),
                recorded,
            )
        }

    @Test
    fun enablingAuditLoggingPreservesObfuscatedEngineMode() =
        runTest {
            val mutex = Mutex()
            val initial =
                AuditLogSettingsFfi(
                    enabled = false,
                    dataMode = AuditDataModeFfi.OBFUSCATED_SENSITIVE_DATA,
                )
            var cached: AuditLogSettingsFfi? = initial
            val recorded = mutableListOf<AuditLogSettingsFfi>()

            updateAuditLogSettingsSerialized(
                mutex = mutex,
                cachedSettings = { cached },
                storeCachedSettings = { cached = it },
                loadFromEngine = { initial },
                transform = { AuditLogSettingsPolicy.settingsWithEnabled(it, true) },
                persistToEngine = {
                    recorded += it
                    it
                },
            )

            assertEquals(
                AuditDataModeFfi.OBFUSCATED_SENSITIVE_DATA,
                recorded.last().dataMode,
            )
        }
}

private class FailingCommitSharedPreferences(
    private val delegate: android.content.SharedPreferences,
    var failCommit: Boolean,
) : android.content.SharedPreferences by delegate {
    override fun edit(): android.content.SharedPreferences.Editor = FailingEditor(delegate.edit())

    private inner class FailingEditor(
        private val backing: android.content.SharedPreferences.Editor,
    ) : android.content.SharedPreferences.Editor {
        override fun putBoolean(
            key: String?,
            value: Boolean,
        ): android.content.SharedPreferences.Editor {
            backing.putBoolean(key, value)
            return this
        }

        override fun remove(key: String?): android.content.SharedPreferences.Editor {
            backing.remove(key)
            return this
        }

        override fun clear(): android.content.SharedPreferences.Editor {
            backing.clear()
            return this
        }

        override fun putString(
            key: String?,
            value: String?,
        ): android.content.SharedPreferences.Editor {
            backing.putString(key, value)
            return this
        }

        override fun putStringSet(
            key: String?,
            values: MutableSet<String>?,
        ): android.content.SharedPreferences.Editor {
            backing.putStringSet(key, values)
            return this
        }

        override fun putInt(
            key: String?,
            value: Int,
        ): android.content.SharedPreferences.Editor {
            backing.putInt(key, value)
            return this
        }

        override fun putLong(
            key: String?,
            value: Long,
        ): android.content.SharedPreferences.Editor {
            backing.putLong(key, value)
            return this
        }

        override fun putFloat(
            key: String?,
            value: Float,
        ): android.content.SharedPreferences.Editor {
            backing.putFloat(key, value)
            return this
        }

        override fun commit(): Boolean = if (failCommit) false else backing.commit()

        override fun apply() {
            commit()
        }
    }
}
