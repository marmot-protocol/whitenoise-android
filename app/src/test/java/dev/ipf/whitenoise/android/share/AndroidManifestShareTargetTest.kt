package dev.ipf.whitenoise.android.share

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.XmlResourceParser
import dev.ipf.whitenoise.android.MainActivity
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.notifications.CONVERSATION_SHARE_TARGET_CATEGORY
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AndroidManifestShareTargetTest {
    @Test
    fun mainActivityDeclaresShortcutsMetadataAndShareTarget() {
        val context = RuntimeEnvironment.getApplication()
        val activityInfo =
            context.packageManager.getActivityInfo(
                ComponentName(context, MainActivity::class.java),
                PackageManager.GET_META_DATA,
            )
        val shortcutsResId = activityInfo.metaData?.getInt("android.app.shortcuts") ?: 0
        assertNotEquals(0, shortcutsResId)
        assertEquals(R.xml.shortcuts, shortcutsResId)

        val shareTarget = parseShareTarget(context.resources.getXml(shortcutsResId))
        assertEquals(MainActivity::class.java.name, shareTarget.targetClass)
        assertTrue(
            "share-target must declare the Direct Share category",
            shareTarget.categories.contains(CONVERSATION_SHARE_TARGET_CATEGORY),
        )
        assertTrue(shareTarget.mimeTypes.contains("text/plain"))
        assertTrue(shareTarget.mimeTypes.contains("image/*"))
        assertTrue(shareTarget.mimeTypes.contains("video/*"))
        assertTrue(shareTarget.mimeTypes.contains("application/*"))
        assertTrue(shareTarget.mimeTypes.contains("audio/*"))
    }

    @Test
    fun sendAndSendMultipleTextPlainResolveToMainActivity() {
        val context = RuntimeEnvironment.getApplication()
        val pm = context.packageManager
        val mainActivityName = MainActivity::class.java.name
        val send =
            pm.queryIntentActivities(
                Intent(Intent.ACTION_SEND).apply { type = "text/plain" },
                PackageManager.MATCH_DEFAULT_ONLY,
            )
        val sendMultiple =
            pm.queryIntentActivities(
                Intent(Intent.ACTION_SEND_MULTIPLE).apply { type = "text/plain" },
                PackageManager.MATCH_DEFAULT_ONLY,
            )
        assertTrue(send.any { it.activityInfo.name == mainActivityName })
        assertTrue(sendMultiple.any { it.activityInfo.name == mainActivityName })
    }

    @Test
    fun sendFiltersAvoidCatchAllMimeType() {
        val manifest =
            listOf(
                java.io.File("src/main/AndroidManifest.xml"),
                java.io.File("app/src/main/AndroidManifest.xml"),
            ).first { it.exists() }.readText()
        assertTrue(!manifest.contains("android:mimeType=\"*/*\""))
    }

    private data class ParsedShareTarget(
        val targetClass: String?,
        val categories: Set<String>,
        val mimeTypes: Set<String>,
    )

    private fun parseShareTarget(parser: XmlResourceParser): ParsedShareTarget {
        var targetClass: String? = null
        val categories = mutableSetOf<String>()
        val mimeTypes = mutableSetOf<String>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "share-target" ->
                        targetClass =
                            parser.getAttributeValue(ANDROID_NS, "targetClass")
                                ?: parser.getAttributeValue(null, "targetClass")
                    "category" ->
                        categories +=
                            parser.getAttributeValue(ANDROID_NS, "name")
                                ?: parser.getAttributeValue(null, "name").orEmpty()
                    "data" ->
                        mimeTypes +=
                            parser.getAttributeValue(ANDROID_NS, "mimeType")
                                ?: parser.getAttributeValue(null, "mimeType").orEmpty()
                }
            }
            event = parser.next()
        }
        return ParsedShareTarget(targetClass, categories, mimeTypes)
    }

    private companion object {
        private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    }
}
