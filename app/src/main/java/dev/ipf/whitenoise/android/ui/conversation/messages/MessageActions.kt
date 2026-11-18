@file:Suppress("TooManyFunctions") // One action surface owns menu, picker, progress, and button projections.

package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.key
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.ForwardBlockedReason
import dev.ipf.whitenoise.android.core.ForwardMessagePayload
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.ForwardFailureStage
import dev.ipf.whitenoise.android.state.ForwardOperationPhase
import dev.ipf.whitenoise.android.state.ForwardOperationSnapshot
import dev.ipf.whitenoise.android.state.ForwardTargetPhase
import dev.ipf.whitenoise.android.state.ForwardTargetProgress
import dev.ipf.whitenoise.android.state.PendingForwardRequest
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.isForwardOwnerSignedIn
import dev.ipf.whitenoise.android.ui.EmojiLabel
import dev.ipf.whitenoise.android.ui.common.Explanation
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseLazyColumn
import dev.ipf.whitenoise.android.ui.icons.Icons
import dev.ipf.whitenoise.android.ui.icons.filled.CheckCircle
import dev.ipf.whitenoise.android.ui.icons.filled.Close
import dev.ipf.whitenoise.android.ui.icons.filled.ErrorOutline
import dev.ipf.whitenoise.android.ui.icons.filled.Schedule
import dev.ipf.whitenoise.android.ui.theme.Dimens
import kotlinx.coroutines.flow.map
import java.util.Locale
import java.util.UUID

