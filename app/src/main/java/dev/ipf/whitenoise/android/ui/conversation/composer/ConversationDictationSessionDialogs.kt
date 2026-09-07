@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation.composer

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.audio.ConversationDictationController
import dev.ipf.whitenoise.android.audio.ConversationDictationFailure
import dev.ipf.whitenoise.android.audio.ConversationDictationState

/** Keeps recovery and retained-transcript dialogs separate from the compact action layout. */
@Composable
internal fun ConversationDictationSessionDialogs(
    state: ConversationDictationState,
    controller: ConversationDictationController,
    reviewDialogOpen: Boolean,
    onCloseReview: () -> Unit,
) {
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
    if (reviewDialogOpen && state is ConversationDictationState.ReviewRequired) {
        ConversationDictationReviewDialog(
            transcript = state.transcript,
            onInsert = {
                onCloseReview()
                controller.insertReviewAtEnd()
            },
            onDiscard = {
                onCloseReview()
                controller.dismissReview()
            },
            onDismiss = onCloseReview,
        )
    } else if (reviewDialogOpen && state is ConversationDictationState.DeliveryUnknown) {
        ConversationDictationReviewDialog(
            transcript = state.transcript,
            titleRes = R.string.delivery_not_confirmed,
            onInsert = null,
            onDiscard = {
                onCloseReview()
                controller.dismissDeliveryUnknown()
            },
            onDismiss = onCloseReview,
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

/** Copy closes the dialog but retains the transcript until explicit insertion or discard. */
@Suppress("DEPRECATION")
@Composable
private fun ConversationDictationReviewDialog(
    transcript: String,
    onInsert: (() -> Unit)?,
    onDiscard: () -> Unit,
    onDismiss: () -> Unit,
    titleRes: Int = R.string.dictation_review_title,
) {
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(titleRes)) },
        text = { Text(transcript) },
        confirmButton = {
            Row {
                TextButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(transcript))
                        onDismiss()
                    },
                ) {
                    Text(stringResource(R.string.copy))
                }
                if (onInsert != null) {
                    TextButton(onClick = onInsert) {
                        Text(stringResource(R.string.dictation_insert_at_end))
                    }
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
