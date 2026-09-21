package dev.ipf.whitenoise.android.ui.settings

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
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
class OfflineSpeechToTextRecommendationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun recommendationPresenceTracksProductionPackageInstallation() {
        assertFalse(offlineSpeechToTextInstalled(context))

        shadowOf(context.packageManager).installPackage(
            PackageInfo().apply {
                packageName = OFFLINE_SPEECH_TO_TEXT_PACKAGE
                applicationInfo =
                    ApplicationInfo().apply {
                        packageName = OFFLINE_SPEECH_TO_TEXT_PACKAGE
                    }
            },
        )

        assertTrue(offlineSpeechToTextInstalled(context))
    }
}
