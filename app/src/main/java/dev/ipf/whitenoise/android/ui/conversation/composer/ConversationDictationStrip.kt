@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation.composer

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.KeyboardVoice
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.audio.ConversationDictationController
import dev.ipf.whitenoise.android.audio.ConversationDictationFailure
import dev.ipf.whitenoise.android.audio.ConversationDictationState
import kotlinx.coroutines.delay
import android.provider.Settings as AndroidSettings

internal const val COMPOSER_DICTATION_STRIP_TAG = "composer-dictation-strip"
internal const val COMPOSER_DICTATION_REVIEW_DIALOG_TAG = "composer-dictation-review-dialog"

@Composable
@Suppress("CyclomaticComplexMethod", "LongMethod")
internal fun ConversationDictationStrip(
    state: ConversationDictationState,
    controller: ConversationDictationController,
    modifier: Modifier = Modifier,
) {
    if (state is ConversationDictationState.Idle) return

    val status = dictationStatusLabel(state)
    var reviewDialogOpen by remember(state.sessionId) { mutableStateOf(false) }
    val context = LocalContext.current
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(22.dp),
        modifier =
            modifier
                .testTag(COMPOSER_DICTATION_STRIP_TAG)
                .semantics {
                    liveRegion = LiveRegionMode.Polite
                    stateDescription = status
                },
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when (state) {
                is ConversationDictationState.Listening -> ListeningIndicator(state.startedAtElapsedMillis)
                is ConversationDictationState.Failed ->
                    Icon(
                        Icons.Default.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp),
                    )
                is ConversationDictationState.ReviewRequired ->
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                else ->
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
            }
            Text(
                text = status,
                style = MaterialTheme.typography.labelLarge,
                color =
                    if (state is ConversationDictationState.Failed) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.weight(1f))
            when (state) {
                is ConversationDictationState.Starting,
                is ConversationDictationState.Listening,
                ->
                    IconButton(
                        onClick = controller::stop,
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            Icons.Default.Stop,
                            contentDescription = stringResource(R.string.dictation_stop),
                        )
                    }
                is ConversationDictationState.Failed ->
                    IconButton(
                        onClick =
                            if (state.reason == ConversationDictationFailure.PermissionPermanentlyDenied) {
                                { openDictationAppSettings(context) }
                            } else {
                                controller::retry
                            },
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            if (state.reason == ConversationDictationFailure.PermissionPermanentlyDenied) {
                                Icons.Default.Settings
                            } else {
                                Icons.Default.Refresh
                            },
                            contentDescription =
                                stringResource(
                                    if (state.reason == ConversationDictationFailure.PermissionPermanentlyDenied) {
                                        R.string.open_app_settings
                                    } else {
                                        R.string.retry
                                    },
                                ),
                        )
                    }
                is ConversationDictationState.ReviewRequired ->
                    IconButton(
                        onClick = { reviewDialogOpen = true },
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = stringResource(R.string.dictation_review_action),
                        )
                    }
                else -> Unit
            }
            IconButton(
                onClick =
                    if (state is ConversationDictationState.Failed) {
                        controller::dismissFailure
                    } else {
                        controller::cancel
                    },
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription =
                        stringResource(
                            when (state) {
                                is ConversationDictationState.Failed -> R.string.dismiss
                                is ConversationDictationState.ReviewRequired -> R.string.dictation_discard_transcript
                                else -> R.string.dictation_cancel
                            },
                        ),
                )
            }
        }
    }

    if (reviewDialogOpen && state is ConversationDictationState.ReviewRequired) {
        ConversationDictationReviewDialog(
            transcript = state.transcript,
            onInsert = {
                reviewDialogOpen = false
                controller.insertReviewAtEnd()
            },
            onDiscard = {
                reviewDialogOpen = false
                controller.dismissReview()
            },
            onDismiss = { reviewDialogOpen = false },
        )
    }
}

@Suppress("DEPRECATION")
@Composable
private fun ConversationDictationReviewDialog(
    transcript: String,
    onInsert: () -> Unit,
    onDiscard: () -> Unit,
    onDismiss: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dictation_review_title)) },
        text = { Text(transcript) },
        confirmButton = {
            Row {
                TextButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(transcript))
                        onDiscard()
                    },
                ) {
                    Text(stringResource(R.string.copy))
                }
                TextButton(onClick = onInsert) {
                    Text(stringResource(R.string.dictation_insert_at_end))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDiscard) {
                Text(stringResource(R.string.discard))
            }
        },
        modifier = Modifier.testTag(COMPOSER_DICTATION_REVIEW_DIALOG_TAG),
    )
}

@Composable
private fun ListeningIndicator(startedAtElapsedMillis: Long) {
    val pulseScale by rememberInfiniteRecordingPulse()
    var elapsedMillis by remember(startedAtElapsedMillis) { mutableLongStateOf(0L) }
    LaunchedEffect(startedAtElapsedMillis) {
        while (true) {
            elapsedMillis = (SystemClock.elapsedRealtime() - startedAtElapsedMillis).coerceAtLeast(0L)
            delay(DICTATION_TIMER_UPDATE_MILLIS)
        }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = Icons.Default.KeyboardVoice,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier =
                Modifier
                    .size(20.dp)
                    .graphicsLayer {
                        val scale = 0.85f + ((pulseScale - 1f) * 0.25f)
                        scaleX = scale
                        scaleY = scale
                    },
        )
        Text(
            text = formatRecordingDuration(elapsedMillis),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun dictationStatusLabel(state: ConversationDictationState): String =
    when (state) {
        is ConversationDictationState.DisclosureRequired,
        is ConversationDictationState.PermissionRequired,
        -> stringResource(R.string.dictation_preparing)
        is ConversationDictationState.Starting -> stringResource(R.string.dictation_starting)
        is ConversationDictationState.Listening -> stringResource(R.string.dictation_listening)
        is ConversationDictationState.Processing -> stringResource(R.string.dictation_processing)
        is ConversationDictationState.Failed -> dictationFailureLabel(state.reason)
        is ConversationDictationState.ReviewRequired -> stringResource(R.string.dictation_review_required)
        is ConversationDictationState.Idle -> ""
    }

@Composable
private fun dictationFailureLabel(reason: ConversationDictationFailure): String =
    stringResource(
        when (reason) {
            ConversationDictationFailure.ProviderUnavailable -> R.string.dictation_provider_unavailable
            ConversationDictationFailure.PermissionDenied -> R.string.dictation_permission_denied
            ConversationDictationFailure.PermissionPermanentlyDenied -> R.string.dictation_permission_denied_permanently
            ConversationDictationFailure.MicrophoneInUse -> R.string.dictation_microphone_in_use
            ConversationDictationFailure.NoSpeech -> R.string.dictation_no_speech
            ConversationDictationFailure.Network -> R.string.dictation_network_error
            ConversationDictationFailure.RecognizerBusy -> R.string.dictation_recognizer_busy
            ConversationDictationFailure.TimedOut -> R.string.dictation_timed_out
            ConversationDictationFailure.Unknown -> R.string.dictation_failed
        },
    )

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

private const val DICTATION_TIMER_UPDATE_MILLIS = 250L
