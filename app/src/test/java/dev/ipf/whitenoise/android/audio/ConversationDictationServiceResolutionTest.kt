package dev.ipf.whitenoise.android.audio

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.provider.Settings
import android.speech.RecognitionService
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ConversationDictationServiceResolutionTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val platform = AndroidConversationDictationPlatform(context)

    @Test
    fun emptySystemSelectionUsesTheOneEligibleInstalledService() {
        installService()
        assertTrue(platform.recognitionConfigured())
        assertTrue(platform.recognitionAvailable())
    }

    @Test
    fun disabledAndPrivateServicesAreNotUsableProviders() {
        installService(name = "Disabled", enabled = false)
        installService(name = "Private", exported = false)
        installService(name = "DisabledApp", appEnabled = false)
        assertFalse(platform.recognitionConfigured())
        assertFalse(platform.recognitionAvailable())
    }

    @Test
    fun disappearingSelectedServiceDoesNotChooseAnotherInstalledProvider() {
        installService()
        Settings.Secure.putString(
            context.contentResolver,
            VOICE_RECOGNITION_SERVICE_SETTING,
            "org.missing/.Recognition",
        )
        assertFalse(platform.recognitionConfigured())
    }

    @Test
    fun serviceSelectionIsPinnedUntilTheNextSessionPreflight() {
        val component = installService()
        assertTrue(platform.recognitionConfigured())
        component.enabled = false
        installService(name = "Replacement")
        assertFalse(platform.recognitionAvailable())
        assertTrue(platform.recognitionConfigured())
        assertTrue(platform.recognitionAvailable())
    }

    @Suppress("DEPRECATION")
    private fun installService(
        name: String = "Recognition",
        enabled: Boolean = true,
        exported: Boolean = true,
        appEnabled: Boolean = true,
    ): ServiceInfo {
        val service =
            ServiceInfo().apply {
                packageName = "org.offline"
                this.name = ComponentName(packageName, "$packageName.$name").className
                this.enabled = enabled
                this.exported = exported
                applicationInfo = ApplicationInfo().apply { this.enabled = appEnabled }
            }
        shadowOf(context.packageManager).addResolveInfoForIntent(
            Intent(RecognitionService.SERVICE_INTERFACE),
            ResolveInfo().apply { serviceInfo = service },
        )
        return service
    }
}
