package dev.ipf.whitenoise.android.ui.conversation.media

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.runCatchingCancellable
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseDropdownMenu
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseMenuItem
import dev.ipf.whitenoise.android.ui.common.fadingVerticalScroll
import dev.ipf.whitenoise.android.ui.conversation.PlaybackDialog
import dev.ipf.whitenoise.android.ui.openMarkdownLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Shows a PDF or HTML attachment under the shared viewer chrome. The reader body below the chrome keeps its black
 * canvas, and a sanitized HTML page renders exactly as the policy wrote it.
 */
@Composable
@Suppress("FunctionNaming", "LongMethod", "CyclomaticComplexMethod") // Optional exports keep both readers explicit.
internal fun DocumentAttachmentViewer(
    kind: DocumentAttachmentKind,
    name: String,
    account: String,
    source: String,
    actions: TextAttachmentNativeActions,
    loadBytes: suspend () -> ByteArray,
    withPdfFile: suspend (suspend (File) -> Unit) -> Unit,
    onOpenExternal: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    LaunchedEffect(actions) {
        snapshotFlow { actions.isCurrent() }.collect { if (!it) onDismiss() }
    }
    val scope = rememberCoroutineScope()
    var original by remember(actions) { mutableStateOf<String?>(null) }
    var html by remember(actions) { mutableStateOf<LocalHtmlDocument?>(null) }
    var error by remember(actions) { mutableStateOf(false) }
    var retryable by remember(actions) { mutableStateOf(false) }
    var sourceTab by remember(actions) { mutableStateOf(false) }
    var pendingLink by remember(actions) { mutableStateOf<String?>(null) }
    var generation by remember(actions) { mutableIntStateOf(0) }
    val context = LocalContext.current
    LaunchedEffect(actions, generation) {
        if (kind != DocumentAttachmentKind.Html) return@LaunchedEffect
        error = false
        retryable = false
        html = null
        original = null
        // Only a failed load can succeed on retry; size, encoding and sanitizer limits are deterministic.
        val bytes = runCatchingCancellable { loadBytes() }
        val decoded =
            bytes.getOrNull()?.let {
                withContext(Dispatchers.Default) { decodeTextAttachment(it) } as? TextAttachmentDecodeResult.Success
            }
        if (!actions.isCurrent()) return@LaunchedEffect
        if (decoded == null) {
            error = true
            retryable = bytes.isFailure
            return@LaunchedEffect
        }
        // Source stays readable even when the sender markup exceeds the sanitizer's structure limits.
        original = decoded.text
        val sanitized =
            runCatchingCancellable {
                withContext(Dispatchers.Default) { LocalHtmlPolicy.sanitize(decoded.text) }
            }
        if (actions.isCurrent()) sanitized.onSuccess { html = it }.onFailure { error = true }
    }
    PlaybackDialog(
        onDismissRequest = onDismiss,
        properties =
            DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
            ),
    ) {
        DocumentViewerFrame(
            name,
            kind,
            sourceTab,
            actions.sharing,
            onDismiss,
            onShare = if (actions.canShare) ({ scope.launch { actions.share() } }) else null,
            onSave = if (actions.canSave) ({ scope.launch { actions.save() } }) else null,
            onOpenExternal = onOpenExternal,
            onSourceTab = { sourceTab = it },
        ) {
            if (kind == DocumentAttachmentKind.Pdf) {
                PdfDocumentPages(account, source, actions, withPdfFile, onOpenExternal)
            } else if (sourceTab && original != null) {
                DocumentSource(original!!, name)
            } else if (error) {
                DocumentViewerError(
                    R.string.document_html_unavailable,
                    if (retryable) ({ generation++ }) else null,
                    onOpenExternal,
                )
            } else {
                val document = html
                if (document == null) {
                    DocumentLoading()
                } else {
                    if (document.omitted) {
                        Text(
                            stringResource(R.string.document_html_omitted),
                            color = Color.White,
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    LocalHtmlView(
                        document,
                        Modifier.fillMaxSize(),
                        onLink = {
                            if (actions.isCurrent()) pendingLink = it
                        },
                        onUnavailable = {
                            error = true
                        },
                    )
                }
            }
        }
        pendingLink?.let { url ->
            DocumentLinkConfirmation(url, onDismiss = { pendingLink = null }, onConfirm = {
                pendingLink = null
                if (actions.isCurrent()) openMarkdownLink(context, url, null)
            })
        }
    }
}

/**
 * Full-screen document frame: the themed shared viewer chrome with Close, the file name, Share and the More menu,
 * then the reader body on its black canvas, with the HTML Rendered and Source tabs on top. Only the toolbar follows
 * the theme (#4058), so the body keeps its white-on-black look.
 */
@Composable
@Suppress("FunctionNaming", "LongMethod")
internal fun DocumentViewerFrame(
    name: String,
    kind: DocumentAttachmentKind,
    sourceTab: Boolean,
    shareBusy: Boolean,
    onDismiss: () -> Unit,
    onShare: (() -> Unit)?,
    onSave: (() -> Unit)?,
    onOpenExternal: (() -> Unit)?,
    onSourceTab: (Boolean) -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    var more by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(Color.Black)) {
        AttachmentViewerChrome(
            title = name,
            onClose = onDismiss,
            windowInsets = AttachmentViewerChromeDefaults.topInsets(),
            actions = {
                if (onShare != null) {
                    IconButton(onClick = onShare, enabled = !shareBusy) {
                        Icon(painterResource(R.drawable.ic_share), stringResource(R.string.share))
                    }
                }
            },
            overflow =
                if (onSave != null || onOpenExternal != null) {
                    {
                        Box {
                            IconButton(
                                onClick = { more = true },
                            ) {
                                Icon(
                                    painterResource(R.drawable.ic_more_vert),
                                    stringResource(R.string.more_options),
                                )
                            }

                            WhiteNoiseDropdownMenu(
                                expanded = more,
                                onDismissRequest = { more = false },
                                items =
                                    listOfNotNull(
                                        onSave?.let {
                                            WhiteNoiseMenuItem(
                                                label = stringResource(R.string.save),
                                                icon = R.drawable.ic_download,
                                                onClick = it,
                                            )
                                        },
                                        onOpenExternal?.let {
                                            WhiteNoiseMenuItem(
                                                label = stringResource(R.string.text_attachment_open_external),
                                                icon = R.drawable.ic_open_in_new,
                                                onClick = it,
                                            )
                                        },
                                    ),
                            )
                        }
                    }
                } else {
                    null
                },
        )
        MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(primary = Color.White, onSurface = Color.White)) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
                    ),
            ) {
                if (kind == DocumentAttachmentKind.Html) {
                    TabRow(
                        selectedTabIndex = if (sourceTab) 1 else 0,
                        containerColor = Color.Black,
                        contentColor = Color.White,
                    ) {
                        Tab(
                            selected = !sourceTab,
                            onClick = {
                                onSourceTab(false)
                            },
                            text = {
                                Text(stringResource(R.string.document_rendered))
                            },
                        )
                        Tab(
                            selected = sourceTab,
                            onClick = {
                                onSourceTab(true)
                            },
                            text = {
                                Text(stringResource(R.string.document_source))
                            },
                        )
                    }
                }
                content()
            }
        }
    }
}

