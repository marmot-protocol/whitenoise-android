@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.os.ConfigurationCompat
import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.ReactionTally
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.MessageStatus
import dev.ipf.whitenoise.android.state.MessageStatusLabels
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.labelFor
import dev.ipf.whitenoise.android.state.shouldShowOriginalTimestamp
import dev.ipf.whitenoise.android.ui.common.AdaptiveContent
import dev.ipf.whitenoise.android.ui.common.Avatar
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing
import dev.ipf.whitenoise.android.ui.theme.amoledOutlineBorder
import java.time.ZoneId
import java.util.Locale

internal const val MESSAGE_DETAILS_TAG = "message.details"
internal const val MESSAGE_DETAILS_LIST_TAG = "message.details.list"
internal const val MESSAGE_FACTS_TAG = "message.facts"

/** One person an outgoing message was delivered to, for the prototype's delivery list. */
internal data class MessageDetailsRecipient(
    val title: String,
    val seed: String,
    val avatarUrl: String?,
)

/** Outgoing messages list the other members as recipients; incoming ones show the sender instead. */
internal fun messageDetailsRecipients(
    controller: ConversationController,
    appState: WhiteNoiseAppState,
    mine: Boolean,
): List<MessageDetailsRecipient> {
    if (!mine) return emptyList()
    val self = appState.activeAccount?.accountIdHex
    return controller.presentedMembers
        .filterNot { it.memberIdHex.equals(self, ignoreCase = true) }
        .map { member ->
            MessageDetailsRecipient(
                title = appState.displayName(member.memberIdHex),
                seed = member.memberIdHex,
                avatarUrl = appState.avatarUrl(member.memberIdHex),
            )
        }
}