/** Focused long-press presentation; all capabilities and dispatch remain native to the owning message. */
@Composable
@Suppress("FunctionNaming", "LongParameterList", "LongMethod", "CyclomaticComplexMethod")
internal fun MessageActionMenu(
    expanded: Boolean,
    initiatingHoldActive: Boolean = false,
    anchorBoundsInWindow: IntRect?,
    anchorWindowYPx: Float?,
    canReply: Boolean,
    canReact: Boolean,
    canDelete: Boolean,
    canEdit: Boolean,
    canForward: Boolean,
    forwardBlockedReason: ForwardBlockedReason? = null,
    canSelect: Boolean,
    canCopyText: Boolean,
    canSpeak: Boolean,
    canSpeakCodeLiterally: Boolean = false,
    canTranslate: Boolean = false,
    canSelectText: Boolean,
    canKeepOnScreen: Boolean = false,
    canShowLess: Boolean = false,
    canShare: Boolean = false,
    canSave: Boolean,
    canInfo: Boolean = true,
    canReport: Boolean = false,
    quickReactionEmojis: List<String>,
    onDismissRequest: () -> Unit,
    onReact: (String) -> Unit,
    onReactionOrigin: ((String, androidx.compose.ui.geometry.Rect) -> Unit)? = null,
    onOpenEmojiPicker: () -> Unit,
    onReply: () -> Unit,
    onEdit: () -> Unit,
    onForward: () -> Unit,
    onSelect: () -> Unit,
    onSelectText: () -> Unit,
    onCopyText: () -> Unit,
    onSpeak: () -> Unit,
    onSpeakCodeLiterally: () -> Unit = {},
    onTranslate: () -> Unit = {},
    onKeepOnScreen: () -> Unit = {},
    onShowLess: () -> Unit = {},
    onShare: () -> Unit = {},
    onSave: () -> Unit,
    onInfo: () -> Unit,
    onReport: () -> Unit = {},
    onDelete: () -> Unit,
    mine: Boolean = false,
    selectedReactionEmojis: Set<String> = emptySet(),
    previewDescription: String = "",
    previewReady: Boolean = true,
    onInfoAt: ((Rect?) -> Unit)? = null,
    onForwardAt: ((Rect?) -> Unit)? = null,
    preview: (@Composable () -> Unit)? = null,
) {
    if (!expanded) return

    val explainedForwardBlock = forwardBlockedReason?.takeUnless { it == ForwardBlockedReason.Unsupported }
    val showForwardAction = canForward || explainedForwardBlock != null
    val actionKinds =
        remember(
            canReply,
            canEdit,
            canSelect,
            canSelectText,
            canCopyText,
            canSpeak,
            canSpeakCodeLiterally,
            canTranslate,
            showForwardAction,
            canKeepOnScreen,
            canShowLess,
            canShare,
            canSave,
            canInfo,
            canReport,
        ) {
            messageActionKinds(
                canReply = canReply,
                canEdit = canEdit,
                canSelect = canSelect,
                canSelectText = canSelectText,
                canCopyText = canCopyText,
                canSpeak = canSpeak,
                canSpeakCodeLiterally = canSpeakCodeLiterally,
                canTranslate = canTranslate,
                canForward = showForwardAction,
                canKeepOnScreen = canKeepOnScreen,
                canShowLess = canShowLess,
                canShare = canShare,
                canSave = canSave,
                canInfo = canInfo,
                canReport = canReport,
            )
        }
    val labeledActions: List<Pair<MessageActionKind?, String>> =
        buildList {
            actionKinds.forEach { kind -> add(kind to messageActionLabel(kind)) }
            if (canDelete) add(null to stringResource(R.string.delete))
        }
    val actions =
        labeledActions.map { (kind, label) ->
            FocusedMessageAction(
                label = label,
                supportingLabel =
                    if (kind == MessageActionKind.Forward) {
                        explainedForwardBlock?.let { forwardBlockedReasonLabel(it) }
                    } else {
                        null
                    },
                enabled = kind != MessageActionKind.Forward || canForward,
                onClickAt =
                    when (kind) {
                        MessageActionKind.Info -> onInfoAt
                        MessageActionKind.Forward -> onForwardAt
                        else -> null
                    },
                destructive = kind == null,
                icon = { MessageActionIcon(kind) },
                explanation = Explanation(label, stringResource(messageActionExplanationRes(kind))),
                onClick = {
                    when (kind) {
                        MessageActionKind.Reply -> onReply()
                        MessageActionKind.Edit -> onEdit()
                        MessageActionKind.Select -> onSelect()
                        MessageActionKind.SelectText -> onSelectText()
                        MessageActionKind.CopyText -> onCopyText()
                        MessageActionKind.Translate -> onTranslate()
                        MessageActionKind.Speak -> onSpeak()
                        MessageActionKind.SpeakCodeLiterally -> onSpeakCodeLiterally()
                        MessageActionKind.Forward -> onForward()
                        MessageActionKind.KeepOnScreen -> onKeepOnScreen()
                        MessageActionKind.ShowLess -> onShowLess()
                        MessageActionKind.Share -> onShare()
                        MessageActionKind.Save -> onSave()
                        MessageActionKind.Info -> onInfo()
                        MessageActionKind.Report -> onReport()
                        null -> onDelete()
                    }
                },
            )
        }
    // The transcript can reflow as the menu dismisses the IME. Its lifted copy
    // stays anchored to the bubble at the original press position.
    val pressedBounds = remember { anchorBoundsInWindow }
    val pressedY = remember { anchorWindowYPx }
    FocusedMessageActions(
        initiatingHoldActive = initiatingHoldActive,
        sourceBounds = pressedBounds,
        touchY = pressedY,
        mine = mine,
        actions = actions,
        quickReactions = quickReactionEmojis,
        canReact = canReact,
        selectedReactions = selectedReactionEmojis,
        previewDescription = previewDescription,
        previewReady = previewReady,
        preview = preview,
        onReact = onReact,
        onReactionOrigin = onReactionOrigin,
        onMoreReactions = onOpenEmojiPicker,
        onDismiss = onDismissRequest,
    )
}

/** The plain-language body held on a menu row; the row's own label is its title. A null [kind] is Delete. */
@Suppress("CyclomaticComplexMethod") // One flat branch per action kind.
@StringRes
private fun messageActionExplanationRes(kind: MessageActionKind?): Int =
    when (kind) {
        MessageActionKind.Reply -> R.string.explanation_message_action_reply
        MessageActionKind.Edit -> R.string.explanation_message_action_edit
        MessageActionKind.Select -> R.string.explanation_message_action_select
        MessageActionKind.SelectText -> R.string.explanation_message_action_select_text
        MessageActionKind.CopyText -> R.string.explanation_message_action_copy
        MessageActionKind.Translate -> R.string.explanation_message_action_translate
        MessageActionKind.Speak -> R.string.explanation_message_action_read_aloud
        MessageActionKind.SpeakCodeLiterally -> R.string.explanation_message_action_read_code_literally
        MessageActionKind.Forward -> R.string.explanation_message_action_forward
        MessageActionKind.KeepOnScreen -> R.string.explanation_message_action_keep_on_screen
        MessageActionKind.ShowLess -> R.string.explanation_message_action_show_less
        MessageActionKind.Share -> R.string.explanation_message_action_share
        MessageActionKind.Save -> R.string.explanation_message_action_save
        MessageActionKind.Info -> R.string.explanation_message_action_info
        MessageActionKind.Report -> R.string.explanation_message_action_report
        null -> R.string.explanation_message_action_delete
    }

