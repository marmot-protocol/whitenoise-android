package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import dev.ipf.whitenoise.android.R
import java.security.MessageDigest

/** Only an opaque account/chat binding and presence bit survive recreation; no file, audio, key, or duration does. */
internal class VoiceReviewRecoveryMarker(
    val ownerKey: String,
    pending: Boolean = false,
) {
    var hasReview by mutableStateOf(pending)
        private set
    private var attached = true

    /** Called synchronously when native review ownership changes through an explicit user action. */
    fun updatePresence(present: Boolean) {
        if (attached) hasReview = present
    }

    /** A captured recovery action cannot start a recorder after its owner has detached. */
    fun consume(action: () -> Unit) {
        if (attached && hasReview) action()
    }

    /** Keeps the marker for saved-state restoration while revoking this instance's callbacks. */
    fun release() {
        attached = false
    }
}

/** Hashes the scope, so the saved marker contains no account label or chat identifier. */
internal fun voiceReviewRecoveryOwner(
    accountRef: String?,
    groupId: String,
): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest("${accountRef?.length ?: -1}:$accountRef:${groupId.length}:$groupId".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

/** Restored presence is admitted only for the currently displayed account/chat, never a replacement scope. */
internal fun voiceReviewRecoverySaver(ownerKey: String): Saver<VoiceReviewRecoveryMarker, List<Any>> =
    Saver(
        save = { listOf(it.ownerKey, it.hasReview) },
        restore = { values ->
            VoiceReviewRecoveryMarker(ownerKey, values.getOrNull(0) == ownerKey && values.getOrNull(1) == true)
        },
    )

/** Retains only the unavailable-review hint across same-scope Activity/process recreation. */
@Composable
internal fun rememberVoiceReviewRecoveryMarker(
    accountRef: String?,
    groupId: String,
): VoiceReviewRecoveryMarker {
    val ownerKey = remember(accountRef, groupId) { voiceReviewRecoveryOwner(accountRef, groupId) }
    val marker =
        rememberSaveable(ownerKey, saver = voiceReviewRecoverySaver(ownerKey)) {
            VoiceReviewRecoveryMarker(ownerKey)
        }
    DisposableEffect(marker) { onDispose { marker.release() } }
    return marker
}

/** An unavailable finalized take can only be dismissed or replaced by a fresh explicit native recording. */
@Composable
@Suppress("FunctionNaming")
internal fun VoiceReviewUnavailableNotice(
    onDismiss: () -> Unit,
    onRecordAgain: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.voice_review_unavailable_title)) },
        text = { Text(stringResource(R.string.voice_review_unavailable_message)) },
        confirmButton = {
            TextButton(onClick = onRecordAgain) { Text(stringResource(R.string.voice_record_again)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dismiss)) }
        },
    )
}
