package dev.ipf.whitenoise.android.updates

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AppSelfUpdateInstallerTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun packageInstallsAreAllowedBeforeAndroidO() {
        // The minSdk 30 manifest cannot load in an API 25 Robolectric sandbox, so override only this branch input.
        val originalSdk = Build.VERSION.SDK_INT
        shadowOf(context.packageManager).setCanRequestPackageInstalls(false)
        ReflectionHelpers.setStaticField(
            Build.VERSION::class.java,
            "SDK_INT",
            Build.VERSION_CODES.N_MR1,
        )

        try {
            assertTrue(AppSelfUpdateInstaller.canRequestPackageInstalls(context))
        } finally {
            ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", originalSdk)
        }
    }

    @Test
    fun packageInstallPermissionIsReadOnAndroidOAndLater() {
        val packageManager = shadowOf(context.packageManager)
        packageManager.setCanRequestPackageInstalls(false)
        assertFalse(AppSelfUpdateInstaller.canRequestPackageInstalls(context))

        packageManager.setCanRequestPackageInstalls(true)
        assertTrue(AppSelfUpdateInstaller.canRequestPackageInstalls(context))
    }

    @Test
    fun installPermissionSettingsTargetThisApplication() {
        val intent = AppSelfUpdateInstaller.installPermissionSettingsIntent(context)

        assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, intent.action)
        assertEquals(Uri.parse("package:${context.packageName}"), intent.data)
        assertTrue(intent.hasFlag(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    @Test
    fun launchInstallHonorsInstallerContract() {
        val apk = verifiedApkFile()
        val capturingContext = CapturingContext(context)

        try {
            assertTrue(AppSelfUpdateInstaller.launchInstall(capturingContext, apk))
            val intent = capturingContext.startedIntent
            assertEquals(Intent.ACTION_VIEW, intent.action)
            assertEquals("content", intent.data?.scheme)
            assertEquals("${context.packageName}.fileprovider", intent.data?.authority)
            assertEquals(AndroidAbi.APK_MIME, intent.type)
            assertTrue(intent.hasFlag(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            assertTrue(intent.hasFlag(Intent.FLAG_ACTIVITY_NEW_TASK))

            assertFalse(
                AppSelfUpdateInstaller.launchInstall(
                    UnavailableActivityContext(context),
                    apk,
                ),
            )
        } finally {
            apk.delete()
        }
    }

    private fun verifiedApkFile() =
        AppSelfUpdateStorage.apkFileForVersion(context, "2026.8.7").apply {
            writeText("apk")
        }

    private fun Intent.hasFlag(flag: Int): Boolean = flags and flag == flag

    private class CapturingContext(
        base: Context,
    ) : ContextWrapper(base) {
        lateinit var startedIntent: Intent
            private set

        override fun startActivity(intent: Intent) {
            startedIntent = intent
        }
    }

    private class UnavailableActivityContext(
        base: Context,
    ) : ContextWrapper(base) {
        override fun startActivity(intent: Intent) = throw ActivityNotFoundException("No package installer")
    }
}