/** Leading-glyph size shared by every message action row. */
private val messageActionIconSize = 24.dp

/** Label explaining why forwarding is blocked. */
@Composable
internal fun forwardBlockedReasonLabel(reason: ForwardBlockedReason): String =
    when (reason) {
        ForwardBlockedReason.PendingAttachment -> stringResource(R.string.forward_blocked_pending)
        ForwardBlockedReason.FailedAttachment -> stringResource(R.string.forward_blocked_failed)
        ForwardBlockedReason.MalformedAttachment -> stringResource(R.string.forward_blocked_malformed)
        ForwardBlockedReason.ExpiredAttachment -> stringResource(R.string.forward_blocked_expired)
        ForwardBlockedReason.UnavailableAttachment -> stringResource(R.string.forward_blocked_unavailable)
        ForwardBlockedReason.RestrictedAttachment -> stringResource(R.string.forward_blocked_restricted)
        ForwardBlockedReason.Unsupported -> stringResource(R.string.forward_blocked_unsupported)
        ForwardBlockedReason.TooLong -> stringResource(R.string.forward_blocked_too_long)
    }

/**
 * Glyph for a message action row. A null [kind] is the destructive Delete row,
 * which the menu tints with the error colour on its own.
 */
@Composable
@Suppress("FunctionNaming")
private fun MessageActionIcon(kind: MessageActionKind?) {
    Icon(
        painter = painterResource(kind?.let(::messageActionIconRes) ?: R.drawable.ic_delete),
        contentDescription = null,
        modifier = Modifier.size(messageActionIconSize),
    )
}

/** Forward details: the operation summary, slow-aware preparation progress and one row per destination. */
@Composable
@Suppress("FunctionNaming", "LongMethod") // Exhaustive operation phases stay beside their progress semantics.
internal fun ForwardProgressContent(
    snapshot: ForwardOperationSnapshot,
    targetTitles: Map<String, String>,
    modifier: Modifier = Modifier,
) {
    val summary =
        when (snapshot.phase) {
            ForwardOperationPhase.Completed ->
                pluralStringResource(
                    R.plurals.forward_completed_summary,
                    snapshot.completedTargets,
                    snapshot.completedTargets,
                )
            ForwardOperationPhase.PartialFailure,
            ->
                stringResource(
                    R.string.forward_partial_summary,
                    snapshot.completedTargets,
                    snapshot.targets.size,
                )
            ForwardOperationPhase.Failed -> stringResource(R.string.forward_failed_summary)
            ForwardOperationPhase.Cancelled -> stringResource(R.string.forward_cancelled)
            ForwardOperationPhase.Cancelling -> stringResource(R.string.forward_cancelling)
            else -> stringResource(R.string.forward_progress_title)
        }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = summary,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(horizontal = Dimens.spaceLg).semantics { liveRegion = LiveRegionMode.Polite },
        )
        if (snapshot.phase == ForwardOperationPhase.Preparing && snapshot.totalAttachments > 0) {
            LinearProgressIndicator(
                progress = {
                    snapshot.preparedAttachments.toFloat() / snapshot.totalAttachments.toFloat()
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.spaceLg),
            )
            Text(
                text =
                    withSlowNotice(
                        stringResource(
                            R.string.forward_preparing_attachments,
                            snapshot.preparedAttachments,
                            snapshot.totalAttachments,
                        ),
                        stringResource(R.string.forward_preparing_slow).takeIf { snapshot.preparationSlow },
                    ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Dimens.spaceLg),
            )
        }
        WhiteNoiseLazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp),
            contentPadding = PaddingValues(bottom = 4.dp),
        ) {
            items(snapshot.targets, key = ForwardTargetProgress::groupIdHex) { progress ->
                ForwardTargetProgressRow(
                    progress = progress,
                    title =
                        targetTitles[progress.groupIdHex.lowercase(Locale.ROOT)]
                            ?: progress.groupIdHex.take(FORWARD_TARGET_TITLE_FALLBACK_LENGTH),
                )
            }
        }
    }
}

/** Appends a slow-phase [notice] on its own line beneath the progress [count], or returns the count alone. */
private fun withSlowNotice(
    count: String,
    notice: String?,
): String = if (notice == null) count else "$count\n$notice"

