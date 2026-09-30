package dev.ipf.whitenoise.android.state

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AuditLogSettingsFfi
import dev.ipf.marmotkit.AuditLogTrackerConfigV4Ffi
import dev.ipf.marmotkit.AuditOtlpConfigV5Ffi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.whitenoise.android.audio.DictationDiagnostics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AuditUploadConsentTest {
    private val preferences =
        ApplicationProvider
            .getApplicationContext<Context>()
            .getSharedPreferences("audit-consent-test", Context.MODE_PRIVATE)

    /** Consent given for the former collector does not authorize the new v5 receiver. */
    @Test
    fun previousDisclosureRequiresNewChoiceBeforeUploading() =
        runTest {
            preferences
                .edit()
                .clear()
                .putInt("audit_upload_disclosure_revision", 1)
                .commit()
            val native = Native(enabled = true)
            val consent = AuditUploadConsent(preferences)
            consent.prepare(native.runtime)
            assertFalse(consent.granted)
            assertTrue(consent.requiresChoice)
            assertFalse(native.enabled)
            assertEquals(listOf("clear-upload", "disable"), native.mutations)
        }

    @Test
    fun oldLocalLoggingChoiceIsDisabledBeforeStartupAndRenewalSurvivesRestart() =
        runTest {
            preferences.edit().clear().commit()
            val native = Native(enabled = true)
            val consent = AuditUploadConsent(preferences)
            consent.prepare(native.runtime)
            assertEquals(listOf("clear-upload", "disable"), native.mutations)
            assertFalse(native.enabled)
            assertTrue(consent.requiresChoice)
            val reopened = AuditUploadConsent(preferences)
            reopened.prepare(native.runtime)
            assertTrue(reopened.requiresChoice)
            assertFalse(reopened.granted)
        }

    @Test
    fun declineResolvesRenewalAndNeverRestoresTheOldRecorderChoice() =
        runTest {
            preferences.edit().clear().commit()
            val native = Native(enabled = true)
            val consent = AuditUploadConsent(preferences)
            consent.prepare(native.runtime)
            consent.choose(false)
            consent.prepare(native.runtime)
            assertFalse(consent.requiresChoice)
            assertFalse(consent.granted)
            assertFalse(native.enabled)
        }

    @Test
    fun freshAcknowledgementPersistsButDoesNotEnableAStoppedRecorder() =
        runTest {
            preferences.edit().clear().commit()
            AuditUploadConsent(preferences).choose(true)
            val reopened = AuditUploadConsent(preferences)
            val native = Native(enabled = false)
            reopened.prepare(native.runtime)
            assertTrue(reopened.granted)
            assertFalse(native.enabled)
            reopened.choose(false)
            assertFalse(AuditUploadConsent(preferences).granted)
        }

    /** Native rejection leaves a consented recorder local and cannot assert upload readiness. */
    @Test
    fun rejectedV5UploadDoesNotBlockStartupOrStopLocalRecording() =
        runTest {
            preferences.edit().clear().commit()
            AuditUploadConsent(preferences).choose(true)
            val native = Native(enabled = true, failSecondV5Set = true)
            val consent = AuditUploadConsent(preferences)

            consent.prepare(native.runtime)

            assertTrue(native.enabled)
            assertTrue(consent.granted)
            assertFalse(consent.startupUploadConfigured)
            assertEquals(3, native.auditUploadAttempts.size)
        }

    /** The failed enabled route is cleared before startup continues. */
    @Test
    fun rejectedStartupRouteClearsNativeAuthorization() =
        runTest {
            val attempts = mutableListOf<Boolean>()
            val accepted =
                tryEnableAuditUploadAtStartup(
                    configureUpload = { enabled ->
                        attempts += enabled
                        if (enabled) error("Native rejected v5 route")
                    },
                    reportRejected = {},
                )
            assertFalse(accepted)
            assertEquals(listOf(true, false), attempts)
        }

    /** A cancelled startup clears a partially configured route and still propagates cancellation. */
    @Test
    fun cancelledStartupRouteClearsNativeAuthorization() =
        runTest {
            val attempts = mutableListOf<Boolean>()
            try {
                tryEnableAuditUploadAtStartup(
                    configureUpload = { enabled ->
                        attempts += enabled
                        if (enabled) throw CancellationException("Startup cancelled")
                    },
                    reportRejected = {},
                )
                fail("Cancellation must propagate")
            } catch (_: CancellationException) {
                assertEquals(listOf(true, false), attempts)
            }
        }

    @Test
    fun failureToDisableLegacyRecordingFailsStartupWithUploadCredentialAlreadyRemoved() =
        runTest {
            preferences.edit().clear().commit()
            val native = Native(enabled = true, failDisable = true)
            val consent = AuditUploadConsent(preferences)
            try {
                consent.prepare(native.runtime)
                fail("Startup must not continue after migration fails")
            } catch (_: IllegalStateException) {
                assertEquals("clear-upload", native.mutations.first())
                assertTrue(consent.requiresChoice)
                assertFalse(consent.granted)
            }
        }

    @Test
    fun grantAuthorizesOnlyAfterRecorderAndReceiptPersist() =
        runTest {
            preferences.edit().clear().commit()
            val consent = AuditUploadConsent(preferences)
            var recording = false
            var authorized = false
            consent.applyChoice(
                AuditLogSettingsFfi(true),
                configureUpload = { enabled ->
                    if (enabled) {
                        assertTrue(recording)
                        assertTrue(AuditUploadConsent(preferences).granted)
                    }
                    authorized = enabled
                },
                persistSettings = { settings ->
                    assertFalse(authorized)
                    assertFalse(consent.granted)
                    recording = settings.enabled
                    settings
                },
            )
            assertTrue(recording)
            assertTrue(authorized)
        }

    @Test
    fun failedAuthorizationClearDoesNotClaimACompletedRevocation() =
        runTest {
            preferences.edit().clear().commit()
            val consent = AuditUploadConsent(preferences)
            consent.choose(true)
            var recorderTouched = false
            expectFailure {
                consent.applyChoice(
                    AuditLogSettingsFfi(false),
                    configureUpload = { error("Native configuration unavailable") },
                    persistSettings = {
                        recorderTouched = true
                        it
                    },
                )
            }
            assertTrue(AuditUploadConsent(preferences).granted)
            assertFalse(recorderTouched)
        }

    @Test
    fun recorderFailureAfterRevocationCannotRestoreAuthorizationOnRestart() =
        runTest {
            preferences.edit().clear().commit()
            val consent = AuditUploadConsent(preferences)
            consent.choose(true)
            var authorized = true
            expectFailure {
                consent.applyChoice(
                    AuditLogSettingsFfi(false),
                    configureUpload = { authorized = it },
                    persistSettings = { error("Recorder storage unavailable") },
                )
            }
            assertFalse(authorized)
            assertFalse(AuditUploadConsent(preferences).granted)
            val native = Native(enabled = true)
            AuditUploadConsent(preferences).prepare(native.runtime)
            assertFalse(native.enabled)
            assertEquals(listOf("clear-upload", "disable"), native.mutations)
        }

    @Test
    fun failedGrantAuthorizationRemovesTheReceiptAndStopsRecording() =
        runTest {
            preferences.edit().clear().commit()
            val consent = AuditUploadConsent(preferences)
            var recording = false
            expectFailure {
                consent.applyChoice(
                    AuditLogSettingsFfi(true),
                    configureUpload = { if (it) error("Native configuration unavailable") },
                    persistSettings = {
                        recording = it.enabled
                        it
                    },
                )
            }
            assertFalse(recording)
            assertFalse(AuditUploadConsent(preferences).granted)
        }

    @Test
    fun failedReceiptPersistenceCannotAuthorizeUploads() =
        runTest {
            preferences.edit().clear().commit()
            var recording = false
            var authorized = false
            expectFailure {
                AuditUploadConsent(failingPreferences()).applyChoice(
                    AuditLogSettingsFfi(true),
                    configureUpload = { authorized = it },
                    persistSettings = {
                        recording = it.enabled
                        it
                    },
                )
            }
            assertFalse(recording)
            assertFalse(authorized)
            assertFalse(AuditUploadConsent(preferences).granted)
        }

    @Test
    fun failedDenialReceiptStillPersistsAStoppedRecorderForRestart() =
        runTest {
            preferences.edit().clear().commit()
            AuditUploadConsent(preferences).choose(true)
            val native = Native(enabled = true)
            var authorized = true
            expectFailure {
                AuditUploadConsent(failingPreferences()).applyChoice(
                    AuditLogSettingsFfi(false),
                    configureUpload = { authorized = it },
                    persistSettings = { native.runtime.setAuditLogSettings(it) },
                )
            }
            assertFalse(authorized)
            assertFalse(native.enabled)
            // The old receipt can survive a storage error, but the native recorder
            // must remain disabled even when startup reads that old receipt.
            AuditUploadConsent(preferences).prepare(native.runtime)
            assertFalse(native.enabled)
        }

    @Test
    fun failedRecorderEnableCannotSaveOrAuthorizeAGrant() =
        runTest {
            preferences.edit().clear().commit()
            var authorized = false
            expectFailure {
                AuditUploadConsent(preferences).applyChoice(
                    AuditLogSettingsFfi(true),
                    configureUpload = { authorized = it },
                    persistSettings = {
                        if (it.enabled) error("Storage unavailable")
                        it
                    },
                )
            }
            assertFalse(authorized)
            assertFalse(AuditUploadConsent(preferences).granted)
        }

    @Test
    fun successfulGrantRecordsLocallyAndRevocationStopsNewEvents() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            DictationDiagnostics.attach(context)
            DictationDiagnostics.clear()
            preferences.edit().clear().commit()
            val consent = AuditUploadConsent(preferences)
            try {
                consent.applyChoice(AuditLogSettingsFfi(true), configureUpload = {}, persistSettings = { it })
                DictationDiagnostics.record("event=session_started session=1")
                val first = DictationDiagnostics.snapshot().getValue("dictation-current.jsonl").decodeToString()
                consent.applyChoice(AuditLogSettingsFfi(false), configureUpload = {}, persistSettings = { it })
                DictationDiagnostics.record("event=session_started session=2")
                val afterRevocation =
                    DictationDiagnostics.snapshot().getValue("dictation-current.jsonl").decodeToString()
                assertEquals(first, afterRevocation)
            } finally {
                DictationDiagnostics.setEnabled(false)
                DictationDiagnostics.clear()
            }
        }

    @Test
    fun failedConsentAndSettingsWritesStopLocalCollectionImmediately() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            DictationDiagnostics.attach(context)
            DictationDiagnostics.clear()
            preferences.edit().clear().commit()
            val consent = AuditUploadConsent(preferences)
            try {
                consent.applyChoice(AuditLogSettingsFfi(true), configureUpload = {}, persistSettings = { it })
                expectFailure { AuditUploadConsent(failingPreferences()).choose(false) }
                DictationDiagnostics.record("event=session_started session=1")
                assertTrue(DictationDiagnostics.snapshot().keys.none { it.endsWith(".jsonl") })
                consent.applyChoice(AuditLogSettingsFfi(true), configureUpload = {}, persistSettings = { it })
                expectFailure {
                    consent.applyChoice(
                        AuditLogSettingsFfi(false),
                        configureUpload = {},
                        persistSettings = { error("Unavailable") },
                    )
                }
                DictationDiagnostics.record("event=session_started session=2")
                val snapshot = DictationDiagnostics.snapshot()
                assertTrue(snapshot.keys.none { it.endsWith(".jsonl") })
                val manifest = JSONObject(snapshot.getValue("dictation-manifest.json").decodeToString())
                assertFalse(manifest.getBoolean("collection_enabled"))
            } finally {
                DictationDiagnostics.setEnabled(false)
                DictationDiagnostics.clear()
            }
        }

    @Test
    fun unavailableDiagnosticSettingsReadDoesNotBlockProductConfiguration() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            DictationDiagnostics.attach(context)
            DictationDiagnostics.clear()
            preferences.edit().clear().commit()
            val consent = AuditUploadConsent(preferences)
            consent.choose(true)
            val native = Native(enabled = true, failSettingsRead = true)
            configureAndroidPrivacyRuntime(native.runtime, consent, Mutex(), configureTelemetry = {})
            assertTrue(native.productConfigured)
            DictationDiagnostics.record("event=session_started session=1")
            assertTrue(DictationDiagnostics.snapshot().keys.none { it.endsWith(".jsonl") })
        }

    private fun failingPreferences(): SharedPreferences =
        Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { _, method, args ->
            if (method.name == "edit") {
                failingEditor()
            } else {
                method.invoke(preferences, *(args ?: emptyArray()))
            }
        } as SharedPreferences

    private fun failingEditor(): SharedPreferences.Editor =
        Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java),
        ) { proxy, method, _ ->
            if (method.name == "commit") false else proxy
        } as SharedPreferences.Editor

    /** Verifies a failed consent transition is surfaced instead of silently accepted. */
    private suspend fun expectFailure(action: suspend () -> Unit) {
        try {
            action()
            fail("The failed transition must be reported to the caller")
        } catch (_: IllegalStateException) {
            // Expected injected persistence/configuration failure.
        }
    }

    private class Native(
        var enabled: Boolean,
        val failDisable: Boolean = false,
        val failSecondV5Set: Boolean = false,
        val failSettingsRead: Boolean = false,
    ) {
        var productConfigured = false
        val mutations = mutableListOf<String>()
        val auditUploadAttempts = mutableListOf<Boolean>()
        val runtime =
            Proxy.newProxyInstance(
                MarmotInterface::class.java.classLoader,
                arrayOf(MarmotInterface::class.java),
            ) { _, method, args ->
                when (method.name) {
                    "setAuditOtlpConfigV5" ->
                        (args!!.first() as AuditOtlpConfigV5Ffi).also {
                            auditUploadAttempts += it.enabled
                            if (failSecondV5Set && auditUploadAttempts.size == 2) error("Native rejected v5 route")
                        }
                    "setAuditLogTrackerConfig" ->
                        (args!![0] as AuditLogTrackerConfigV4Ffi).also {
                            mutations += if (it.authorizationBearerToken == null) "clear-upload" else "allow-upload"
                        }
                    "auditLogSettings" -> {
                        check(!failSettingsRead) { "Read unavailable" }
                        AuditLogSettingsFfi(enabled)
                    }
                    "setProductAnalyticsRuntimeConfig" -> args!!.first().also { productConfigured = true }
                    "setAuditLogSettings" ->
                        (args!![0] as AuditLogSettingsFfi).also {
                            check(!failDisable) { "Storage unavailable" }
                            enabled = it.enabled
                            mutations += if (enabled) "enable" else "disable"
                        }
                    else -> error("Unexpected native call: ${method.name}")
                }
            } as MarmotInterface
    }
}
