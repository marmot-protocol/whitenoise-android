@file:Suppress("FunctionNaming") // Compose UI functions intentionally use PascalCase.

package dev.ipf.whitenoise.android.ui.conversation.media

import android.content.ClipData
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.parseMarkdownOrEmpty
import dev.ipf.whitenoise.android.state.runCatchingCancellable
import dev.ipf.whitenoise.android.state.ttsStartFailureMessage
import dev.ipf.whitenoise.android.ui.conversation.TtsTransportBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

internal const val TEXT_ATTACHMENT_READER_TAG = "text-attachment-reader"
internal const val TEXT_ATTACHMENT_READER_BODY_TAG = "text-attachment-reader-body"
internal const val TEXT_ATTACHMENT_READER_RETRY_TAG = "text-attachment-reader-retry"
internal const val TEXT_ATTACHMENT_READER_FILENAME_TAG = "text-attachment-reader-filename"
internal const val TEXT_ATTACHMENT_READER_FILENAME_DIALOG_TAG = "text-attachment-reader-filename-dialog"
internal const val TEXT_ATTACHMENT_READER_FULL_FILENAME_TAG = "text-attachment-reader-full-filename"

/** Projects a local text attachment and reports the precise media-mix start refusal. */
private suspend fun WhiteNoiseAppState.speakTextAttachment(
    preview: TextAttachmentPreview,
    senderKey: String,
    senderDisplayName: String,
    messageIdHex: String,
    attachmentIndex: Int,
) {
    val entry =
        withContext(Dispatchers.Default) {
            textAttachmentTtsEntry(
                preview = preview,
                senderKey = senderKey,
                senderDisplayName = senderDisplayName,
                messageIdHex = messageIdHex,
                attachmentIndex = attachmentIndex,
            )
        }
    if (entry.text.isBlank() || !speakAloud(listOf(entry), Locale.getDefault())) {
        present(if (entry.text.isBlank()) R.string.tts_bar_error else ttsStartFailureMessage())
    }
}