/** Projects a destination's transfer phase and actionable failure reason into one accessible row. */
@Composable
@Suppress("CyclomaticComplexMethod", "FunctionNaming", "LongMethod")
private fun ForwardTargetProgressRow(
    progress: ForwardTargetProgress,
    title: String,
) {
    val supportingText =
        when (progress.phase) {
            ForwardTargetPhase.Waiting -> stringResource(R.string.forward_waiting)
            // A slow phase keeps its count and spinner and adds the notice beneath, so real progress stays visible.
            ForwardTargetPhase.Uploading ->
                withSlowNotice(
                    stringResource(
                        R.string.forward_uploading_attachments,
                        progress.uploadedAttachments,
                        progress.totalAttachments,
                    ),
                    stringResource(R.string.forward_uploading_slow).takeIf { progress.slow },
                )
            // A slow send also says it can no longer be cancelled and will not be sent twice.
            ForwardTargetPhase.Sending ->
                withSlowNotice(
                    stringResource(
                        R.string.forward_sending_messages,
                        progress.sentMessages,
                        progress.totalMessages,
                    ),
                    stringResource(R.string.forward_sending_slow).takeIf { progress.slow },
                )
            ForwardTargetPhase.Completed -> stringResource(R.string.forward_sent)
            ForwardTargetPhase.Cancelled ->
                if (progress.sentMessages > 0) {
                    stringResource(
                        R.string.forward_failed_after_partial,
                        stringResource(R.string.forward_cancelled),
                        progress.sentMessages,
                        progress.totalMessages,
                    )
                } else {
                    stringResource(R.string.forward_cancelled)
                }
            ForwardTargetPhase.Failed -> {
                val failure =
                    when (progress.failureStage) {
                        ForwardFailureStage.Upload -> stringResource(R.string.forward_failed_upload)
                        ForwardFailureStage.UploadTimeout -> stringResource(R.string.forward_failed_upload_timeout)
                        ForwardFailureStage.PublishTimeout -> stringResource(R.string.forward_failed_publish_timeout)
                        ForwardFailureStage.Publish,
                        ForwardFailureStage.PublishRejected,
                        -> stringResource(R.string.forward_failed_publish)
                        ForwardFailureStage.PayloadTooLarge -> stringResource(R.string.forward_payload_too_large)
                        ForwardFailureStage.Expired -> stringResource(R.string.forward_failed_expired)
                        ForwardFailureStage.SessionChanged -> stringResource(R.string.forward_failed_session_changed)
                        ForwardFailureStage.PreparationTimeout ->
                            stringResource(R.string.forward_failed_preparation_timeout)
                        ForwardFailureStage.Materialize,
                        null,
                        -> stringResource(R.string.forward_failed_preparing)
                    }
                if (progress.sentMessages > 0) {
                    stringResource(
                        R.string.forward_failed_after_partial,
                        failure,
                        progress.sentMessages,
                        progress.totalMessages,
                    )
                } else {
                    failure
                }
            }
        }
    ListItem(
        modifier =
            Modifier.semantics {
                stateDescription = supportingText
            },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = { EmojiLabel(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        // Uncapped so slow and failure explanations stay readable in full at large text in every locale.
        supportingContent = { Text(supportingText) },
        leadingContent = {
            when (progress.phase) {
                ForwardTargetPhase.Uploading,
                ForwardTargetPhase.Sending,
                -> CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                ForwardTargetPhase.Completed ->
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                ForwardTargetPhase.Failed ->
                    Icon(
                        Icons.Default.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                ForwardTargetPhase.Cancelled ->
                    Icon(
                        Icons.Default.Close,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                ForwardTargetPhase.Waiting ->
                    Icon(
                        Icons.Default.Schedule,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
            }
        },
    )
}

/**
 * One forwarding request bound to its source conversation owner. The picker's
 * destination account and chat selections are mirrored into the encrypted
 * no-backup pending-request store so process recreation can restore an
 * unresolved request; explicit dismissal or acceptance discards that entry.
 */
@Composable
@Suppress("FunctionNaming")
internal fun ForwardMessageSheet(
    appState: WhiteNoiseAppState,
    payloads: List<ForwardMessagePayload>,
    sourceAccountRef: String?,
    originGroupIdHex: String,
    onDismiss: () -> Unit,
    restoredRequest: PendingForwardRequest? = null,
    origin: Rect? = null,
) {
    // Plain remember on purpose: the encrypted store owns recreation, and the
    // saved-state Bundle must not carry forward-request identifiers.
    val requestId =
        remember(originGroupIdHex) {
            restoredRequest?.requestId ?: UUID.randomUUID().toString()
        }
    var pickerTitles by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    val dismissAndDiscard = {
        appState.forwardRequestPersistence.discard(requestId)
        onDismiss()
    }
    ForwardMessagePickerFullScreen(
        origin = origin,
        appState = appState,
        messageCount = payloads.size,
        attachmentCount =
            payloads.sumOf { payload ->
                (payload as? ForwardMessagePayload.Media)?.attachments?.size ?: 0
            },
        originGroupIdHex = originGroupIdHex,
        sourceAccountRef = sourceAccountRef,
        onDismiss = dismissAndDiscard,
        onForward = { destinationAccountRef, targetGroupIds ->
            val started =
                sourceAccountRef != null &&
                    appState.startForwardMessages(
                        targetGroupIds = targetGroupIds,
                        messages = payloads,
                        sourceAccountRef = sourceAccountRef,
                        destinationAccountRef = destinationAccountRef,
                        targetTitles = pickerTitles,
                    )
            if (started) appState.forwardRequestPersistence.discard(requestId)
            started
        },
        initialDestinationAccountRef = restoredRequest?.destinationAccountRef,
        initialSelectedGroupIds = restoredRequest?.selectedGroupIds.orEmpty(),
        onPickerStateChanged = { destinationAccountRef, selectedGroupIds, titles ->
            pickerTitles = titles
            if (sourceAccountRef != null) {
                appState.forwardRequestPersistence.persist(
                    PendingForwardRequest(
                        requestId = requestId,
                        sourceAccountRef = sourceAccountRef,
                        originGroupIdHex = originGroupIdHex,
                        payloads = payloads,
                        destinationAccountRef = destinationAccountRef,
                        selectedGroupIds = selectedGroupIds,
                    ),
                )
            }
        },
    )
}

/** Outcome of validating one persisted forward request against a conversation. */
internal enum class RestoredForwardDisposition {
    Restore,
    Ignore,
    Discard,
}

/**
 * Decides whether a persisted forward request may be restored here. A request
 * belonging to another conversation or another source owner is ignored, and a
 * request whose source owner or previously chosen destination owner is no
 * longer a signed-in signing account is discarded — restoration must never
 * silently substitute another account for either bound owner.
 */
internal fun restoredForwardRequestDisposition(
    request: PendingForwardRequest,
    boundGroupIdHex: String,
    boundAccountRef: String?,
    isOwnerSignedIn: (String) -> Boolean,
): RestoredForwardDisposition =
    when {
        !request.originGroupIdHex.equals(boundGroupIdHex, ignoreCase = true) ->
            RestoredForwardDisposition.Ignore
        request.sourceAccountRef != boundAccountRef -> RestoredForwardDisposition.Ignore
        !isOwnerSignedIn(request.sourceAccountRef) -> RestoredForwardDisposition.Discard
        request.destinationAccountRef?.let { !isOwnerSignedIn(it) } == true ->
            RestoredForwardDisposition.Discard
        else -> RestoredForwardDisposition.Restore
    }

/**
 * Restores the single unresolved forward request after process recreation.
 * Restoration stays bound to the request's recorded source conversation and
 * source account and never substitutes another account for either bound
 * owner: a request whose source owner or previously chosen destination owner
 * is no longer a signed-in signing account is discarded with an explanation
 * instead of being restored.
 */
@Composable
@Suppress("FunctionNaming")
internal fun RestoredForwardRequestHost(
    appState: WhiteNoiseAppState,
    controller: ConversationController,
) {
    var restored by remember { mutableStateOf<PendingForwardRequest?>(null) }
    LaunchedEffect(controller.group.groupIdHex, controller.boundAccountRef) {
        val request = appState.forwardRequestPersistence.load() ?: return@LaunchedEffect
        when (
            restoredForwardRequestDisposition(
                request = request,
                boundGroupIdHex = controller.group.groupIdHex,
                boundAccountRef = controller.boundAccountRef,
                isOwnerSignedIn = { appState.isForwardOwnerSignedIn(it) },
            )
        ) {
            RestoredForwardDisposition.Ignore -> Unit
            RestoredForwardDisposition.Discard -> {
                appState.forwardRequestPersistence.discard(request.requestId)
                appState.presentTransient(R.string.forward_restore_discarded)
            }
            RestoredForwardDisposition.Restore -> restored = request
        }
    }
    restored?.let { request ->
        ForwardMessageSheet(
            appState = appState,
            payloads = request.payloads,
            sourceAccountRef = request.sourceAccountRef,
            originGroupIdHex = request.originGroupIdHex,
            onDismiss = { restored = null },
            restoredRequest = request,
        )
    }
}
