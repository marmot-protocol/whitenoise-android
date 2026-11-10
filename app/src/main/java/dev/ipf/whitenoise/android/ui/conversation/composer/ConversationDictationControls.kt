@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation.composer

import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.audio.ConversationDictationController
import dev.ipf.whitenoise.android.audio.ConversationDictationDeliveryMode
import dev.ipf.whitenoise.android.audio.ConversationDictationFailure
import dev.ipf.whitenoise.android.audio.ConversationDictationState
import dev.ipf.whitenoise.android.audio.requiresCompletionControls
import dev.ipf.whitenoise.android.ui.common.AccountActionColors
import dev.ipf.whitenoise.android.ui.common.accountActionColors
import dev.ipf.whitenoise.android.ui.common.rememberSystemAnimationsEnabled
import dev.ipf.whitenoise.android.ui.common.settleControlMorph
import dev.ipf.whitenoise.android.ui.icons.Icons
import dev.ipf.whitenoise.android.ui.icons.filled.Close
import dev.ipf.whitenoise.android.ui.icons.filled.ContentPaste

internal const val COMPOSER_DICTATION_STRIP_TAG = "composer-dictation-strip"
internal const val COMPOSER_DICTATION_COMPACT_ACTIONS_TAG = "composer-dictation-compact-actions"
internal const val APP_DICTATION_CONTROL_TAG = "app-dictation-control"
internal const val DICTATION_LISTENING_INDICATOR_TAG = "dictation-listening-indicator"
internal const val DICTATION_PROGRESS_TAG = "dictation-progress"

/** A compact 24dp listening pulse followed by three adjacent compact commands. */
internal val DICTATION_ACTIVE_ACTIONS_WIDTH = 132.dp
internal const val DICTATION_ACTIONS_RESIZE_MILLIS = 200
internal val DICTATION_LISTENING_PULSE_WIDTH = 24.dp

/**
 * Controller facts the dictation commands paint from. The floating capsule's exit freezes them
 * with its state, so the closing capsule cannot restyle or resize while it shrinks away.
 */
internal data class DictationControlFacts(
    val captureInProgress: Boolean,
    val deliveryInProgress: Boolean,
    val completionActionsEnabled: Boolean,
    val processingDeliveryMode: ConversationDictationDeliveryMode?,
    val recoveryHandedToComposer: Boolean,
)

internal val ConversationDictationController.controlFacts: DictationControlFacts
    get() =
        DictationControlFacts(
            captureInProgress = captureInProgress,
            deliveryInProgress = deliveryInProgress,
            completionActionsEnabled = completionActionsEnabled,
            processingDeliveryMode = processingDeliveryMode,
            recoveryHandedToComposer = recoveryHandedToComposer,
        )

/** Floating app-root control used while the immutable dictation origin is not visible. */
@Composable
internal fun ConversationDictationPersistentControl(
    state: ConversationDictationState,
    controller: ConversationDictationController,
    modifier: Modifier = Modifier,
    facts: DictationControlFacts = controller.controlFacts,
    embedded: Boolean = false,
    chromeVisibility: () -> Float = { if (embedded) 0f else 1f },
    movingChromeSize: () -> Size? = { null },
    actionColors: AccountActionColors = accountActionColors(appState = null),
) {
    if (!state.requiresCompletionControls(facts.recoveryHandedToComposer)) return
    val status =
        if (facts.deliveryInProgress) {
            stringResource(R.string.message_status_pending)
        } else {
            dictationStatusLabel(state, facts.captureInProgress)
        }
    val fill = MaterialTheme.colorScheme.surfaceContainerHigh
    val border = MaterialTheme.colorScheme.outlineVariant
    val contentColor = contentColorFor(if (embedded) Color.Transparent else fill)
    Box(
        modifier =
            modifier
                .testTag(if (embedded) "composer-dictation-shell-control" else APP_DICTATION_CONTROL_TAG)
                .persistentDictationChrome(fill, border, status, chromeVisibility, movingChromeSize),
    ) {
        Layout(
            content = {
                // Match Material Surface foreground inheritance without adding another painted container.
                CompositionLocalProvider(LocalContentColor provides contentColor) {
                    // This fixed recovery explanation is not a transcript. Retain its composition while
                    // changing owners so the shared clock can reveal it without replacing the commands.
                    if (state is ConversationDictationState.Failed) {
                        Text(
                            text = status,
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier =
                                Modifier
                                    .padding(start = 12.dp)
                                    .graphicsLayer { alpha = chromeVisibility().coerceIn(0f, 1f) }
                                    .then(if (embedded) Modifier.clearAndSetSemantics {} else Modifier),
                        )
                    }
                    ConversationDictationCompactActions(
                        state = state,
                        controller = controller,
                        facts = facts,
                        actionColors = actionColors,
                    )
                }
            },
        ) { measurables, constraints ->
            measurePersistentDictation(measurables, constraints, embedded)
        }
    }
}

