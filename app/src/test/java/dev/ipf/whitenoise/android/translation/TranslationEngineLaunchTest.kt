package dev.ipf.whitenoise.android.translation

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ResolveInfo
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
@Config(sdk = [30, 36])
class TranslationEngineLaunchTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** An installed engine opens for language packs on every route, even a version White Noise rejects. */
    @Test
    fun installedAppOpensEvenWithoutACompatibleTranslationService() {
        installLauncher()
        for (route in OfflineTranslatorRoute.entries) {
            assertTrue(openOfflineTranslator(context, route))

            val started = shadowOf(context as Application).nextStartedActivity
            assertEquals(Intent.ACTION_MAIN, started.action)
            assertEquals(OFFLINE_TRANSLATOR_PACKAGE, started.component?.packageName)
            assertNull(started.data)
            assertNull(started.extras)
        }
        assertTrue(offlineTranslatorInstalled(context))
    }

    /** While Zapstore offers only 0.8.2, an absent engine opens the publisher's reviewed 0.8.5 release instead. */
    @Test
    fun absentAppOpensTheCompatibleGitHubReleaseWhileZapstoreIsTooOld() {
        assertEquals(OfflineTranslatorRoute.GITHUB_RELEASE, offlineTranslatorRoute())
        assertFalse(offlineTranslatorInstalled(context))

        assertTrue(openOfflineTranslator(context))

        val started = shadowOf(context as Application).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals(GITHUB_RELEASE, started.dataString)
        assertNull(started.`package`)
        assertNull(started.extras)
        assertNull(shadowOf(context as Application).nextStartedActivity)
    }

    /** A Zapstore release passing discovery's signer and version checks makes the Zapstore app the first stop. */
    @Test
    fun acceptableZapstoreReleasePrefersTheZapstoreApp() {
        val reviewed = ReviewedStoreRelease("0.8.5", OFFLINE_TRANSLATOR_SIGNER)
        assertEquals(OfflineTranslatorRoute.ZAPSTORE, offlineTranslatorRoute(reviewed))
        val started = mutableListOf<Intent>()

        assertTrue(openOfflineTranslator(context, OfflineTranslatorRoute.ZAPSTORE) { started += it })

        val intent = started.single()
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("dev.zapstore.app", intent.`package`)
        assertEquals(ZAPSTORE_LISTING, intent.dataString)
        assertNull(intent.extras)
    }

    /** Only a release that discovery would accept can make Zapstore the preferred route. */
    @Test
    fun zapstoreRouteRequiresTheReviewedSignerAndVersion() {
        val rejected =
            listOf(
                ReviewedStoreRelease("0.8.2", OFFLINE_TRANSLATOR_SIGNER),
                ReviewedStoreRelease("0.8.6", OFFLINE_TRANSLATOR_SIGNER),
                ReviewedStoreRelease("0.8.5", "00".repeat(32)),
            )
        for (release in rejected) {
            assertEquals(OfflineTranslatorRoute.GITHUB_RELEASE, offlineTranslatorRoute(release))
        }
    }

    /** Without the Zapstore app the listing opens in a browser, then the release; no handler at all is reported. */
    @Test
    fun missingZapstoreAppOrBrowserFallsBackWithoutCrashing() {
        val attempts = mutableListOf<Intent>()
        val withoutStore = { intent: Intent ->
            attempts += intent
            if (intent.`package` != null) throw ActivityNotFoundException()
        }
        assertTrue(openOfflineTranslator(context, OfflineTranslatorRoute.ZAPSTORE, withoutStore))
        assertEquals(listOf("dev.zapstore.app", null), attempts.map { it.`package` })
        assertEquals(listOf(ZAPSTORE_LISTING, ZAPSTORE_LISTING), attempts.map { it.dataString })

        attempts.clear()
        val nothing = { intent: Intent ->
            attempts += intent
            throw ActivityNotFoundException()
        }
        assertFalse(openOfflineTranslator(context, OfflineTranslatorRoute.ZAPSTORE, nothing))
        assertEquals(listOf(ZAPSTORE_LISTING, ZAPSTORE_LISTING, GITHUB_RELEASE), attempts.map { it.dataString })
        assertFalse(openOfflineTranslator(context, OfflineTranslatorRoute.GITHUB_RELEASE, nothing))
    }

    /** Every outbound intent on every route names only a public page or package, with no message payload. */
    @Test
    fun outboundIntentsCarryNoMessageTextOrTranslationArguments() {
        val attempts = mutableListOf<Intent>()
        for (route in OfflineTranslatorRoute.entries) {
            openOfflineTranslator(context, route) {
                attempts += it
                throw ActivityNotFoundException()
            }
        }
        assertEquals(4, attempts.size)
        for (intent in attempts) {
            assertNull(intent.extras)
            assertNull(intent.clipData)
            assertNull(intent.type)
            assertTrue(intent.dataString.orEmpty().startsWith("https://"))
            assertNull(intent.data?.query)
        }
    }

    /** Registers a launcher activity for an engine release White Noise cannot translate with. */
    private fun installLauncher() {
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
    }

    private companion object {
        // Literal destinations, so an edited production constant cannot silently move either route.
        const val GITHUB_RELEASE = "https://github.com/DavidVentura/offline-translator/releases/tag/v0.8.5"
        const val ZAPSTORE_LISTING =
            "https://zapstore.dev/apps/" +
                "naddr1qq2kgetk9ejxzanfv3mzuarjv9h8xmrpw3hhyqgcwaehxw309aex2mrp0yh85ctswd6x7un99" +
                "ejx2aszyz8t5vkhgh85y2uywr2yafjyc2cjz0mwd0c37wpu2qkgzkkmnfvgcqcyqqq8uzcdudkdh"
    }
}
