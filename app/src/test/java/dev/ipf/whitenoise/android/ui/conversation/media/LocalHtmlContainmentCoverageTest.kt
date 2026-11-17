package dev.ipf.whitenoise.android.ui.conversation.media

import dev.ipf.whitenoise.android.appModuleFile
import dev.ipf.whitenoise.android.appModuleSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the sole WebView construction/loading boundary and its fail-closed capabilities. */
class LocalHtmlContainmentCoverageTest {
    @Test fun senderMarkupCannotReachAnotherBrowserEntryPoint() {
        val root = appModuleFile("src/main/java")
        val loaders =
            root
                .walkTopDown()
                .filter {
                    it.extension == "kt" && "loadDataWithBaseURL(" in it.readText()
                }.toList()
        assertEquals(listOf("LocalHtmlView.kt"), loaders.map { it.name })
        val source = loaders.single().readText()
        for (guard in listOf(
            "document: LocalHtmlDocument",
            "javaScriptEnabled = false",
            "domStorageEnabled = false",
            "allowFileAccess = false",
            "allowContentAccess = false",
            "blockNetworkLoads = true",
            "blockNetworkImage = true",
            "safeBrowsingEnabled = false",
            "WebSettings.LOAD_NO_CACHE",
            "403",
            "request.hasGesture()",
            "document.destination(",
            "request.deny()",
            "destroy()",
            "setOnLongClickListener { true }",
        )) {
            assertTrue(
                guard,
                guard in source,
            )
        }
        assertTrue("loadDataWithBaseURL(null, document.html" in source)
        val owner =
            appModuleSource(
                (
                    "src/main/java/dev/ipf/whitenoise/android/ui/conversation/media/DocumentAttachm" +
                        "entViewer.kt"
                ),
            )
        assertTrue("LocalHtmlPolicy.sanitize(decoded.text)" in owner)
        assertTrue("if (actions.isCurrent()) openMarkdownLink" in owner)
        val manifest = appModuleSource("src/main/AndroidManifest.xml").replace(Regex("\\s+"), " ")
        assertTrue(
            "android:name=\"android.webkit.WebView.MetricsOptOut\" android:value=\"true\"" in manifest,
        )
    }
}
