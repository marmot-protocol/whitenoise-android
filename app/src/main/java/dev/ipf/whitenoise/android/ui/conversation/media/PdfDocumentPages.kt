package dev.ipf.whitenoise.android.ui.conversation.media

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.media.MediaTrustDomain
import dev.ipf.whitenoise.android.media.sandbox.IsolatedMediaTransport
import dev.ipf.whitenoise.android.media.sandbox.PdfPagePolicy
import dev.ipf.whitenoise.android.media.sandbox.PdfResult
import dev.ipf.whitenoise.android.state.runCatchingCancellable
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseLazyColumn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** PDF pages rendered on demand under a page counter in the theme's content colour, with a retryable failure. */
@Composable
@Suppress("CyclomaticComplexMethod", "FunctionNaming", "LongMethod")
internal fun PdfDocumentPages(
    account: String,
    source: String,
    actions: TextAttachmentNativeActions,
    withPdfFile: suspend (suspend (File) -> Unit) -> Unit,
    onOpenExternal: (() -> Unit)?,
) {
    val context = LocalContext.current
    var retry by remember { mutableIntStateOf(0) }
    key(account, source, actions, retry) {
        val domain = remember { MediaTrustDomain.DocumentSession(account, source, UUID.randomUUID().toString()) }
        val transport =
            remember {
                IsolatedMediaTransport(
                    context.applicationContext,
                    "documents",
                    spares = 0,
                    liveLimit = 1,
                )
            }

        val mutex = remember { Mutex() }
        val cache =
            remember {
                object : LruCache<Int, Bitmap>(PdfPagePolicy.CACHE_BYTES) {
                    override fun sizeOf(
                        key: Int,
                        value: Bitmap,
                    ): Int = value.byteCount
                }
            }
        var active by remember { mutableStateOf(true) }
        var file by remember { mutableStateOf<File?>(null) }
        var inspection by remember { mutableStateOf<PdfResult?>(null) }
        var failed by remember { mutableStateOf(false) }
        DisposableEffect(transport) {
            onDispose {
                active = false
                transport.retireDomain(domain)
                transport.close()
                cache.evictAll()
            }
        }
        LaunchedEffect(transport) {
            suspend fun inspect(selected: File) {
                val info =
                    withContext(Dispatchers.IO) {
                        transport.pdf(selected, domain, 0, PdfPagePolicy.MAX_EDGE, false)
                    } ?: error("PDF unavailable")
                if (active && actions.isCurrent()) {
                    file = selected
                    inspection = info
                }
            }
            val result =
                runCatchingCancellable {
                    withPdfFile { selected ->
                        inspect(selected)
                        snapshotFlow { failed }.collect { if (it) error("PDF page unavailable") }
                    }
                }
            if (active && actions.isCurrent()) result.onFailure { failed = true }
        }
        if (failed) {
            DocumentViewerError(R.string.document_pdf_unavailable, { retry++ }, onOpenExternal)
        } else if (inspection == null) {
            DocumentLoading()
        } else {
            val info = inspection!!
            val count = info.pages
            val placeholderRatio = pageRatio(info.width, info.height)
            val list = rememberLazyListState()
            val current by remember(list) { derivedStateOf { list.firstVisibleItemIndex + 1 } }
            Text("$current / $count", modifier = Modifier.padding(horizontal = 16.dp))
            WhiteNoiseLazyColumn(
                state = list,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items((0 until count).toList(), key = { it }) { page ->
                    var bitmap by remember { mutableStateOf(cache.get(page)) }
                    LaunchedEffect(page, transport) {
                        if (bitmap != null || failed) return@LaunchedEffect
                        val result =
                            runCatchingCancellable {
                                mutex.withLock {
                                    ensureActive()
                                    if (!active || !actions.isCurrent() || failed) return@withLock null
                                    cache.get(page) ?: withContext(Dispatchers.IO) {
                                        transport.pdf(file!!, domain, page, PdfPagePolicy.MAX_EDGE, true)?.bitmap
                                    }
                                }
                            }.getOrNull()
                        if (active && actions.isCurrent()) {
                            if (result == null) {
                                failed = true
                            } else {
                                cache.put(page, result)
                                bitmap = result
                            }
                        }
                    }
                    val image = bitmap
                    if (image == null) {
                        Box(
                            Modifier.fillMaxWidth().aspectRatio(placeholderRatio),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator()
                        }
                    } else {
                        PdfPageImage(image, stringResource(R.string.document_pdf_page, page + 1, count))
                    }
                }
            }
        }
    }
}

private const val MIN_PAGE_RATIO = 0.25f
private const val MAX_PAGE_RATIO = 4f
private const val DEFAULT_PAGE_ASPECT = 1.414f

private fun pageRatio(
    width: Int,
    height: Int,
): Float =
    if (width > 0 && height > 0) {
        (width.toFloat() / height).coerceIn(
            MIN_PAGE_RATIO,
            MAX_PAGE_RATIO,
        )
    } else {
        1f / DEFAULT_PAGE_ASPECT
    }

/** Fit width by default; zoom transforms the capped pixels without another native render. */
@Composable
@Suppress("FunctionNaming")
internal fun PdfPageImage(
    bitmap: Bitmap,
    description: String,
) {
    var scale by remember(bitmap) { mutableFloatStateOf(1f) }
    var offset by remember(bitmap) { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val toggleLabel = stringResource(R.string.document_zoom_reset)
    val toggle = {
        scale = if (scale == 1f) 2f else 1f
        offset = Offset.Zero
    }
    val transform =
        rememberTransformableState { zoom, pan, _ ->
            scale = (scale * zoom).coerceIn(1f, 4f)
            val maxX = viewport.width * (scale - 1) / 2
            val maxY = viewport.height * (scale - 1) / 2
            offset = Offset((offset.x + pan.x).coerceIn(-maxX, maxX), (offset.y + pan.y).coerceIn(-maxY, maxY))
        }
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(pageRatio(bitmap.width, bitmap.height))
            // A hairline edge keeps white pages distinct on a light reader background.
            .border(Dp.Hairline, MaterialTheme.colorScheme.outlineVariant)
            .clipToBounds()
            .onSizeChanged { viewport = it }
            .pointerInput(bitmap) { detectTapGestures(onDoubleTap = { toggle() }) }
            .transformable(transform, canPan = { scale > 1f })
            .semantics {
                customActions =
                    listOf(
                        CustomAccessibilityAction(toggleLabel) {
                            toggle()
                            true
                        },
                    )
            },
    ) {
        Image(
            bitmap.asImageBitmap(),
            description,
            Modifier.fillMaxSize().graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            },
        )
    }
}
