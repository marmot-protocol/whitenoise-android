package dev.ipf.whitenoise.android.translation

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ResolveInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 36])
class TranslationEngineLaunchTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun installedAppOpensEvenWithoutACompatibleTranslationService() {
        val application =
            ApplicationInfo().apply {
                packageName = OFFLINE_TRANSLATOR_PACKAGE
                enabled = true
            }
        shadowOf(context.packageManager).installPackage(
            PackageInfo().apply {
                packageName = OFFLINE_TRANSLATOR_PACKAGE
                versionName = "unsupported"
                applicationInfo = application
            },
        )
        shadowOf(context.packageManager).addResolveInfoForIntent(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(OFFLINE_TRANSLATOR_PACKAGE),
            ResolveInfo().apply {
                activityInfo =
                    ActivityInfo().apply {
                        packageName = OFFLINE_TRANSLATOR_PACKAGE
                        name = "$OFFLINE_TRANSLATOR_PACKAGE.MainActivity"
                        enabled = true
                        exported = true
                        applicationInfo = application
                    }
            },
        )

        openOfflineTranslator(context)

        val started = shadowOf(context as Application).nextStartedActivity
        assertEquals(Intent.ACTION_MAIN, started.action)
        assertEquals(OFFLINE_TRANSLATOR_PACKAGE, started.component?.packageName)
        assertNull(started.data)
        assertNull(started.extras)
    }

    @Test
    fun absentAppOffersInstallationWithoutSendingMessageText() {
        openOfflineTranslator(context)

        val started = shadowOf(context as Application).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals(OFFLINE_TRANSLATOR_INSTALL, started.dataString)
        assertNull(started.extras)
    }
}