/** The HTML source as selectable plain white text on the reader's black canvas. */
@Composable
@Suppress("FunctionNaming")
internal fun DocumentSource(
    text: String,
    name: String,
) {
    val candidate = remember(name) { TextAttachmentCandidate(name, "text/plain", TextAttachmentFormat.PlainText) }
    val preview = remember(text, candidate) { TextAttachmentPreview(candidate, text) }
    val selection = rememberTextAttachmentSelectionController(candidate, TextAttachmentReaderState.Ready(preview))
    Column(
        Modifier
            .fillMaxSize()
            .fadingVerticalScroll(rememberScrollState())
            .padding(16.dp)
            .textAttachmentSelectionLongPress(preview, selection::requestSelection),
    ) {
        CompositionLocalProvider(LocalContentColor provides Color.White) {
            TextAttachmentSelectableContent(preview, selection, null, null, {})
        }
    }
}

@Composable
@Suppress("FunctionNaming")
internal fun DocumentLoading() {
    Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) { CircularProgressIndicator() }
}

/** An inline failure message with Retry when the load can succeed again, and Open externally when offered. */
@Composable
@Suppress("FunctionNaming")
internal fun DocumentViewerError(
    message: Int,
    onRetry: (() -> Unit)?,
    onOpenExternal: (() -> Unit)? = null,
) {
    Column(Modifier.fillMaxWidth().padding(24.dp)) {
        Text(stringResource(message), color = Color.White)
        if (onRetry != null) TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
        if (onOpenExternal != null) {
            TextButton(onClick = onOpenExternal) {
                Text(stringResource(R.string.text_attachment_open_external))
            }
        }
    }
}

/** Confirms a link opened from a document on the app dialog surface, like links everywhere else. */
@Composable
@Suppress("FunctionNaming")
internal fun DocumentLinkConfirmation(
    url: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    dev.ipf.whitenoise.android.ui
        .WebLinkConfirmation(url, onDismiss, onConfirm)
}