/**
 * The prototype's Message Details page: the message on its own card, the facts table, reactions and the
 * delivery list, presented full screen with Back like the reader.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("LongParameterList")
@Composable
internal fun MessageDetailsScreen(
    record: AppMessageRecordFfi,
    status: MessageStatus,
    mine: Boolean,
    senderDisplayName: String,
    senderNpub: String,
    senderAvatarUrl: String?,
    reactions: List<ReactionTally>,
    recipients: List<MessageDetailsRecipient>,
    attachmentLabels: List<String>,
    onDismissRequest: () -> Unit,
    onCopy: (String) -> Unit,
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize().testTag(MESSAGE_DETAILS_TAG),
            contentWindowInsets = WindowInsets.safeDrawing,
            containerColor = MaterialTheme.colorScheme.surface,
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.message_details)) },
                    navigationIcon = {
                        IconButton(onClick = onDismissRequest) {
                            Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.back))
                        }
                    },
                    colors =
                        TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                            scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                        ),
                )
            },
        ) { padding ->
            AdaptiveContent(Modifier.fillMaxSize().padding(padding)) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(WhiteNoiseSpacing.CompactScreenMargin)
                            .testTag(MESSAGE_DETAILS_LIST_TAG),
                    verticalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.FormField),
                ) {
                    MessageDetailsContentCard(record.plaintext, attachmentLabels)
                    MessageFactsSection(record, status, mine, senderNpub, onCopy)
                    if (reactions.isNotEmpty()) MessageReactionsSection(reactions)
                    MessageDeliverySection(mine, status, senderDisplayName, record.sender, senderAvatarUrl, recipients)
                }
            }
        }
    }
}

/** The message on its own card: text and attachment names. */
@Composable
private fun MessageDetailsContentCard(
    text: String,
    attachmentLabels: List<String>,
) {
    if (text.isBlank() && attachmentLabels.isEmpty()) return
    Surface(
        border = amoledOutlineBorder(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(WhiteNoiseSpacing.CompactScreenMargin),
            verticalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.Related),
        ) {
            if (text.isNotBlank()) {
                Text(text, style = MaterialTheme.typography.bodyLarge)
            }
            attachmentLabels.forEach { label ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.Related),
                ) {
                    Icon(
                        painterResource(R.drawable.ic_description),
                        contentDescription = null,
                        modifier = Modifier.size(AttachmentGlyphSize),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(label, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

/** Status, the relevant time, the sender's own time when it differs, expiry, ids with copy actions. */
@Composable
private fun MessageFactsSection(
    record: AppMessageRecordFfi,
    status: MessageStatus,
    mine: Boolean,
    senderNpub: String,
    onCopy: (String) -> Unit,
) {
    val configuration = LocalConfiguration.current
    val locale = remember(configuration) { ConfigurationCompat.getLocales(configuration)[0] ?: Locale.ROOT }
    val zone = remember { ZoneId.systemDefault() }
    val statusText = labelFor(status, messageStatusLabels())
    val timeLabel =
        when (status) {
            MessageStatus.Sent -> R.string.message_info_sent_at
            MessageStatus.Received, MessageStatus.Streaming -> R.string.message_info_received_at
            MessageStatus.Pending, MessageStatus.Failed -> R.string.message_info_created_at
        }
    val primarySeconds = if (!mine && record.receivedAt > 0uL) record.receivedAt else record.recordedAt
    val showSenderTime = !mine && shouldShowOriginalTimestamp(record.recordedAt, record.receivedAt)
    Surface(
        modifier = Modifier.fillMaxWidth().testTag(MESSAGE_FACTS_TAG),
        border = amoledOutlineBorder(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column {
            FactRow(stringResource(R.string.message_info_status), statusText)
            FactRow(stringResource(timeLabel), editHistoryRevisionTime(primarySeconds, locale, zone))
            if (showSenderTime) {
                FactRow(
                    stringResource(R.string.message_sender_time),
                    editHistoryRevisionTime(record.recordedAt, locale, zone),
                )
            }
            record.retentionExpiresAt?.takeIf { it > 0uL }?.let { expiresAt ->
                FactRow(
                    stringResource(R.string.message_info_disappears_at),
                    editHistoryRevisionTime(expiresAt, locale, zone),
                )
            }
            if (record.messageIdHex.isNotBlank()) {
                FactRow(
                    label = stringResource(R.string.message_info_message_id),
                    value = record.messageIdHex,
                    copyLabel = stringResource(R.string.message_copy_id),
                    onCopy = { onCopy(record.messageIdHex) },
                )
            }
            if (!mine && senderNpub.isNotBlank()) {
                FactRow(
                    label = stringResource(R.string.message_sender_public_key),
                    value = senderNpub,
                    copyLabel = stringResource(R.string.message_copy_sender_key),
                    onCopy = { onCopy(senderNpub) },
                )
            }
        }
    }
}

/** Localized labels for each delivery status. */
@Composable
private fun messageStatusLabels(): MessageStatusLabels =
    MessageStatusLabels(
        pending = stringResource(R.string.message_status_pending),
        sent = stringResource(R.string.message_status_sent),
        received = stringResource(R.string.message_status_received),
        failed = stringResource(R.string.message_status_failed),
        streaming = stringResource(R.string.message_status_streaming),
    )

/** One label / value row of the facts table, with an optional copy action. */
@Composable
private fun FactRow(
    label: String,
    value: String,
    copyLabel: String? = null,
    onCopy: (() -> Unit)? = null,
) {
    ListItem(
        headlineContent = { Text(label) },
        supportingContent = {
            Text(
                value,
                maxLines = if (onCopy == null) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent =
            if (onCopy == null) {
                null
            } else {
                {
                    IconButton(onClick = onCopy) {
                        Icon(painterResource(R.drawable.ic_content_copy), contentDescription = copyLabel)
                    }
                }
            },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

/** Reactions with their counts. */
@Composable
private fun MessageReactionsSection(reactions: List<ReactionTally>) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        border = amoledOutlineBorder(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column {
            Text(
                text = stringResource(R.string.reactions),
                modifier =
                    Modifier.padding(
                        start = WhiteNoiseSpacing.CompactScreenMargin,
                        top = WhiteNoiseSpacing.CompactScreenMargin,
                        end = WhiteNoiseSpacing.CompactScreenMargin,
                        bottom = WhiteNoiseSpacing.Related,
                    ),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            reactions.forEachIndexed { index, tally ->
                if (index > 0) {
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = WhiteNoiseSpacing.CompactScreenMargin),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
                ListItem(
                    modifier = Modifier.testTag("message.details.reaction.$index"),
                    headlineContent = {
                        Text(pluralStringResource(R.plurals.people_reacted, tally.count, tally.count))
                    },
                    leadingContent = { Text(tally.emoji, fontSize = ReactionGlyphSize) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
        }
    }
}

/** Incoming: who sent it. Outgoing: every other member with the message's delivery state. */
@Suppress("LongParameterList")
@Composable
private fun MessageDeliverySection(
    mine: Boolean,
    status: MessageStatus,
    senderDisplayName: String,
    senderSeed: String,
    senderAvatarUrl: String?,
    recipients: List<MessageDetailsRecipient>,
) {
    if (mine && recipients.isEmpty()) return
    val statusText = labelFor(status, messageStatusLabels())
    Surface(
        modifier = Modifier.fillMaxWidth(),
        border = amoledOutlineBorder(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column {
            if (!mine) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.sent_from)) },
                    supportingContent = { Text(senderDisplayName) },
                    leadingContent = {
                        Avatar(
                            title = senderDisplayName,
                            seed = senderSeed,
                            size = DeliveryAvatarSize,
                            pictureUrl = senderAvatarUrl,
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            } else {
                recipients.forEach { recipient ->
                    ListItem(
                        headlineContent = { Text(recipient.title) },
                        supportingContent = { Text(statusText) },
                        leadingContent = {
                            Avatar(
                                title = recipient.title,
                                seed = recipient.seed,
                                size = DeliveryAvatarSize,
                                pictureUrl = recipient.avatarUrl,
                            )
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
            }
        }
    }
}

private val AttachmentGlyphSize = 20.dp
private val DeliveryAvatarSize = 40.dp
private val ReactionGlyphSize = 24.sp
