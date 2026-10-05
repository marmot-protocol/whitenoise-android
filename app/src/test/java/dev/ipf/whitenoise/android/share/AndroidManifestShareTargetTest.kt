package dev.ipf.whitenoise.android.share

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
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
    fun mainActivityUsesSingleTaskForExternalEntryPointReuse() {
        val context = RuntimeEnvironment.getApplication()
        val activityInfo =
            context.packageManager.getActivityInfo(
                ComponentName(context, MainActivity::class.java),
                0,
            )

        assertEquals(ActivityInfo.LAUNCH_SINGLE_TASK, activityInfo.launchMode)
    }

    /** Parses the shipping shortcut resource to verify the Direct Share target class, category and MIME scope. */
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
        assertEquals(setOf("*/*"), shareTarget.mimeTypes)
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

    /** Queries actual manifest resolution for both share actions across document and visual MIME families. */
    @Test
    fun allSupportedFileFamiliesResolveExactlyOnceForBothActions() {
        val context = RuntimeEnvironment.getApplication()
        val types =
            listOf(
                "text/markdown",
                "text/x-markdown",
                "text/csv",
                "font/ttf",
                "model/gltf-binary",
                "chemical/x-pdb",
                "application/octet-stream",
                "text/plain",
                "image/png",
                "video/mp4",
                "audio/ogg",
            )
        for (action in listOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)) {
            for (mime in types) {
                val matches =
                    context.packageManager
                        .queryIntentActivities(
                            Intent(action).apply { type = mime },
                            PackageManager.MATCH_DEFAULT_ONLY,
                        ).count { it.activityInfo.name == MainActivity::class.java.name }
                assertEquals("$action $mime", 1, matches)
            }
        }
    }

    /** Keeps the external dispatcher in the test package rather than adding a production exported entry point. */
    @Test
    fun externalShareDispatcherReliesOnTestPackageDefaults() {
        val manifest =
            listOf(
                java.io.File("src/androidTest/AndroidManifest.xml"),
                java.io.File("app/src/androidTest/AndroidManifest.xml"),
            ).first { it.exists() }.readText()

        assertTrue(manifest.contains("ExternalShareDispatchActivity"))
        assertTrue(!manifest.contains("android:process="))
        assertTrue(!manifest.contains("android:taskAffinity="))
    }

    /** Checks provider exposure and both backup resource contracts for private staged content. */
    @Test
    fun privateIntakeCannotBeExportedOrIncludedInBackup() {
        val context = RuntimeEnvironment.getApplication()
        val info = context.packageManager.resolveContentProvider("${context.packageName}.private-share", 0)!!
        assertTrue(!info.exported && !info.grantUriPermissions)
        val res =
            listOf(java.io.File("src/main/res/xml"), java.io.File("app/src/main/res/xml"))
                .first { it.isDirectory }
        val factory =
            javax.xml.parsers.DocumentBuilderFactory
                .newInstance()
        val paths =
            factory
                .newDocumentBuilder()
                .parse(java.io.File(res, "file_paths.xml"))
                .documentElement.childNodes
        for (index in 0 until paths.length) {
            val node = paths.item(index)
            if (node.nodeType == org.w3c.dom.Node.ELEMENT_NODE) assertEquals("cache-path", node.nodeName)
        }
        for (name in listOf("backup_rules.xml", "data_extraction_rules.xml")) {
            val exclusions = factory.newDocumentBuilder().parse(java.io.File(res, name)).getElementsByTagName("exclude")
            assertTrue(
                (0 until exclusions.length).any {
                    val attrs = exclusions.item(it).attributes
                    attrs.getNamedItem("domain").nodeValue == "file" && attrs.getNamedItem("path").nodeValue == "."
                },
            )
        }
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
