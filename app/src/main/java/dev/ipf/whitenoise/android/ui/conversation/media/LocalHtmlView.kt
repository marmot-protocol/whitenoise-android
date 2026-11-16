package dev.ipf.whitenoise.android.ui.conversation.media

import android.graphics.Color
import android.net.http.SslError
import android.os.Message
import android.view.ActionMode
import android.webkit.ClientCertRequest
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.HttpAuthHandler
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import java.io.ByteArrayInputStream

/** The only browser entry point: receives a fresh-tree projection, never sender HTML. */
@Suppress("DEPRECATION", "FunctionNaming")
@Composable
internal fun LocalHtmlView(
    document: LocalHtmlDocument,
    modifier: Modifier = Modifier,
    onLink: (String) -> Unit,
    onUnavailable: () -> Unit,
) {
    val context = LocalContext.current
    val fontScale = LocalDensity.current.fontScale
    val view =
        remember(document, fontScale) {
            var created: WebView? = null
            runCatching {
                object : WebView(context) {
                    override fun startActionMode(callback: ActionMode.Callback?): ActionMode? = null

                    override fun startActionMode(
                        callback: ActionMode.Callback?,
                        type: Int,
                    ): ActionMode? = null
                }.also { created = it }.apply {
                    setBackgroundColor(Color.BLACK)
                    settings.apply {
                        javaScriptEnabled = false
                        javaScriptCanOpenWindowsAutomatically = false
                        domStorageEnabled = false
                        databaseEnabled = false
                        allowFileAccess = false
                        allowContentAccess = false
                        allowFileAccessFromFileURLs = false
                        allowUniversalAccessFromFileURLs = false
                        blockNetworkLoads = true
                        blockNetworkImage = true
                        loadsImagesAutomatically = false
                        cacheMode = WebSettings.LOAD_NO_CACHE
                        safeBrowsingEnabled = false
                        setGeolocationEnabled(false)
                        setSupportMultipleWindows(false)
                        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        mediaPlaybackRequiresUserGesture = true
                        builtInZoomControls = true
                        displayZoomControls = false
                        textZoom = (fontScale * 100).toInt()
                    }
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                    // Suppress browser selection/action mode, whose Open actions can bypass confirmation.
                    setOnLongClickListener { true }
                    isLongClickable = false
                    setDownloadListener { _, _, _, _, _ -> }
                    webChromeClient =
                        object : WebChromeClient() {
                            override fun onPermissionRequest(request: PermissionRequest) = request.deny()

                            override fun onGeolocationPermissionsShowPrompt(
                                origin: String,
                                callback: GeolocationPermissions.Callback,
                            ) = callback.invoke(origin, false, false)

                            override fun onCreateWindow(
                                view: WebView?,
                                isDialog: Boolean,
                                isUserGesture: Boolean,
                                resultMsg: Message?,
                            ): Boolean = false

                            override fun onShowFileChooser(
                                webView: WebView?,
                                filePathCallback: android.webkit.ValueCallback<Array<android.net.Uri>>?,
                                fileChooserParams: FileChooserParams?,
                            ): Boolean {
                                filePathCallback?.onReceiveValue(null)
                                return true
                            }
                        }
                    webViewClient =
                        object : WebViewClient() {
                            private fun denied() =
                                WebResourceResponse(
                                    "text/plain",
                                    "UTF-8",
                                    403,
                                    "Forbidden",
                                    emptyMap(),
                                    ByteArrayInputStream(byteArrayOf()),
                                )

                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: WebResourceRequest?,
                            ): WebResourceResponse = denied()

                            override fun shouldInterceptRequest(
                                view: WebView?,
                                url: String?,
                            ): WebResourceResponse = denied()

                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest,
                            ): Boolean {
                                if (request.isForMainFrame && request.hasGesture() && !request.isRedirect) {
                                    document.destination(request.url.toString())?.let(onLink)
                                }
                                return true
                            }

                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                url: String?,
                            ): Boolean = true

                            override fun onReceivedSslError(
                                view: WebView?,
                                handler: SslErrorHandler,
                                error: SslError?,
                            ) = handler.cancel()

                            override fun onReceivedHttpAuthRequest(
                                view: WebView?,
                                handler: HttpAuthHandler,
                                host: String?,
                                realm: String?,
                            ) = handler.cancel()

                            override fun onReceivedClientCertRequest(
                                view: WebView?,
                                request: ClientCertRequest,
                            ) = request.cancel()

                            override fun onRenderProcessGone(
                                view: WebView?,
                                detail: RenderProcessGoneDetail?,
                            ): Boolean {
                                onUnavailable()
                                return true
                            }
                        }
                    loadDataWithBaseURL(null, document.html, "text/html", "UTF-8", null)
                }
            }.onFailure { created?.destroy() }.getOrNull()
        }
    DisposableEffect(view) {
        if (view == null) onUnavailable()
        onDispose {
            view?.apply {
                stopLoading()
                removeAllViews()
                destroy()
            }
        }
    }
    if (view != null) AndroidView(factory = { view }, modifier = modifier)
}