/** Compact Cancel/Paste/Send controls shared by the origin composer and floating capsule. */
@Composable
internal fun ConversationDictationCompactActions(
    state: ConversationDictationState,
    controller: ConversationDictationController,
    modifier: Modifier = Modifier,
    facts: DictationControlFacts = controller.controlFacts,
    actionColors: AccountActionColors = accountActionColors(appState = null),
) {
    if (!state.requiresCompletionControls(facts.recoveryHandedToComposer)) return
    val status =
        if (facts.deliveryInProgress) {
            stringResource(R.string.message_status_pending)
        } else {
            dictationStatusLabel(state, facts.captureInProgress)
        }

    // Capture stopping and recovery both change the command width; ease each edge instead of snapping.
    val motion = rememberSystemAnimationsEnabled()
    val width by animateDpAsState(
        targetValue = state.compactActionsWidth(facts.captureInProgress),
        animationSpec = if (motion) tween(DICTATION_ACTIONS_RESIZE_MILLIS) else snap(),
        label = "dictation actions width",
    )
    Box(
        modifier =
            modifier
                .width(width)
                .testTag(COMPOSER_DICTATION_COMPACT_ACTIONS_TAG)
                .semantics {
                    liveRegion = LiveRegionMode.Polite
                    stateDescription = status
                },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End,
        ) {
            if (state.hasActiveRecognitionActions) {
                ConversationDictationActiveActions(controller, facts, actionColors)
            } else {
                ConversationDictationPrimaryAction(
                    state = state,
                    controller = controller,
                    status = status,
                )
                ConversationDictationDismissAction(state, controller)
            }
        }
    }
    if (state is ConversationDictationState.Failed && state.reason == ConversationDictationFailure.MicrophoneMuted) {
        val context = LocalContext.current
        ConversationDictationMicrophoneDialog(
            onDismiss = controller::dismissFailure,
            onOpenSettings = {
                controller.dismissFailure()
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_PRIVACY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            },
        )
    }
}

