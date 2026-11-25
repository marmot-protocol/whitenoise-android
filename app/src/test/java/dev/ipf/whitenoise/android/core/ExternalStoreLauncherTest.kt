package dev.ipf.whitenoise.android.core

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.state.openAppUpdateZapstoreListing
import dev.ipf.whitenoise.android.translation.OFFLINE_TRANSLATOR_ZAPSTORE_URL
import dev.ipf.whitenoise.android.translation.OfflineTranslatorRoute
import dev.ipf.whitenoise.android.translation.offlineTranslatorReleaseUrl
import dev.ipf.whitenoise.android.translation.openOfflineTranslator
import dev.ipf.whitenoise.android.ui.settings.OSTT_ZAPSTORE_URL
import dev.ipf.whitenoise.android.ui.settings.openOfflineSpeechToTextListing
import dev.ipf.whitenoise.android.updates.AppUpdateConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Verifies every external store listing route starts both store and browser intents in a new task. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExternalStoreLauncherTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** View intents for store listings always carry `FLAG_ACTIVITY_NEW_TASK`, with or without a package. */
    @Test
    fun externalStoreViewIntentAlwaysSetsNewTaskFlag() {
        val storeIntent = externalStoreViewIntent(TEST_LISTING_URL, packageName = ZAPSTORE_PACKAGE)
        assertEquals(Intent.ACTION_VIEW, storeIntent.action)
        assertEquals(TEST_LISTING_URL, storeIntent.dataString)
        assertEquals(ZAPSTORE_PACKAGE, storeIntent.`package`)
        assertNewTaskFlag(storeIntent)

        val browserIntent = externalStoreViewIntent(TEST_LISTING_URL)
        assertEquals(Intent.ACTION_VIEW, browserIntent.action)
        assertEquals(TEST_LISTING_URL, browserIntent.dataString)
        assertNull(browserIntent.`package`)
        assertNewTaskFlag(browserIntent)
    }

    /** Candidate lists preserve store -> browser -> fallback ordering and attach `FLAG_ACTIVITY_NEW_TASK` to all. */
    @Test
    fun externalStoreListingIntentsOrdersStoreBrowserAndFallbacksWithNewTask() {
        val candidates =
            externalStoreListingIntents(
                listingUrl = TEST_LISTING_URL,
                preferPackage = ZAPSTORE_PACKAGE,
                fallbackUrls = listOf(TEST_FALLBACK_URL),
            )
        assertEquals(listOf(ZAPSTORE_PACKAGE, null, null), candidates.map { it.`package` })
        assertEquals(
            listOf(TEST_LISTING_URL, TEST_LISTING_URL, TEST_FALLBACK_URL),
            candidates.map { it.dataString },
        )
        candidates.forEach(::assertNewTaskFlag)

        val browserOnly =
            externalStoreListingIntents(
                listingUrl = TEST_FALLBACK_URL,
                preferPackage = null,
            )
        assertEquals(1, browserOnly.size)
        assertNull(browserOnly.single().`package`)
        assertEquals(TEST_FALLBACK_URL, browserOnly.single().dataString)
        assertNewTaskFlag(browserOnly.single())
    }

    /** Dictation, Offline Translator, and app-update store routes all set `FLAG_ACTIVITY_NEW_TASK` on every attempt. */
    @Test
    fun dictationTranslatorAndAppUpdateRoutesAllSetNewTaskOnStoreAndBrowserIntents() {
        val dictationIntents = collectAllAttemptedIntents { openOfflineSpeechToTextListing(context, it) }
        assertEquals(listOf(ZAPSTORE_PACKAGE, null), dictationIntents.map { it.`package` })
        assertEquals(listOf(OSTT_ZAPSTORE_URL, OSTT_ZAPSTORE_URL), dictationIntents.map { it.dataString })
        dictationIntents.forEach(::assertNewTaskFlag)

        val translatorZapstoreIntents =
            collectAllAttemptedIntents { openOfflineTranslator(context, OfflineTranslatorRoute.ZAPSTORE, it) }
        assertEquals(listOf(ZAPSTORE_PACKAGE, null, null), translatorZapstoreIntents.map { it.`package` })
        assertEquals(
            listOf(OFFLINE_TRANSLATOR_ZAPSTORE_URL, OFFLINE_TRANSLATOR_ZAPSTORE_URL, offlineTranslatorReleaseUrl),
            translatorZapstoreIntents.map { it.dataString },
        )
        translatorZapstoreIntents.forEach(::assertNewTaskFlag)

        val translatorReleaseIntents =
            collectAllAttemptedIntents { openOfflineTranslator(context, OfflineTranslatorRoute.GITHUB_RELEASE, it) }
        assertEquals(listOf(null), translatorReleaseIntents.map { it.`package` })
        assertEquals(listOf(offlineTranslatorReleaseUrl), translatorReleaseIntents.map { it.dataString })
        translatorReleaseIntents.forEach(::assertNewTaskFlag)

        val appUpdateIntents = collectAllAttemptedIntents { openAppUpdateZapstoreListing(context, it) }
        assertEquals(listOf(null), appUpdateIntents.map { it.`package` })
        assertEquals(listOf(AppUpdateConstants.ZAPSTORE_LISTING_URL), appUpdateIntents.map { it.dataString })
        appUpdateIntents.forEach(::assertNewTaskFlag)
    }

    /** App-update listing keeps the user's link-handler choice: one implicit new-task intent, false when unhandled. */
    @Test
    fun appUpdateRouteUsesOneImplicitNewTaskIntentAndReportsMissingHandlers() {
        val started = mutableListOf<Intent>()
        assertTrue(openAppUpdateZapstoreListing(context) { started += it })
        assertEquals(null, started.single().`package`)
        assertEquals(AppUpdateConstants.ZAPSTORE_LISTING_URL, started.single().dataString)
        assertNewTaskFlag(started.single())

        assertFalse(openAppUpdateZapstoreListing(context) { throw ActivityNotFoundException() })
    }

    /** Invokes [launch] with a failing starter so every fallback candidate intent is recorded in order. */
    private fun collectAllAttemptedIntents(launch: ((Intent) -> Unit) -> Boolean): List<Intent> {
        val attempts = mutableListOf<Intent>()
        assertFalse(
            launch { intent ->
                attempts += intent
                throw ActivityNotFoundException()
            },
        )
        return attempts
    }

    /** Asserts that [intent] is an `ACTION_VIEW` intent carrying `Intent.FLAG_ACTIVITY_NEW_TASK`. */
    private fun assertNewTaskFlag(intent: Intent) {
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertTrue(
            "expected FLAG_ACTIVITY_NEW_TASK on ${intent.dataString} (package=${intent.`package`})",
            (intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK) != 0,
        )
    }

    private companion object {
        const val TEST_LISTING_URL = "https://zapstore.dev/apps/dev.ipf.whitenoise.android"
        const val TEST_FALLBACK_URL = "https://github.com/DavidVentura/offline-translator/releases/tag/v0.8.5"
    }
}
