@file:Suppress("FunctionNaming") // Compose UI functions intentionally use PascalCase.

package dev.ipf.whitenoise.android.ui.conversation.media

import android.content.ClipData
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
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
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.parseMarkdownOrEmpty
import dev.ipf.whitenoise.android.state.runCatchingCancellable
import dev.ipf.whitenoise.android.state.ttsStartFailureMessage
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseDropdownMenu
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseMenuItem
import dev.ipf.whitenoise.android.ui.conversation.TtsTransportBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private const val READER_ACTIONS_MAXIMUM_HEIGHT_FRACTION = 0.55f

internal const val TEXT_ATTACHMENT_READER_TAG = "text-attachment-reader"
internal const val TEXT_ATTACHMENT_READER_BODY_TAG = "text-attachment-reader-body"
internal const val TEXT_ATTACHMENT_READER_RETRY_TAG = "text-attachment-reader-retry"
internal const val TEXT_ATTACHMENT_READER_FILENAME_TAG = "text-attachment-reader-filename"
internal const val TEXT_ATTACHMENT_READER_FILENAME_DIALOG_TAG = "text-attachment-reader-filename-dialog"
internal const val TEXT_ATTACHMENT_READER_FULL_FILENAME_TAG = "text-attachment-reader-full-filename"

/** Projects a local text attachment and reports the precise media-mix start refusal. */
@Suppress("LongParameterList", "LongMethod")
private suspend fun WhiteNoiseAppState.speakTextAttachment(
    preview: TextAttachmentPreview,
    senderKey: String,
    senderDisplayName: String,
    messageIdHex: String,
    attachmentIndex: Int,
    actions: TextAttachmentNativeActions,
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
    if (!actions.isCurrent()) return
    if (entry.text.isBlank() || !speakAloudPrepared(listOf(entry), Locale.getDefault())) {
        present(if (entry.text.isBlank()) R.string.tts_bar_error else ttsStartFailureMessage())
    }
}

/** Full-screen dialog reading a text attachment. */
@Composable
@Suppress("LongParameterList", "LongMethod")
internal fun TextAttachmentReaderDialog(
    actions: TextAttachmentNativeActions,
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
    var loadGeneration by remember(candidate, actions) { mutableIntStateOf(0) }
    var state by remember(candidate, actions) {
        mutableStateOf<TextAttachmentReaderState>(TextAttachmentReaderState.Loading)
    }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    val speechState by appState.ttsController.state.collectAsState()
    var speechRequested by remember(actions) { mutableStateOf(false) }
    val isReading = actions.isCurrent() && textAttachmentOwnsSpeech(speechState, messageIdHex, attachmentIndex)

    LaunchedEffect(candidate, actions, loadGeneration) {
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
                if (actions.isCurrent() && !speechRequested) {
                    if (textAttachmentOwnsSpeech(appState.ttsController.state.value, messageIdHex, attachmentIndex)) {
                        appState.stopSpeaking()
                    } else {
                        speechRequested = true
                        scope.launch {
                            try {
                                appState.speakTextAttachment(
                                    preview,
                                    senderKey,
                                    senderDisplayName,
                                    messageIdHex,
                                    attachmentIndex,
                                    actions,
                                )
                            } finally {
                                speechRequested = false
                            }
                        }
                    }
                }
            },
            onSave = { scope.launch { actions.save() } },
            isReading = isReading,
            readAloudBusy = speechRequested,
            onOpenExternal = { scope.launch { onOpenExternal() } },
            mentionDisplayName = appState::mentionDisplayName,
            onNostrProfileTap = appState::presentProfile,
            transport = { TtsTransportBar(appState) },
        )
    }
}