/** Explains system microphone blocking before any silent recognition session starts. */
@Composable
internal fun ConversationDictationMicrophoneDialog(
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("dictation-microphone-dialog"),
        title = { Text(stringResource(R.string.dictation_microphone_muted)) },
        text = { Text(stringResource(R.string.dictation_microphone_muted_help)) },
        confirmButton = {
            TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.open_settings)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

private val ConversationDictationState.hasActiveRecognitionActions: Boolean
    get() =
        this is ConversationDictationState.Starting ||
            this is ConversationDictationState.Listening ||
            this is ConversationDictationState.Processing

/**
 * Active recognition keeps room for the 24dp listening pulse only while capture runs. Paste or Send
 * stops capture, and the capsule narrows with the pulse instead of keeping its blank slot.
 */
private fun ConversationDictationState.compactActionsWidth(captureInProgress: Boolean) =
    when {
        !hasActiveRecognitionActions -> 96.dp
        captureInProgress -> DICTATION_ACTIVE_ACTIONS_WIDTH
        else -> DICTATION_ACTIVE_ACTIONS_WIDTH - DICTATION_LISTENING_PULSE_WIDTH
    }

/**
 * Keeps all three explicit outcomes visible for the lifetime of app-owned recognition.
 */
@Composable
private fun ConversationDictationActiveActions(
    controller: ConversationDictationController,
    facts: DictationControlFacts,
    actionColors: AccountActionColors,
) {
    val sendLabel = stringResource(R.string.send)
    ConversationDictationListeningSlot(facts.captureInProgress)
    IconButton(
        onClick = settleControlMorph(controller::cancel),
        enabled = !facts.deliveryInProgress,
        modifier = Modifier.width(ComposerShortcutPitch).height(ComposerShortcutTarget),
    ) {
        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cancel))
    }
    ConversationDictationPasteAction(controller, facts)
    if (facts.processingDeliveryMode == ConversationDictationDeliveryMode.SendOnFinish) {
        IconButton(
            onClick = settleControlMorph(controller::send),
            enabled = facts.completionActionsEnabled,
            modifier =
                Modifier.width(ComposerShortcutPitch).height(ComposerShortcutTarget).semantics {
                    contentDescription = sendLabel
                    selected = true
                    progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
                },
        ) {
            CircularProgressIndicator(
                modifier =
                    Modifier
                        .size(22.dp)
                        .testTag(DICTATION_PROGRESS_TAG)
                        .clearAndSetSemantics {},
                strokeWidth = 2.dp,
            )
        }
    } else {
        // The same disc the composer sends with: sending a dictation is sending a message, and the
        // two should not look like different actions.
        ComposerActionDisc(
            onClick = settleControlMorph(controller::send),
            containerColor = actionColors.container,
            contentColor = actionColors.content,
            description = sendLabel,
            icon = R.drawable.ic_arrow_upward,
            enabled = facts.completionActionsEnabled,
            modifier = Modifier.semantics { selected = false },
            // Keep commands adjacent while retaining Material minimum touch expansion.
            width = ComposerShortcutPitch,
        )
    }
}

/** Pastes the owned transcript and shows progress while that completion action is processing. */
@Composable
private fun ConversationDictationPasteAction(
    controller: ConversationDictationController,
    facts: DictationControlFacts,
) {
    val pasteLabel = stringResource(R.string.paste_dictation_transcript)
    IconButton(
        onClick = settleControlMorph(controller::paste),
        enabled = facts.completionActionsEnabled,
        modifier =
            Modifier.width(ComposerShortcutPitch).height(ComposerShortcutTarget).semantics {
                contentDescription = pasteLabel
                selected = facts.processingDeliveryMode == ConversationDictationDeliveryMode.PasteIntoDraft
                if (facts.processingDeliveryMode == ConversationDictationDeliveryMode.PasteIntoDraft) {
                    progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
                }
            },
    ) {
        if (facts.processingDeliveryMode == ConversationDictationDeliveryMode.PasteIntoDraft) {
            CircularProgressIndicator(
                modifier =
                    Modifier
                        .size(22.dp)
                        .testTag(DICTATION_PROGRESS_TAG)
                        .clearAndSetSemantics {},
                strokeWidth = 2.dp,
            )
        } else {
            Icon(Icons.Default.ContentPaste, contentDescription = null)
        }
    }
}

/** Shows recovery or progress in the first compact action slot. */
@Composable
private fun ConversationDictationPrimaryAction(
    state: ConversationDictationState,
    controller: ConversationDictationController,
    status: String,
) {
    when (state) {
        is ConversationDictationState.Failed ->
            if (state.reason != ConversationDictationFailure.DeliveryUnknown) {
                ConversationDictationFailureAction(state, controller)
            }
        else ->
            CircularProgressIndicator(
                modifier =
                    Modifier
                        .size(48.dp)
                        .padding(14.dp)
                        .semantics { contentDescription = status },
                strokeWidth = 2.dp,
            )
    }
}

/** Keeps terminal discard/dismiss semantics separate from active-session cancellation. */
@Composable
private fun ConversationDictationDismissAction(
    state: ConversationDictationState,
    controller: ConversationDictationController,
) {
    val onClick =
        when (state) {
            is ConversationDictationState.Failed -> controller::dismissFailure
            else -> controller::cancel
        }
    val label =
        when (state) {
            is ConversationDictationState.Failed -> R.string.dismiss
            else -> R.string.dictation_cancel
        }
    IconButton(
        onClick = settleControlMorph(onClick),
        modifier = Modifier.width(ComposerShortcutPitch).height(ComposerShortcutTarget),
    ) {
        Icon(
            Icons.Default.Close,
            contentDescription = stringResource(label),
        )
    }
}