@Composable
internal fun TextAttachmentReaderDialog(
    candidate: TextAttachmentCandidate,
    appState: WhiteNoiseAppState,
    senderKey: String,
    senderDisplayName: String,
    messageIdHex: String,
    attachmentIndex: Int,
    loadBytes: suspend () -> ByteArray,
    onOpenExternal: suspend () -> Unit,
    onDismiss: () -> Unit,
) {
    var loadGeneration by remember(candidate) { mutableIntStateOf(0) }
    var state by remember(candidate) { mutableStateOf<TextAttachmentReaderState>(TextAttachmentReaderState.Loading) }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current

    LaunchedEffect(candidate, loadGeneration) {
        state = TextAttachmentReaderState.Loading
        state =
            runCatchingCancellable {
                loadTextAttachmentPreview(
                    candidate = candidate,
                    bytes = loadBytes(),
                    parseMarkdown = { appState.parseMarkdownOrEmpty(it) },
                )
            }.getOrElse {
                TextAttachmentReaderState.Unavailable(TextAttachmentUnavailableReason.DownloadFailed)
            }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties =
            DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = true,
                decorFitsSystemWindows = false,
            ),
    ) {
        TextAttachmentReaderScreen(
            candidate = candidate,
            state = state,
            onDismiss = onDismiss,
            onRetry = { loadGeneration += 1 },
            onCopy = { value ->
                scope.launch {
                    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("text attachment", value)))
                    appState.present(R.string.copied)
                }
            },
            onReadAloud = { preview ->
                scope.launch {
                    appState.speakTextAttachment(
                        preview = preview,
                        senderKey = senderKey,
                        senderDisplayName = senderDisplayName,
                        messageIdHex = messageIdHex,
                        attachmentIndex = attachmentIndex,
                    )
                }
            },
            onOpenExternal = { scope.launch { onOpenExternal() } },
            mentionDisplayName = appState::mentionDisplayName,
            onNostrProfileTap = appState::presentProfile,
            transport = { TtsTransportBar(appState) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TextAttachmentReaderScreen(
    candidate: TextAttachmentCandidate,
    state: TextAttachmentReaderState,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onCopy: (String) -> Unit,
    onReadAloud: (TextAttachmentPreview) -> Unit,
    onOpenExternal: () -> Unit,
    modifier: Modifier = Modifier,
    mentionDisplayName: ((String) -> String?)? = null,
    onNostrProfileTap: ((String) -> Unit)? = null,
    transport: @Composable () -> Unit = {},
) {
    val selection = rememberTextAttachmentSelectionController(candidate, state)
    val preview = (state as? TextAttachmentReaderState.Ready)?.preview
    Scaffold(
        modifier =
            modifier
                .fillMaxSize()
                .testTag(TEXT_ATTACHMENT_READER_TAG),
        topBar = {
            TextAttachmentReaderTopBar(
                candidate = candidate,
                preview = preview,
                selection = selection,
                onDismiss = onDismiss,
                onCopy = onCopy,
                onReadAloud = onReadAloud,
                onOpenExternal = onOpenExternal,
                transport = transport,
            )
        },
    ) { padding ->
        TextAttachmentReaderContent(
            state = state,
            selection = selection,
            onRetry = onRetry,
            onOpenExternal = onOpenExternal,
            onCopyLink = onCopy,
            mentionDisplayName = mentionDisplayName,
            onNostrProfileTap = onNostrProfileTap,
            modifier = Modifier.padding(padding),
        )
    }
}

/** Renders reader navigation and actions against the current native selection. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TextAttachmentReaderTopBar(
    candidate: TextAttachmentCandidate,
    preview: TextAttachmentPreview?,
    selection: TextAttachmentSelectionController,
    onDismiss: () -> Unit,
    onCopy: (String) -> Unit,
    onReadAloud: (TextAttachmentPreview) -> Unit,
    onOpenExternal: () -> Unit,
    transport: @Composable () -> Unit,
) {
    Column {
        TopAppBar(
            title = {},
            navigationIcon = {
                IconButton(onClick = onDismiss) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.back),
                    )
                }
            },
            actions = {
                TextAttachmentReaderActions(
                    preview = preview,
                    copyText = selection::selectedText,
                    onCopy = onCopy,
                    onReadAloud = onReadAloud,
                    onOpenExternal = onOpenExternal,
                )
            },
        )
        TextAttachmentMetadata(candidate = candidate, onCopy = onCopy)
        HorizontalDivider()
        transport()
    }
}

@Composable
private fun TextAttachmentReaderActions(
    preview: TextAttachmentPreview?,
    copyText: (String) -> String,
    onCopy: (String) -> Unit,
    onReadAloud: (TextAttachmentPreview) -> Unit,
    onOpenExternal: () -> Unit,
) {
    IconButton(
        enabled = preview?.text?.isNotEmpty() == true,
        onClick = { preview?.let { onCopy(copyText(it.text)) } },
    ) {
        Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.copy_text))
    }
    IconButton(
        enabled = preview?.text?.isNotBlank() == true,
        onClick = { preview?.let(onReadAloud) },
    ) {
        Icon(
            Icons.AutoMirrored.Filled.VolumeUp,
            contentDescription = stringResource(R.string.speak_aloud),
        )
    }
    IconButton(onClick = onOpenExternal) {
        Icon(
            Icons.AutoMirrored.Filled.OpenInNew,
            contentDescription = stringResource(R.string.text_attachment_open_external),
        )
    }
}

@Composable
private fun TextAttachmentMetadata(
    candidate: TextAttachmentCandidate,
    onCopy: (String) -> Unit,
) {
    var showFullFilename by remember(candidate.displayName) { mutableStateOf(false) }
    var filenameTruncated by remember(candidate.displayName) { mutableStateOf(false) }
    val viewFullFilenameLabel = stringResource(R.string.text_attachment_view_full_filename)
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = candidate.displayName,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { result ->
                if (filenameTruncated != result.hasVisualOverflow) {
                    filenameTruncated = result.hasVisualOverflow
                }
            },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .testTag(TEXT_ATTACHMENT_READER_FILENAME_TAG),
        )
        Text(
            candidate.normalizedMime,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (filenameTruncated) {
            TextButton(
                onClick = { showFullFilename = true },
                modifier = Modifier.align(Alignment.End),
            ) {
                Text(viewFullFilenameLabel)
            }
        }
    }
    if (showFullFilename) {
        TextAttachmentFilenameDialog(
            filename = candidate.displayName,
            onCopy = {
                onCopy(candidate.displayName)
                showFullFilename = false
            },
            onDismiss = { showFullFilename = false },
        )
    }
}

@Composable
private fun TextAttachmentFilenameDialog(
    filename: String,
    onCopy: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(TEXT_ATTACHMENT_READER_FILENAME_DIALOG_TAG),
        title = { Text(stringResource(R.string.text_attachment_filename)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
            ) {
                SelectionContainer {
                    Text(
                        text = filename,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .testTag(TEXT_ATTACHMENT_READER_FULL_FILENAME_TAG),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onCopy) {
                Text(stringResource(R.string.copy))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        },
    )
}

@Composable
private fun TextAttachmentReaderContent(
    state: TextAttachmentReaderState,
    selection: TextAttachmentSelectionController,
    onRetry: () -> Unit,
    onOpenExternal: () -> Unit,
    onCopyLink: (String) -> Unit,
    mentionDisplayName: ((String) -> String?)?,
    onNostrProfileTap: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when (state) {
            TextAttachmentReaderState.Loading -> TextAttachmentLoading()
            is TextAttachmentReaderState.Unavailable ->
                TextAttachmentUnavailable(state.reason, onRetry, onOpenExternal)
            is TextAttachmentReaderState.Ready ->
                TextAttachmentReadyBody(
                    preview = state.preview,
                    selection = selection,
                    mentionDisplayName = mentionDisplayName,
                    onNostrProfileTap = onNostrProfileTap,
                    onCopyLink = onCopyLink,
                    onOpenExternal = onOpenExternal,
                )
        }
    }
}

@Composable
private fun TextAttachmentLoading() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator()
        Text(stringResource(R.string.text_attachment_loading))
    }
}

@Composable
private fun TextAttachmentReadyBody(
    preview: TextAttachmentPreview,
    selection: TextAttachmentSelectionController,
    mentionDisplayName: ((String) -> String?)?,
    onNostrProfileTap: ((String) -> Unit)?,
    onCopyLink: (String) -> Unit,
    onOpenExternal: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
                .textAttachmentSelectionLongPress(preview, selection::requestSelection)
                .testTag(TEXT_ATTACHMENT_READER_BODY_TAG),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (preview.isTruncated) TextAttachmentTruncatedNotice(onOpenExternal)
        if (preview.text.isEmpty()) {
            Text(
                stringResource(R.string.text_attachment_empty),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        } else {
            TextAttachmentSelectableContent(
                preview = preview,
                selection = selection,
                mentionDisplayName = mentionDisplayName,
                onNostrProfileTap = onNostrProfileTap,
                onCopyLink = onCopyLink,
            )
        }
    }
}

@Composable
private fun TextAttachmentTruncatedNotice(onOpenExternal: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(Icons.Default.Info, contentDescription = null)
                Text(
                    stringResource(R.string.text_attachment_preview_truncated),
                    modifier = Modifier.weight(1f),
                )
            }
            TextButton(onClick = onOpenExternal, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.media_open))
            }
        }
    }
}
