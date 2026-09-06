@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation.composer

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.audio.ConversationDictationController
import dev.ipf.whitenoise.android.audio.ConversationDictationFailure
import dev.ipf.whitenoise.android.audio.ConversationDictationState
import android.provider.Settings as AndroidSettings

internal const val COMPOSER_DICTATION_STRIP_TAG = "composer-dictation-strip"
internal const val COMPOSER_DICTATION_REVIEW_DIALOG_TAG = "composer-dictation-review-dialog"
internal const val COMPOSER_DICTATION_COMPACT_ACTIONS_TAG = "composer-dictation-compact-actions"
internal const val APP_DICTATION_CONTROL_TAG = "app-dictation-control"
internal const val DICTATION_PROGRESS_TAG = "dictation-progress"

/** App-root bottom control used while the immutable dictation origin is not visible. */
@Composable
internal fun ConversationDictationPersistentControl(
    state: ConversationDictationState,
    controller: ConversationDictationController,
    modifier: Modifier = Modifier,
) {
    if (state is ConversationDictationState.Idle) return
    val status =
        if (controller.deliveryInProgress) {
            stringResource(R.string.message_status_pending)
        } else {
            dictationStatusLabel(state)
        }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 2.dp,
        modifier =
            modifier
                .fillMaxWidth()
                .testTag(APP_DICTATION_CONTROL_TAG)
                .semantics {
                    liveRegion = LiveRegionMode.Polite
                    stateDescription = status
                },
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = status,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            ConversationDictationCompactActions(
                state = state,
                controller = controller,
            )
        }
    }
}

/** Compact Cancel/Paste/Send controls shared by the origin composer and app-root bar. */
@Composable
internal fun ConversationDictationCompactActions(
    state: ConversationDictationState,
    controller: ConversationDictationController,
    modifier: Modifier = Modifier,
) {
    if (state is ConversationDictationState.Idle) return
    val status =
        if (controller.deliveryInProgress) {
            stringResource(R.string.message_status_pending)
        } else {
            dictationStatusLabel(state)
        }
    var reviewDialogOpen by remember(state.sessionId) { mutableStateOf(false) }
    Box(
        modifier =
            modifier
                .width(if (state.hasActiveRecognitionActions) 144.dp else 96.dp)
                .testTag(COMPOSER_DICTATION_COMPACT_ACTIONS_TAG)
                .semantics {
                    liveRegion = LiveRegionMode.Polite
                    stateDescription = status
                },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End,
        ) {
            if (state.hasActiveRecognitionActions) {
                ConversationDictationActiveActions(controller)
            } else {
                ConversationDictationPrimaryAction(
                    state = state,
                    controller = controller,
                    status = status,
                    onReview = { reviewDialogOpen = true },
                )
                ConversationDictationDismissAction(state, controller)
            }
        }
        if (state is ConversationDictationState.Starting || state is ConversationDictationState.Processing) {
            LinearProgressIndicator(
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .testTag(DICTATION_PROGRESS_TAG),
            )
        }
    }
    ConversationDictationSessionDialogs(state, controller, reviewDialogOpen) { reviewDialogOpen = false }
}

private val ConversationDictationState.hasActiveRecognitionActions: Boolean
    get() =
        this is ConversationDictationState.Starting ||
            this is ConversationDictationState.Listening ||
            this is ConversationDictationState.Processing

/** Keeps all three explicit outcomes visible for the lifetime of app-owned recognition. */
@Composable
private fun ConversationDictationActiveActions(controller: ConversationDictationController) {
    IconButton(
        onClick = controller::cancel,
        enabled = !controller.deliveryInProgress,
        modifier = Modifier.size(48.dp),
    ) {
        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cancel))
    }
    IconButton(
        onClick = controller::paste,
        enabled = controller.completionActionsEnabled,
        modifier = Modifier.size(48.dp),
    ) {
        Icon(Icons.Default.ContentPaste, contentDescription = stringResource(R.string.paste))
    }
    IconButton(
        onClick = controller::send,
        enabled = controller.completionActionsEnabled,
        modifier = Modifier.size(48.dp),
    ) {
        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.send))
    }
}