/**
 * Maps controller phases to concise, localized live-region announcements.
 *
 * The Failed label is the control's persistent state, kept beside Cancel, Paste and Send with the retained
 * transcript until it is dismissed or acted on. A blocked send also raises one temporary banner at the top of the
 * owning screen through the controller's `onSendBlocked`, that banner is the notification of the event and times
 * out, this label is the recovery state, so the two are one event with its notice and its control, not a duplicate
 * presentation (#3312, manual case CON-034).
 */
@Composable
private fun dictationStatusLabel(
    state: ConversationDictationState,
    captureInProgress: Boolean,
): String =
    when (state) {
        is ConversationDictationState.ProviderSelectionRequired,
        is ConversationDictationState.DisclosureRequired,
        is ConversationDictationState.PermissionRequired,
        -> stringResource(R.string.dictation_preparing)
        is ConversationDictationState.CheckingProvider -> stringResource(R.string.dictation_checking_service)
        is ConversationDictationState.ProviderActivityRequired -> stringResource(R.string.dictation_opening_service)
        is ConversationDictationState.ProviderActivityActive -> stringResource(R.string.dictation_service_open)
        is ConversationDictationState.Starting -> stringResource(R.string.dictation_starting)
        is ConversationDictationState.Listening -> stringResource(R.string.dictation_listening)
        is ConversationDictationState.Processing ->
            stringResource(
                if (captureInProgress) R.string.dictation_listening else R.string.dictation_processing,
            )
        is ConversationDictationState.Failed -> {
            val failureLabel = dictationFailureLabel(state.cause ?: state.reason)
            if (state.reason == ConversationDictationFailure.SendBlocked && state.cause != null) {
                "${stringResource(R.string.dictation_send_blocked)} · $failureLabel"
            } else {
                failureLabel
            }
        }
        is ConversationDictationState.Idle -> ""
    }

/**
 * Maps terminal recognition failures to actionable, localized status text.
 *
 * NoSpeech and NoMatch share "No speech was recognized"; the label does not claim the PCM was silent.
 * Their internal distinction controls retry policy and diagnostics without changing recovery copy.
 */
@Composable
@Suppress("CyclomaticComplexMethod") // Exhaustive enum-to-resource mapping is intentionally flat.
internal fun dictationFailureLabel(reason: ConversationDictationFailure): String =
    stringResource(
        when (reason) {
            ConversationDictationFailure.ProviderUnavailable -> R.string.dictation_provider_unavailable
            ConversationDictationFailure.ProviderAccessRejected -> R.string.dictation_provider_access_rejected
            ConversationDictationFailure.PermissionDenied -> R.string.dictation_permission_denied
            ConversationDictationFailure.PermissionPermanentlyDenied -> R.string.dictation_permission_denied_permanently
            ConversationDictationFailure.MicrophoneMuted -> R.string.dictation_microphone_muted
            ConversationDictationFailure.MicrophoneInUse -> R.string.dictation_microphone_in_use
            ConversationDictationFailure.AudioFocusUnavailable -> R.string.dictation_audio_focus_unavailable
            ConversationDictationFailure.Interrupted -> R.string.dictation_interrupted
            ConversationDictationFailure.NoSpeech,
            ConversationDictationFailure.NoMatch,
            -> R.string.dictation_no_speech
            ConversationDictationFailure.Network -> R.string.dictation_network_error
            ConversationDictationFailure.ProviderDisconnected -> R.string.dictation_provider_unavailable
            ConversationDictationFailure.RecognizerBusy -> R.string.dictation_recognizer_busy
            ConversationDictationFailure.AudioBufferFull -> R.string.dictation_audio_buffer_full
            ConversationDictationFailure.TimedOut -> R.string.dictation_timed_out
            ConversationDictationFailure.SendBlocked -> R.string.dictation_send_blocked
            ConversationDictationFailure.DeliveryUnknown -> R.string.delivery_not_confirmed
            ConversationDictationFailure.Unknown -> R.string.dictation_failed
            ConversationDictationFailure.DraftWriteDeferred -> R.string.dictation_waiting_for_unlock
            ConversationDictationFailure.DraftWriteRefused -> R.string.toast_draft_save_failed
        },
    )
