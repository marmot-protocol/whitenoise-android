package dev.ipf.whitenoise.android.state

import android.content.Context
import dev.ipf.marmotkit.AuditDataModeFfi
import dev.ipf.marmotkit.AuditLogSettingsFfi
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
    fun legacyFalsePreservesFullDataAndCompletesMigration() {
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

        AuditLogSettingsPolicy.completeMigration(preferences)

        assertFalse(AuditLogSettingsPolicy.hasLegacyRedactionPreference(preferences))
        assertTrue(AuditLogSettingsPolicy.isMigrationComplete(preferences))
        assertEquals(
            AuditLogSettingsPolicy.MigrationAction.None,
            AuditLogSettingsPolicy.evaluateMigration(preferences, settings),
        )
    }

    @Test
    fun legacyRedactionPreferenceIsRetiredWithoutEncodingMode() {
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

        AuditLogSettingsPolicy.completeMigration(preferences)

        assertFalse(AuditLogSettingsPolicy.hasLegacyRedactionPreference(preferences))
        assertTrue(AuditLogSettingsPolicy.isMigrationComplete(preferences))
    }

    @Test
    fun explicitFullDataAfterMigrationCompleteSurvivesEvaluation() {
        AuditLogSettingsPolicy.completeMigration(preferences)
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
                    complete = { completionCount += 1 },
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
                        complete = { completionCount += 1 },
                    )
                }

            assertTrue(result.isFailure)
            assertEquals(0, completionCount)
            assertFalse(AuditLogSettingsPolicy.isMigrationComplete(preferences))
            assertEquals(action, AuditLogSettingsPolicy.evaluateMigration(preferences, settings))
        }

    @Test
    fun setEnabledPreservesEngineDataMode() =
        runTest {
            val initial =
                AuditLogSettingsFfi(
                    enabled = true,
                    dataMode = AuditDataModeFfi.FULL_DATA,
                )
            val recorded = mutableListOf<AuditLogSettingsFfi>()

            var current = initial
            current =
                applyAuditLogSettingsUpdate(
                    current = current,
                    transform = { AuditLogSettingsPolicy.settingsWithEnabled(it, false) },
                    persist = {
                        recorded += it
                        it
                    },
                )
            current =
                applyAuditLogSettingsUpdate(
                    current = current,
                    transform = { AuditLogSettingsPolicy.settingsWithEnabled(it, true) },
                    persist = {
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
            val initial =
                AuditLogSettingsFfi(
                    enabled = false,
                    dataMode = AuditDataModeFfi.OBFUSCATED_SENSITIVE_DATA,
                )
            val recorded = mutableListOf<AuditLogSettingsFfi>()

            val updated =
                applyAuditLogSettingsUpdate(
                    current = initial,
                    transform = { AuditLogSettingsPolicy.settingsWithRedaction(it, redact = false) },
                    persist = {
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
            val initial =
                AuditLogSettingsFfi(
                    enabled = true,
                    dataMode = AuditDataModeFfi.OBFUSCATED_SENSITIVE_DATA,
                )
            val recorded = mutableListOf<AuditLogSettingsFfi>()

            applyAuditLogSettingsUpdate(
                current = initial,
                transform = { AuditLogSettingsPolicy.settingsWithRedaction(it, redact = false) },
                persist = {
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
            val initial =
                AuditLogSettingsFfi(
                    enabled = false,
                    dataMode = AuditDataModeFfi.OBFUSCATED_SENSITIVE_DATA,
                )
            val recorded = mutableListOf<AuditLogSettingsFfi>()

            applyAuditLogSettingsUpdate(
                current = initial,
                transform = { AuditLogSettingsPolicy.settingsWithEnabled(it, true) },
                persist = {
                    recorded += it
                    it
                },
            )

            assertEquals(
                AuditDataModeFfi.OBFUSCATED_SENSITIVE_DATA,
                recorded.last().dataMode,
            )
        }

    @Test
    fun turningOffRedactionRequiresConfirmation() {
        assertEquals(
            AuditRedactionToggleDecision.RequireFullDataConfirmation,
            auditRedactionToggleDecision(requestedRedact = false),
        )
    }

    @Test
    fun turningOnRedactionAppliesImmediately() {
        assertEquals(
            AuditRedactionToggleDecision.ApplyImmediately(redact = true),
            auditRedactionToggleDecision(requestedRedact = true),
        )
    }
}
