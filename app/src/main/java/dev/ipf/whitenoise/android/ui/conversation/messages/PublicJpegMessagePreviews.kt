@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.ipf.marmotkit.MarkdownBlockFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MarkdownInlineFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.media.publicJpegDigest
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.isSignedInSigningAccount
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

private const val MAX_PREVIEW_DEPTH = 8
private const val MAX_PREVIEW_INLINES = 512
private val previewWidth = 280.dp
private val previewHeight = 240.dp

/** Account references may be reused; both the signing identity and runtime must still match. */
internal fun publicJpegPreviewOwnerCurrent(
    appState: WhiteNoiseAppState,
    accountRef: String,
    accountId: String,
    generation: Int,
): Boolean =
    appState.activeAccountRef == accountRef &&
        appState.runtimeGeneration == generation &&
        appState.accounts.any {
            it.label == accountRef && it.accountIdHex == accountId && it.isSignedInSigningAccount()
        }

/** Only top-level prose destinations; quotes/code/spoiler blocks never initiate automatic requests. */
@Suppress("CyclomaticComplexMethod") // Bounded typed-inline traversal; quotes/code/spoilers are never visited.
internal fun publicJpegPreviewUrls(document: MarkdownDocumentFfi?): List<String> {
    val urls = linkedSetOf<String>()
    var inspected = 0

    fun add(url: String) {
        if (urls.size < 2 && publicJpegDigest(url) != null) urls.add(url)
    }

    fun walk(
        nodes: List<MarkdownInlineFfi>,
        depth: Int,
    ) {
        if (depth > MAX_PREVIEW_DEPTH) return
        for (inline in nodes) {
            if (urls.size == 2 || inspected++ >= MAX_PREVIEW_INLINES) return
            when (inline) {
                is MarkdownInlineFfi.Autolink -> add(inline.url)
                is MarkdownInlineFfi.Image -> add(inline.dest)
                is MarkdownInlineFfi.Link -> add(inline.dest)
                is MarkdownInlineFfi.Emph -> walk(inline.children, depth + 1)
                is MarkdownInlineFfi.Strong -> walk(inline.children, depth + 1)
                is MarkdownInlineFfi.Strikethrough -> walk(inline.children, depth + 1)
                else -> Unit
            }
        }
    }
    document
        ?.blocks
        .orEmpty()
        .filterIsInstance<MarkdownBlockFfi.Paragraph>()
        .forEach { walk(it.inlines, 0) }
    return urls.toList()
}

/** Original Markdown links remain visible and usable whether this optional decoration succeeds or fails. */
@Suppress("LongMethod") // One lifecycle-owned acquisition and render pass.
@Composable
internal fun PublicJpegMessagePreviews(
    appState: WhiteNoiseAppState,
    accountRef: String?,
    messageId: String,
    document: MarkdownDocumentFfi?,
) {
    val preferences = appState.publicJpegPreviewPreferences
    val choices by preferences.state.collectAsStateWithLifecycle()
    val networkTypes by appState.publicJpegPreviewNetworkTypes.collectAsStateWithLifecycle()
    val connectivity by appState.connectivitySignals.collectAsStateWithLifecycle()
    val policy =
        listOf(
            networkTypes,
            connectivity.hasValidatedInternet,
            appState.mediaAutoDownloadMatrix,
            appState.automaticAttachmentDownloadsPaused(),
        )
    val accountId = appState.accounts.firstOrNull { it.label == accountRef && !it.signedOut }?.accountIdHex
    val enabled = accountId != null && choices[accountId] == true
    val urls = remember(document) { publicJpegPreviewUrls(document) }
    val generation = appState.runtimeGeneration
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var images by remember(accountRef, messageId, urls, enabled, generation, accountId, policy) {
        mutableStateOf<List<ImageBitmap>>(emptyList())
    }
    LaunchedEffect(accountRef, messageId, urls, enabled, generation, accountId, lifecycle, policy) {
        if (!enabled || accountRef == null || urls.isEmpty()) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val jobContext = currentCoroutineContext()
            val allowed = {
                jobContext.isActive &&
                    preferences.enabled(accountId) &&
                    publicJpegPreviewOwnerCurrent(appState, accountRef, accountId, generation) &&
                    appState.publicJpegPreviewNetworkAllowed()
            }
            if (allowed()) {
                val loaded =
                    urls.mapNotNull { url ->
                        appState.publicJpegPreviewLoader
                            .load(
                                owner = "$generation:$accountId",
                                url = url,
                                allowed = allowed,
                                fetch = { target, maxBytes ->
                                    appState.marmot().downloadProfileImage(target, maxBytes)
                                },
                            )?.asImageBitmap()
                    }
                if (allowed()) images = loaded
            }
        }
    }
    images.forEach { image -> PublicJpegPreviewImage(image) }
}

@Composable
internal fun PublicJpegPreviewImage(image: ImageBitmap) {
    Image(
        bitmap = image,
        contentDescription = stringResource(R.string.public_jpeg_preview_image),
        contentScale = ContentScale.Fit,
        modifier =
            Modifier
                .widthIn(max = previewWidth)
                .heightIn(max = previewHeight)
                .clip(MaterialTheme.shapes.medium)
                .testTag("message-public-jpeg-preview"),
    )
}
