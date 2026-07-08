package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.os.ConfigurationCompat
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.MessageStatus
import dev.ipf.whitenoise.android.state.MessageStatusLabels
import dev.ipf.whitenoise.android.state.TimelineMessage
import dev.ipf.whitenoise.android.state.formatExactTimestamp
import dev.ipf.whitenoise.android.state.labelFor
import dev.ipf.whitenoise.android.state.shortHex
import dev.ipf.whitenoise.android.state.shouldShowOriginalTimestamp
import dev.ipf.whitenoise.android.ui.common.Avatar
import dev.ipf.whitenoise.android.ui.theme.amoledSheetContainerColor
import dev.ipf.whitenoise.android.ui.theme.amoledSurfaceBorderStroke
import java.time.ZoneId
import java.util.Locale

/**
 * Full-screen reader for a body too long to show inline. Reached from the
 * collapsed bubble's Read More; Back returns to the conversation unchanged
 * (#325). A full-bleed Dialog avoids touching the existing nav backstack.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MessageFullScreenView(
    senderDisplayName: String,
    senderSeed: String,
    senderAvatarUrl: String?,
    body: String,
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
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties =
            DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = true,
            ),
    ) {
        var overflowOpen by remember { mutableStateOf(false) }
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        // The sender identity lives in the bar itself — avatar +
                        // name with the send time (and delivery status for own
                        // messages) as a subtitle — so the body below is just the
                        // message, no redundant in-content header.
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Avatar(title = senderDisplayName, seed = senderSeed, size = 36.dp, pictureUrl = senderAvatarUrl)
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
                                    Text(
                                        timeText,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    if (showStatus) {
                                        OutgoingMessageStatusIcon(status, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.back),
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = { overflowOpen = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.message_actions))
                        }
                        DropdownMenu(
                            expanded = overflowOpen,
                            onDismissRequest = { overflowOpen = false },
                            shape = MenuDefaults.shape,
                            border = amoledSurfaceBorderStroke(),
                        ) {
                            if (canReply) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.reply)) },
                                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.Reply, contentDescription = null) },
                                    onClick = {
                                        overflowOpen = false
                                        onReply()
                                    },
                                )
                            }
                            if (canReact) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.message_react)) },
                                    leadingIcon = { Icon(Icons.Default.EmojiEmotions, contentDescription = null) },
                                    onClick = {
                                        overflowOpen = false
                                        onReact()
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.copy_text)) },
                                leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                                onClick = {
                                    overflowOpen = false
                                    onCopy()
                                },
                            )
                            if (canDelete) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.delete)) },
                                    leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                                    onClick = {
                                        overflowOpen = false
                                        onDelete()
                                    },
                                )
                            }
                        }
                    },
                    // The Dialog window already sits below the status bar, so the
                    // bar's own status-bar inset would double the gap and inflate
                    // its height. Zero it to render at the standard compact height.
                    windowInsets = WindowInsets(0, 0, 0, 0),
                )
            },
            bottomBar = bottomBar,
        ) { padding ->
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(18.dp),
                    border = amoledSurfaceBorderStroke(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        body,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MessageInfoSheet(
    item: TimelineMessage,
    mine: Boolean,
    senderDisplayName: String,
    senderNpub: String,
    onDismissRequest: () -> Unit,
    onCopy: (String) -> Unit,
) {
    val record = item.record
    val configuration = LocalConfiguration.current
    val locale =
        remember(configuration) {
            ConfigurationCompat.getLocales(configuration).get(0) ?: Locale.getDefault()
        }
    val zone = remember { ZoneId.systemDefault() }
    val statusLabels =
        MessageStatusLabels(
            pending = stringResource(R.string.message_status_pending),
            sent = stringResource(R.string.message_status_sent),
            received = stringResource(R.string.message_status_received),
            failed = stringResource(R.string.message_status_failed),
            streaming = stringResource(R.string.message_status_streaming),
        )
    val statusText = labelFor(item.status, statusLabels)
    // Label derives from status, not `mine`, so an outgoing Failed bubble
    // doesn't read "Sent" while the Status row says "Failed". For outgoing
    // pending/failed the row reflects local composition time.
    val timestampLabel =
        when (item.status) {
            MessageStatus.Sent -> stringResource(R.string.message_info_sent_at)
            MessageStatus.Received, MessageStatus.Streaming -> stringResource(R.string.message_info_received_at)
            MessageStatus.Pending, MessageStatus.Failed -> stringResource(R.string.message_info_created_at)
        }
    // For incoming, prefer the *local* arrival time — sender's claimed
    // `recordedAt` can be spoofed. Surface `recordedAt` as a second row only
    // when it diverges from receivedAt by more than a few seconds (anything
    // less is clock-skew noise).
    val primarySeconds = if (!mine && record.receivedAt > 0uL) record.receivedAt else record.recordedAt
    val formattedTimestamp = formatExactTimestamp(primarySeconds, zone, locale)
    val showOriginal = !mine && shouldShowOriginalTimestamp(record.recordedAt, record.receivedAt)
    val formattedOriginalTimestamp =
        if (showOriginal) {
            formatExactTimestamp(record.recordedAt, zone, locale)
        } else {
            ""
        }
    val npubShort = shortHex(senderNpub, head = 12, tail = 6)
    val messageIdShort = shortHex(record.messageIdHex)
    val copyActionLabel = stringResource(R.string.copy_text)

    ModalBottomSheet(
        containerColor = amoledSheetContainerColor(),
        onDismissRequest = onDismissRequest,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.message_info),
                style = MaterialTheme.typography.titleMedium,
            )
            if (formattedTimestamp.isNotBlank()) {
                MessageInfoRow(
                    label = timestampLabel,
                    value = formattedTimestamp,
                )
            }
            if (formattedOriginalTimestamp.isNotBlank()) {
                // Sender's claimed send time. Suppressed when it matches the
                // local Received time within the skew tolerance — see
                // shouldShowOriginalTimestamp — so the row only appears when
                // it adds information.
                MessageInfoRow(
                    label = stringResource(R.string.message_info_sent_at),
                    value = formattedOriginalTimestamp,
                )
            }
            // "From" is meaningful only for incoming messages; hide for own
            // messages where it would read tautologically "From: <my name>".
            if (!mine && senderNpub.isNotBlank()) {
                MessageInfoRow(
                    label = stringResource(R.string.message_info_sender),
                    value = if (senderDisplayName.isNotBlank()) "$senderDisplayName · $npubShort" else npubShort,
                    onCopy = { onCopy(senderNpub) },
                    copyActionLabel = copyActionLabel,
                )
            }
            if (record.messageIdHex.isNotBlank()) {
                MessageInfoRow(
                    label = stringResource(R.string.message_info_message_id),
                    value = messageIdShort,
                    onCopy = { onCopy(record.messageIdHex) },
                    copyActionLabel = copyActionLabel,
                )
            }
            MessageInfoRow(
                label = stringResource(R.string.message_info_status),
                value = statusText,
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun MessageInfoRow(
    label: String,
    value: String,
    onCopy: (() -> Unit)? = null,
    copyActionLabel: String? = null,
) {
    val rowModifier =
        if (onCopy != null) {
            Modifier
                .fillMaxWidth()
                .clickable(
                    onClickLabel = copyActionLabel,
                    role = Role.Button,
                    onClick = onCopy,
                )
        } else {
            Modifier.fillMaxWidth()
        }
    Row(
        modifier = rowModifier.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (onCopy != null) {
            Icon(
                Icons.Default.ContentCopy,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
