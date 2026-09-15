package dev.ipf.whitenoise.android.state

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AuditLogSettingsFfi
import dev.ipf.marmotkit.AuditLogTrackerConfigV4Ffi
import dev.ipf.marmotkit.MarmotInterface
import kotlinx.coroutines.test.runTest
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

    private class Native(
        var enabled: Boolean,
        val failDisable: Boolean = false,
    ) {
        val mutations = mutableListOf<String>()
        val runtime =
            Proxy.newProxyInstance(
                MarmotInterface::class.java.classLoader,
                arrayOf(MarmotInterface::class.java),
            ) { _, method, args ->
                when (method.name) {
                    "setAuditLogTrackerConfig" ->
                        (args!![0] as AuditLogTrackerConfigV4Ffi).also {
                            mutations += if (it.authorizationBearerToken == null) "clear-upload" else "allow-upload"
                        }
                    "auditLogSettings" -> AuditLogSettingsFfi(enabled)
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
