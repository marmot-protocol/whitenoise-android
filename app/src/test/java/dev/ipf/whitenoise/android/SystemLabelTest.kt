package dev.ipf.whitenoise.android

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SystemLabelTest {
    @Test
    fun applicationAndExportedActivitiesExposeTheVariantSystemLabel() {
        val context = RuntimeEnvironment.getApplication()
        val packageManager = context.packageManager
        val expectedLabel = expectedSystemLabel(context.packageName)
        val applicationInfo =
            packageManager.getApplicationInfo(
                context.packageName,
                PackageManager.GET_META_DATA,
            )

        assertEquals(expectedLabel, applicationInfo.loadLabel(packageManager).toString())

        val exportedActivities =
            packageManager
                .getPackageInfo(context.packageName, PackageManager.GET_ACTIVITIES)
                .activities
                .orEmpty()
                .filter { it.exported }
        assertTrue("The app must expose at least one activity entry surface", exportedActivities.isNotEmpty())
        exportedActivities.forEach { activityInfo ->
            assertEquals(
                "${activityInfo.name} must use the application system label",
                expectedLabel,
                activityInfo.loadLabel(packageManager).toString(),
            )
        }
    }

    @Test
    fun launcherShareAndDeepLinkEntriesResolveWithTheApplicationSystemLabel() {
        val context = RuntimeEnvironment.getApplication()
        val packageManager = context.packageManager
        val applicationLabel =
            packageManager
                .getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
                .loadLabel(packageManager)
                .toString()
        val entryIntents =
            listOf(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
                Intent(Intent.ACTION_SEND).apply { type = "text/plain" },
                Intent(Intent.ACTION_SEND_MULTIPLE).apply { type = "text/plain" },
                Intent(Intent.ACTION_VIEW, Uri.parse("${BuildConfig.WHITENOISE_DEEP_LINK_SCHEME}://profile/test"))
                    .addCategory(Intent.CATEGORY_DEFAULT)
                    .addCategory(Intent.CATEGORY_BROWSABLE),
                Intent(Intent.ACTION_VIEW, Uri.parse("marmot://profile/test"))
                    .addCategory(Intent.CATEGORY_DEFAULT)
                    .addCategory(Intent.CATEGORY_BROWSABLE),
            )

        entryIntents.forEach { intent ->
            val flags =
                if (intent.action == Intent.ACTION_MAIN) {
                    0
                } else {
                    PackageManager.MATCH_DEFAULT_ONLY
                }
            val matches =
                packageManager
                    .queryIntentActivities(intent, flags)
                    .filter { it.activityInfo.name == MainActivity::class.java.name }
            assertTrue("$intent must resolve to MainActivity", matches.isNotEmpty())
            matches.forEach { match ->
                assertEquals(
                    "$intent must resolve with the application system label",
                    applicationLabel,
                    match.activityInfo.loadLabel(packageManager).toString(),
                )
            }
        }
    }

    @Test
    fun coInstallablePackagesExposeDistinctSystemLabels() {
        val packageNames =
            listOf(
                "dev.ipf.whitenoise.android",
                "dev.ipf.whitenoise.android.dev",
                "dev.ipf.whitenoise.android.staging",
                "dev.ipf.whitenoise.android.preview",
                "dev.ipf.whitenoise.android.preview.pr42",
            )
        val labels = packageNames.map(::expectedSystemLabel)

        assertEquals(labels.size, labels.toSet().size)
    }

    private fun expectedSystemLabel(packageName: String): String =
        when (packageName) {
            "dev.ipf.whitenoise.android" -> "White Noise"
            "dev.ipf.whitenoise.android.dev" -> "White Noise Dev"
            "dev.ipf.whitenoise.android.staging" -> "White Noise Staging"
            "dev.ipf.whitenoise.android.preview" -> "White Noise PR"
            else -> {
                val isolatedPreview = ISOLATED_PREVIEW_PACKAGE.matchEntire(packageName)
                requireNotNull(isolatedPreview) { "Unknown installable White Noise package: $packageName" }
                "PR ${isolatedPreview.groupValues[1]} Isolated"
            }
        }

    private companion object {
        val ISOLATED_PREVIEW_PACKAGE = Regex("dev\\.ipf\\.whitenoise\\.android\\.preview\\.pr(.+)")
    }
}
