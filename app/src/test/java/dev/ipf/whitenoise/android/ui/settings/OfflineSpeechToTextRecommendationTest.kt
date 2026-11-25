package dev.ipf.whitenoise.android.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.audio.OFFLINE_SPEECH_TO_TEXT_PACKAGE
import dev.ipf.whitenoise.android.core.ZAPSTORE_PACKAGE
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

    /** Opening the dictation provider listing targets Zapstore in a new task when available. */
    @Test
    fun listingOpensInZapstoreWhenItCanHandleTheLink() {
        val started = mutableListOf<Intent>()

        assertTrue(openOfflineSpeechToTextListing(context) { started += it })
        assertEquals(1, started.size)
        val intent = started.single()
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(OSTT_ZAPSTORE_URL, intent.dataString)
        assertEquals(ZAPSTORE_PACKAGE, intent.`package`)
        assertTrue((intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK) != 0)
    }

    /** Falling back to the web listing keeps both Zapstore and browser intents in their own tasks. */
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
        assertTrue((started[0].flags and Intent.FLAG_ACTIVITY_NEW_TASK) != 0)
        assertEquals(Intent.ACTION_VIEW, started[1].action)
        assertNull(started[1].`package`)
        assertEquals(OSTT_ZAPSTORE_URL, started[1].dataString)
        assertTrue((started[1].flags and Intent.FLAG_ACTIVITY_NEW_TASK) != 0)
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
