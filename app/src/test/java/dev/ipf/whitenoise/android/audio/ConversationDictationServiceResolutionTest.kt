package dev.ipf.whitenoise.android.audio

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.provider.Settings
import android.speech.RecognitionService
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.state.ConversationDictationPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    private val ServiceInfo.componentName: ComponentName
        get() = ComponentName(packageName, name)

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
    fun staleAndroidSettingFallsThroughToTheSoleInstalledProvider() {
        installService()
        Settings.Secure.putString(
            context.contentResolver,
            VOICE_RECOGNITION_SERVICE_SETTING,
            "org.missing/.Recognition",
        )
        assertTrue(platform.recognitionConfigured())
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

    @Test
    fun explicitOverrideChoosesOneOfMultipleInstalledServices() {
        installService(packageName = "org.first")
        val selected = installService(packageName = "org.second")
        ConversationDictationPreferences(context).setRecognitionServiceOverride(selected.componentName)

        assertTrue(platform.recognitionConfigured())
        assertEquals("org.second", platform.speechProviderPackage())
    }

    @Test
    fun removedExplicitOverrideClearsThePreferenceWithoutChoosingAnotherProvider() {
        val selected = installService(packageName = "org.first")
        installService(packageName = "org.second")
        val preferences = ConversationDictationPreferences(context)
        preferences.setRecognitionServiceOverride(selected.componentName)
        selected.enabled = false

        assertFalse(platform.recognitionConfigured())
        assertNull(preferences.current().recognitionServiceOverride)
    }

    @Test
    fun preferenceChangesDoNotReplaceTheProviderPinnedForTheCurrentSession() {
        val first = installService(packageName = "org.first")
        val second = installService(packageName = "org.second")
        val preferences = ConversationDictationPreferences(context)
        preferences.setRecognitionServiceOverride(first.componentName)
        assertTrue(platform.recognitionConfigured())

        preferences.setRecognitionServiceOverride(second.componentName)
        first.enabled = false

        assertFalse(platform.recognitionAvailable())
        assertEquals("org.first", platform.speechProviderPackage())
    }

    @Test
    fun androidSelectionTakesPriorityOverSavedWhiteNoiseSelection() {
        val first = installService(packageName = "org.first")
        val second = installService(packageName = "org.second")
        ConversationDictationPreferences(context).setRecognitionServiceOverride(second.componentName)
        Settings.Secure.putString(
            context.contentResolver,
            VOICE_RECOGNITION_SERVICE_SETTING,
            first.componentName.flattenToString(),
        )
        assertTrue(platform.recognitionConfigured())
        assertEquals("org.first", platform.speechProviderPackage())
    }

    @Test
    fun installedVersionChangeInvalidatesSavedAndPinnedIdentity() {
        val first = installService(packageName = "org.first")
        installService(packageName = "org.second")
        val preferences = ConversationDictationPreferences(context)
        preferences.setRecognitionServiceOverride(first.componentName)
        assertTrue(platform.recognitionConfigured())
        val updated = context.packageManager.getPackageInfo("org.first", 0)
        updated.longVersionCode = 8
        shadowOf(context.packageManager).installPackage(updated)
        assertFalse(platform.recognitionAvailable())
        assertFalse(platform.recognitionConfigured())
        assertNull(preferences.current().providerSelection)
    }

    @Test
    fun componentDisableOverrideInvalidatesSavedSelection() {
        val first = installService()
        ConversationDictationPreferences(context).setRecognitionServiceOverride(first.componentName)
        context.packageManager.setComponentEnabledSetting(
            first.componentName,
            android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            android.content.pm.PackageManager.DONT_KILL_APP,
        )
        assertFalse(platform.prepareProviderSelection())
        assertNull(ConversationDictationPreferences(context).current().providerSelection)
    }

    @Test
    fun packageDisableAndUninstallInvalidatePinnedIdentity() {
        val first = installService()
        assertTrue(platform.prepareProviderSelection())
        context.packageManager.setApplicationEnabledSetting(
            first.packageName,
            android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            android.content.pm.PackageManager.DONT_KILL_APP,
        )
        assertFalse(platform.recognitionAvailable())
        context.packageManager.setApplicationEnabledSetting(
            first.packageName,
            android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
            android.content.pm.PackageManager.DONT_KILL_APP,
        )
        assertTrue(platform.prepareProviderSelection())
        shadowOf(context.packageManager).removePackage(first.packageName)
        assertFalse(platform.recognitionAvailable())
    }

    @Test
    fun testOnlyAndServicesWithoutBindingPermissionAreNotOffered() {
        installService(name = "TestOnly").applicationInfo.flags = ApplicationInfo.FLAG_TEST_ONLY
        installService(name = "NoBindingPermission").permission = null
        assertFalse(platform.prepareProviderSelection())
    }

    @Test
    fun activityOnlySelectionRoutesExplicitlyAndCannotSwitchWhenItDisappears() {
        installService(packageName = "org.window", enabled = false)
        val activity =
            android.content.pm.ActivityInfo().apply {
                packageName = "org.window"
                name = "org.window.Window"
                enabled = true
                exported = true
                applicationInfo = context.packageManager.getApplicationInfo(packageName, 0)
            }
        shadowOf(context.packageManager).addResolveInfoForIntent(
            conversationDictationRecognitionActivityIntent(),
            ResolveInfo().apply { activityInfo = activity },
        )
        assertTrue(platform.prepareProviderSelection())
        assertFalse(platform.recognitionAvailable())
        assertEquals(ConversationDictationCallerAudioRequirement.Unsupported, platform.callerAudioRequirement())
        assertEquals(ComponentName("org.window", "org.window.Window"), platform.providerActivityIntent().component)
        activity.enabled = false
        installService(packageName = "org.replacement")
        assertFalse(platform.recognitionActivityAvailable())
        assertEquals("org.window", platform.speechProviderPackage())
    }

    @Suppress("DEPRECATION")
    private fun installService(
        name: String = "Recognition",
        packageName: String = "org.offline",
        enabled: Boolean = true,
        exported: Boolean = true,
        appEnabled: Boolean = true,
    ): ServiceInfo {
        shadowOf(context.packageManager).installPackage(
            PackageInfo().apply {
                this.packageName = packageName
                versionCode = 7
                applicationInfo =
                    ApplicationInfo().apply {
                        this.packageName = packageName
                        this.enabled = appEnabled
                    }
            },
        )
        val service =
            ServiceInfo().apply {
                this.packageName = packageName
                this.name = ComponentName(packageName, "$packageName.$name").className
                this.enabled = enabled
                this.exported = exported
                permission = "android.permission.BIND_SPEECH_RECOGNITION_SERVICE"
                applicationInfo =
                    ApplicationInfo().apply {
                        this.enabled = appEnabled
                        this.packageName = packageName
                    }
            }
        shadowOf(context.packageManager).addResolveInfoForIntent(
            Intent(RecognitionService.SERVICE_INTERFACE),
            ResolveInfo().apply { serviceInfo = service },
        )
        return service
    }
}
