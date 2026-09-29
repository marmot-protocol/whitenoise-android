package dev.ipf.whitenoise.android.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import androidx.test.core.app.ApplicationProvider
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

    @Test
    fun listingOpensInZapstoreWhenItCanHandleTheLink() {
        val started = mutableListOf<Intent>()

        assertTrue(openOfflineSpeechToTextListing(context) { started += it })
        assertEquals(1, started.size)
        assertEquals(Intent.ACTION_VIEW, started.single().action)
        assertEquals(OSTT_ZAPSTORE_URL, started.single().dataString)
        assertEquals(ZAPSTORE_PACKAGE, started.single().`package`)
    }

    @Test
    fun listingFallsBackToTheSameWebPageWhenZapstoreCannotHandleIt() {
        val started = mutableListOf<Intent>()

        assertTrue(
            openOfflineSpeechToTextListing(context) { intent ->
                started += intent
                if (started.size == 1) throw ActivityNotFoundException()
            },
        )
        assertEquals(2, started.size)
        assertEquals(ZAPSTORE_PACKAGE, started[0].`package`)
        assertNull(started[1].`package`)
        assertEquals(OSTT_ZAPSTORE_URL, started[1].dataString)
    }

    @Test
    fun listingReportsFailureWhenNeitherZapstoreNorBrowserCanOpenIt() {
        var attempts = 0

        assertFalse(
            openOfflineSpeechToTextListing(context) {
                attempts++
                throw ActivityNotFoundException()
            },
        )
        assertEquals(2, attempts)
    }
}
