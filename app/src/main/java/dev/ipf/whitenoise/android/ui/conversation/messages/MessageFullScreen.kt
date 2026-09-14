package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.MessageStatus
import dev.ipf.whitenoise.android.ui.MarkdownMessageBody
import dev.ipf.whitenoise.android.ui.common.AdaptiveContent
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseDropdownMenu
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseMenuItem

internal const val MESSAGE_FULL_SCREEN_TAG = "message-full-screen"
internal const val MESSAGE_FULL_SCREEN_BODY_TAG = "message-full-screen-body"

/**
 * Full-screen reader for a body too long to show inline. Reached from the
 * collapsed bubble's Read More; Back returns to the conversation unchanged
 * (#325). A full-bleed Dialog avoids touching the existing nav backstack while
 * preserving native selection and link gestures.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("FunctionNaming", "LongMethod", "UnusedParameter") // Keep native caller identity inputs API-compatible.
internal fun MessageFullScreenView(
    senderDisplayName: String,
    senderSeed: String,
    senderAvatarUrl: String?,
    body: String,
    bodyMarkdownDocument: MarkdownDocumentFfi?,
    mentionDisplayName: ((String) -> String?)?,
    isGroupMember: ((String) -> Boolean)?,
    onNostrProfileTap: ((String) -> Unit)?,
    onCopyMarkdownLink: (String) -> Unit,
    timeText: String,
    showStatus: Boolean,
    status: MessageStatus,
    canReply: Boolean,
    canReact: Boolean,
    canDelete: Boolean,
    onReply: () -> Unit,
    onReact: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
    bottomBar: @Composable () -> Unit,
    selectionController: ReaderTextSelectionController? = null,
) {
    val selectionKey = remember(body, bodyMarkdownDocument) { Any() }
    val selection = rememberReaderTextSelectionController(selectionKey, selectionController)
    Dialog(
        onDismissRequest = onDismiss,
        properties =
            DialogProperties(
                usePlatformDefaultWidth = false,
                // The first Back press exits an active native selection; only
                // the next one dismisses the reader.
                dismissOnBackPress = false,
                decorFitsSystemWindows = false,
            ),
    ) {
        BackHandler {
            if (selection.active) selection.reset() else onDismiss()
        }
        var overflowOpen by remember { mutableStateOf(false) }
        Scaffold(
            modifier = Modifier.testTag(MESSAGE_FULL_SCREEN_TAG),
            contentWindowInsets = WindowInsets.safeDrawing,
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                senderDisplayName,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                if (showStatus) {
                                    OutgoingMessageStatusIcon(status, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text(
                                    timeText,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = {
                                if (selection.active) selection.reset() else onDismiss()
                            },
                        ) {
                            Icon(
                                painterResource(R.drawable.ic_arrow_back),
                                contentDescription = stringResource(R.string.back),
                            )
                        }
                    },
                    actions = {
                        Box {
                            IconButton(onClick = { overflowOpen = true }) {
                                Icon(
                                    painterResource(R.drawable.ic_more_vert),
                                    contentDescription = stringResource(R.string.message_actions),
                                )
                            }
                            WhiteNoiseDropdownMenu(
                                expanded = overflowOpen,
                                onDismissRequest = { overflowOpen = false },
                                items =
                                    buildList {
                                        if (canReply) {
                                            add(
                                                WhiteNoiseMenuItem(
                                                    label = stringResource(R.string.reply),
                                                    icon = R.drawable.ic_reply,
                                                    onClick = onReply,
                                                ),
                                            )
                                        }
                                        if (canReact) {
                                            add(
                                                WhiteNoiseMenuItem(
                                                    label = stringResource(R.string.message_react),
                                                    icon = R.drawable.ic_add,
                                                    onClick = onReact,
                                                ),
                                            )
                                        }
                                        add(
                                            WhiteNoiseMenuItem(
                                                label = stringResource(R.string.copy_text),
                                                icon = R.drawable.ic_content_copy,
                                                onClick = onCopy,
                                            ),
                                        )
                                        if (canDelete) {
                                            add(
                                                WhiteNoiseMenuItem(
                                                    label = stringResource(R.string.delete),
                                                    icon = R.drawable.ic_delete,
                                                    destructive = true,
                                                    onClick = onDelete,
                                                ),
                                            )
                                        }
                                    },
                            )
                        }
                    },
                )
            },
            bottomBar = bottomBar,
        ) { padding ->
            AdaptiveContent(Modifier.fillMaxSize().padding(padding)) {
                MessageFullScreenBody(
                    body = body,
                    markdownDocument = bodyMarkdownDocument,
                    mentionDisplayName = mentionDisplayName,
                    isGroupMember = isGroupMember,
                    onNostrProfileTap = onNostrProfileTap,
                    onCopyMarkdownLink = onCopyMarkdownLink,
                    selectionController = selection,
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                )
            }
        }
    }
}

/** Renders selectable plain text or Markdown while preserving link gestures. */
@Composable
@Suppress("FunctionNaming") // Jetpack Compose functions use UpperCamelCase.
internal fun MessageFullScreenBody(
    body: String,
    markdownDocument: MarkdownDocumentFfi?,
    mentionDisplayName: ((String) -> String?)?,
    isGroupMember: ((String) -> Boolean)?,
    onNostrProfileTap: ((String) -> Unit)?,
    onCopyMarkdownLink: (String) -> Unit,
    selectionController: ReaderTextSelectionController? = null,
    modifier: Modifier = Modifier,
) {
    val selectionKey = remember(body, markdownDocument) { Any() }
    val selection = rememberReaderTextSelectionController(selectionKey, selectionController)
    val content: @Composable () -> Unit = {
        markdownDocument?.let { document ->
            MarkdownMessageBody(
                document = document,
                modifier = Modifier.fillMaxWidth(),
                mentionDisplayName = mentionDisplayName,
                isGroupMember = isGroupMember,
                onNostrProfileTap = onNostrProfileTap,
                onSelectableTextLayoutChanged = selection.selectableTextLayoutReporter,
                onLinkTextLayoutChanged = selection.markdownLinkLayoutReporter,
                onCopyLink = onCopyMarkdownLink,
            )
        } ?: ReaderSelectablePlainText(
            text = body,
            onSelectableTextLayoutChanged = selection.selectableTextLayoutReporter,
        )
    }
    Box(
        modifier =
            modifier
                .testTag(MESSAGE_FULL_SCREEN_BODY_TAG)
                .readerTextSelectionLongPress(selection) { position ->
                    selection.requestSelection(position)
                },
    ) {
        if (selection.active) {
            SelectionContainer(state = selection.selectionState) {
                content()
            }
        } else {
            content()
        }
    }
}
