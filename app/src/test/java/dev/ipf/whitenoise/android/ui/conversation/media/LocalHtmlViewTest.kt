package dev.ipf.whitenoise.android.ui.conversation.media

import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.v2.createComposeRule
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Exercises the actual AndroidView boundary; Chromium networking still requires device evidence. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@Suppress("DEPRECATION")
class LocalHtmlViewTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun capabilitiesNavigationAndDisposalFailClosed() {
        val document = runBlocking { LocalHtmlPolicy.sanitize("<a href=https://example.org/>Open</a>") }
        val shown = mutableStateOf(true)
        val links = mutableListOf<String>()
        var root: View? = null
        var unavailable = false
        compose.setContent {
            val view = LocalView.current
            SideEffect { root = view.rootView }
            if (shown.value) LocalHtmlView(document, onLink = links::add, onUnavailable = { unavailable = true })
        }
        val browser =
            compose.runOnIdle {
                assertFalse(unavailable)
                checkNotNull(findBrowser(checkNotNull(root)))
            }
        compose.runOnIdle {
            val settings = browser.settings
            assertFalse(settings.javaScriptEnabled)
            assertFalse(settings.domStorageEnabled)
            assertFalse(settings.allowFileAccess)
            assertFalse(settings.allowContentAccess)
            assertFalse(settings.allowFileAccessFromFileURLs)
            assertFalse(settings.allowUniversalAccessFromFileURLs)
            assertTrue(settings.blockNetworkLoads)
            assertTrue(settings.blockNetworkImage)
            assertEquals(WebSettings.LOAD_NO_CACHE, settings.cacheMode)
            assertFalse(settings.safeBrowsingEnabled)
            val shadow = shadowOf(browser)
            assertNull(shadow.lastLoadDataWithBaseURL.baseUrl)
            assertEquals(document.html, shadow.lastLoadDataWithBaseURL.data)
            val client = shadow.webViewClient
            for (url in listOf("https://example.org/", "file:///secret", "content://secret", "wn-document-link:0")) {
                val response = client.shouldInterceptRequest(browser, request(url))
                assertEquals(403, response!!.statusCode)
                assertEquals(-1, response.data.read())
            }
            assertTrue(client.shouldOverrideUrlLoading(browser, request("https://example.org/")))
            assertTrue(client.shouldOverrideUrlLoading(browser, request("wn-document-link:0", gesture = false)))
            assertTrue(client.shouldOverrideUrlLoading(browser, request("wn-document-link:0", redirect = true)))
            assertTrue(client.shouldOverrideUrlLoading(browser, request("wn-document-link:0", mainFrame = false)))
            assertTrue(client.shouldOverrideUrlLoading(browser, request("wn-document-link:9")))
            assertTrue(links.isEmpty())
            assertTrue(client.shouldOverrideUrlLoading(browser, request("wn-document-link:0")))
            assertEquals(listOf("https://example.org/"), links)
            assertFalse(browser.isLongClickable)
            assertNull(browser.startActionMode(null))
            assertNull(browser.startActionMode(null, android.view.ActionMode.TYPE_FLOATING))
            assertFalse(shadow.webChromeClient.onCreateWindow(browser, true, true, null))
            shown.value = false
        }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(shadowOf(browser).wasDestroyCalled()) }
    }

    private fun findBrowser(view: View): WebView? {
        if (view is WebView) return view
        return findChildBrowser(view)
    }

    private fun findChildBrowser(view: View): WebView? {
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) findBrowser(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun request(
        value: String,
        gesture: Boolean = true,
        redirect: Boolean = false,
        mainFrame: Boolean = true,
    ) = object : WebResourceRequest {
        override fun getUrl(): Uri = Uri.parse(value)

        override fun isForMainFrame(): Boolean = mainFrame

        override fun isRedirect(): Boolean = redirect

        override fun hasGesture(): Boolean = gesture

        override fun getMethod(): String = "GET"

        override fun getRequestHeaders(): Map<String, String> = emptyMap()
    }
}