/** Reader screen: metadata, body and native actions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("LongParameterList", "LongMethod")
internal fun TextAttachmentReaderScreen(
    candidate: TextAttachmentCandidate,
    state: TextAttachmentReaderState,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onCopy: (String) -> Unit,
    onReadAloud: (TextAttachmentPreview) -> Unit,
    onOpenExternal: () -> Unit,
    onSave: () -> Unit = {},
    isReading: Boolean = false,
    readAloudBusy: Boolean = false,
    modifier: Modifier = Modifier,
    mentionDisplayName: ((String) -> String?)? = null,
    onNostrProfileTap: ((String) -> Unit)? = null,
    transport: @Composable () -> Unit = {},
) {
    val selection = rememberTextAttachmentSelectionController(candidate, state)
    val preview = (state as? TextAttachmentReaderState.Ready)?.preview
    val onBack = { if (selection.active) selection.reset() else onDismiss() }
    BackHandler(enabled = selection.active, onBack = onBack)
    Scaffold(
        modifier =
            modifier
                .fillMaxSize()
                .testTag(TEXT_ATTACHMENT_READER_TAG),
        topBar = {
            TextAttachmentReaderTopBar(onDismiss = onBack, onOpenExternal = onOpenExternal, onSave = onSave)
        },
        bottomBar = {
            TextAttachmentReaderBottomBar(
                preview = preview,
                selection = selection,
                onCopy = onCopy,
                onReadAloud = onReadAloud,
                isReading = isReading,
                busy = readAloudBusy,
                transport = transport,
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            TextAttachmentMetadata(candidate = candidate, byteCount = preview?.byteCount, onCopy = onCopy)
            HorizontalDivider()
            TextAttachmentReaderContent(
                state = state,
                selection = selection,
                onRetry = onRetry,
                onOpenExternal = onOpenExternal,
                onCopyLink = onCopy,
                mentionDisplayName = mentionDisplayName,
                onNostrProfileTap = onNostrProfileTap,
            )
        }
    }
}

/** Keeps the prototype titled navigation and overflow on the existing external-open callback. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TextAttachmentReaderTopBar(
    onDismiss: () -> Unit,
    onOpenExternal: () -> Unit,
    onSave: () -> Unit,
) {
    var more by remember { mutableStateOf(false) }
    TopAppBar(
        title = { Text(stringResource(R.string.text_attachment_reader_title)) },
        navigationIcon = {
            IconButton(onClick = onDismiss) {
                Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.back))
            }
        },
        actions = {
            Box {
                IconButton(onClick = { more = true }) {
                    Icon(painterResource(R.drawable.ic_more_vert), stringResource(R.string.more_options))
                }
                WhiteNoiseDropdownMenu(
                    expanded = more,
                    onDismissRequest = { more = false },
                    items =
                        listOf(
                            WhiteNoiseMenuItem(
                                label = stringResource(R.string.save),
                                icon = R.drawable.ic_download,
                                onClick = onSave,
                            ),
                            WhiteNoiseMenuItem(
                                label = stringResource(R.string.text_attachment_open_external),
                                onClick = onOpenExternal,
                            ),
                        ),
                )
            }
        },
    )
}

/** Keeps native transport and selection actions above navigation insets, scrollable at large text. */
@Composable
@Suppress("LongParameterList", "LongMethod")
private fun TextAttachmentReaderBottomBar(
    preview: TextAttachmentPreview?,
    selection: TextAttachmentSelectionController,
    onCopy: (String) -> Unit,
    onReadAloud: (TextAttachmentPreview) -> Unit,
    isReading: Boolean,
    busy: Boolean,
    transport: @Composable () -> Unit,
) {
    val maximumHeight =
        with(LocalDensity.current) {
            LocalWindowInfo.current.containerSize.height
                .toDp()
        } *
            READER_ACTIONS_MAXIMUM_HEIGHT_FRACTION
    Column(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .heightIn(max = maximumHeight)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        transport()
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val copyLabel = stringResource(R.string.copy_text)
            TextButton(
                enabled = preview?.text?.isNotEmpty() == true,
                onClick = { preview?.let { onCopy(selection.selectedText(it.text)) } },
                modifier = Modifier.semantics { contentDescription = copyLabel },
            ) { Text(copyLabel) }
            val speakLabel = stringResource(if (isReading) R.string.tts_bar_stop else R.string.speak_aloud)
            TextButton(
                enabled = !busy && preview?.text?.isNotBlank() == true,
                onClick = {
                    preview?.let { onReadAloud(textAttachmentSelectedPreview(it, selection.selectedText(it.text))) }
                },
                modifier = Modifier.semantics { contentDescription = speakLabel },
            ) { Text(speakLabel) }
        }
    }
}

/** File name, type and size with a copy action. */
@Composable
private fun TextAttachmentMetadata(
    candidate: TextAttachmentCandidate,
    byteCount: Long?,
    onCopy: (String) -> Unit,
) {
    var showFullFilename by remember(candidate.displayName) { mutableStateOf(false) }
    val viewFullFilenameLabel = stringResource(R.string.text_attachment_view_full_filename)
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = candidate.displayName,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
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
        byteCount?.let {
            Text(stringResource(R.string.bytes_count, it), style = MaterialTheme.typography.labelMedium)
        }
        TextButton(onClick = { showFullFilename = true }) {
            Text(viewFullFilenameLabel)
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

/** Loading, failed or ready body of the reader. */
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
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
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

/** Selectable rendered text of a ready attachment. */
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
                .fillMaxWidth()
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