/** Shows recovery, review, or progress in the first compact action slot. */
@Composable
private fun ConversationDictationPrimaryAction(
    state: ConversationDictationState,
    controller: ConversationDictationController,
    status: String,
    onReview: () -> Unit,
) {
    when (state) {
        is ConversationDictationState.Failed -> ConversationDictationFailureAction(state, controller)
        is ConversationDictationState.ReviewRequired,
        is ConversationDictationState.DeliveryUnknown,
        ->
            IconButton(onClick = onReview, modifier = Modifier.size(48.dp)) {
                Icon(
                    Icons.Default.Edit,
                    contentDescription = stringResource(R.string.dictation_review_action),
                )
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

/** Chooses settings after permanent denial and retry for recoverable failures. */
@Composable
private fun ConversationDictationFailureAction(
    state: ConversationDictationState.Failed,
    controller: ConversationDictationController,
) {
    val context = LocalContext.current
    val permanentlyDenied = state.reason == ConversationDictationFailure.PermissionPermanentlyDenied
    IconButton(
        onClick = if (permanentlyDenied) ({ openDictationAppSettings(context) }) else controller::retry,
        modifier = Modifier.size(48.dp),
    ) {
        Icon(
            imageVector = if (permanentlyDenied) Icons.Default.Settings else Icons.Default.Refresh,
            contentDescription =
                stringResource(
                    if (permanentlyDenied) R.string.open_app_settings else R.string.retry,
                ),
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
            is ConversationDictationState.ReviewRequired -> controller::dismissReview
            is ConversationDictationState.DeliveryUnknown -> controller::dismissDeliveryUnknown
            else -> controller::cancel
        }
    val label =
        when (state) {
            is ConversationDictationState.Failed -> R.string.dismiss
            is ConversationDictationState.ReviewRequired -> R.string.dictation_discard_transcript
            is ConversationDictationState.DeliveryUnknown -> R.string.dictation_discard_transcript
            else -> R.string.dictation_cancel
        }
    IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) {
        Icon(
            Icons.Default.Close,
            contentDescription = stringResource(label),
        )
    }
}

/** Maps controller phases to concise, localized live-region announcements. */
@Composable
private fun dictationStatusLabel(state: ConversationDictationState): String =
    when (state) {
        is ConversationDictationState.DisclosureRequired,
        is ConversationDictationState.PermissionRequired,
        -> stringResource(R.string.dictation_preparing)
        is ConversationDictationState.CheckingProvider -> stringResource(R.string.dictation_checking_service)
        is ConversationDictationState.ProviderActivityRequired -> stringResource(R.string.dictation_opening_service)
        is ConversationDictationState.ProviderActivityActive -> stringResource(R.string.dictation_service_open)
        is ConversationDictationState.Starting -> stringResource(R.string.dictation_starting)
        is ConversationDictationState.Listening -> stringResource(R.string.dictation_listening)
        is ConversationDictationState.Processing -> stringResource(R.string.dictation_processing)
        is ConversationDictationState.Failed -> dictationFailureLabel(state.reason)
        is ConversationDictationState.ReviewRequired -> stringResource(R.string.dictation_review_required)
        is ConversationDictationState.DeliveryUnknown -> stringResource(R.string.delivery_not_confirmed)
        is ConversationDictationState.Idle -> ""
    }

/** Maps terminal recognition failures to actionable, localized status text. */
@Composable
private fun dictationFailureLabel(reason: ConversationDictationFailure): String =
    stringResource(
        when (reason) {
            ConversationDictationFailure.ProviderUnavailable -> R.string.dictation_provider_unavailable
            ConversationDictationFailure.PermissionDenied -> R.string.dictation_permission_denied
            ConversationDictationFailure.PermissionPermanentlyDenied -> R.string.dictation_permission_denied_permanently
            ConversationDictationFailure.MicrophoneMuted -> R.string.dictation_microphone_muted
            ConversationDictationFailure.MicrophoneInUse -> R.string.dictation_microphone_in_use
            ConversationDictationFailure.NoSpeech -> R.string.dictation_no_speech
            ConversationDictationFailure.Network -> R.string.dictation_network_error
            ConversationDictationFailure.RecognizerBusy -> R.string.dictation_recognizer_busy
            ConversationDictationFailure.TimedOut -> R.string.dictation_timed_out
            ConversationDictationFailure.Unknown -> R.string.dictation_failed
        },
    )

/** Opens White Noise's Android permission page after a permanent microphone denial. */
private fun openDictationAppSettings(context: android.content.Context) {
    runCatching {
        context.startActivity(
            Intent(
                AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
