@file:Suppress("FunctionNaming") // Jetpack Compose functions intentionally use PascalCase.

package dev.ipf.whitenoise.android.ui.devices

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import dev.ipf.marmotkit.SignInResponseFfi
import dev.ipf.marmotkit.UnrecognizedSignInFfi
import dev.ipf.marmotkit.UnrecognizedSignInStateFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseAlertDialog
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing

/**
 * Reviews one sign-in. Not now, Mine, don't link, This wasn't me (I don't recognize this for one from before this
 * device) and Dismiss are separate buttons that send separate answers, and closing the dialog with Back or a tap
 * outside sends none. Not now is offered only while the sign-in still waits, since MDK takes it once; Mine, don't
 * link only until it is given. The rejection says what it records and what it does not do before it is pressed.
 */
@Suppress("LongParameterList")
@Composable
internal fun SignInReviewDialog(
    signIn: UnrecognizedSignInFfi,
    onAnswer: (SignInResponseFfi) -> Unit,
    onClose: () -> Unit,
    mine: Boolean = false,
    onMine: () -> Unit = {},
    readOnly: Boolean = false,
) {
    val choice = signInAnswerChoice(signIn)
    val question = if (signIn.beforeThisDevice) R.string.sign_in_question_before else R.string.sign_in_question_new
    val waiting = signIn.state == UnrecognizedSignInStateFfi.PROMPT
    WhiteNoiseAlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(question)) },
        text = { SignInReviewText(signIn, choice, mine) },
        confirmButton = {
            when (choice) {
                SignInAnswerChoice.DismissOnly ->
                    if (!readOnly) {
                        TextButton(
                            onClick = { onAnswer(SignInResponseFfi.DISMISS) },
                            modifier = Modifier.testTag(SIGN_IN_DISMISS_TAG),
                        ) { Text(stringResource(R.string.sign_in_dismiss)) }
                    }
                SignInAnswerChoice.NotNowOrDontRecognize ->
                    RejectButton(R.string.sign_in_dont_recognize) { onAnswer(SignInResponseFfi.REJECT) }
                SignInAnswerChoice.NotNowOrReject ->
                    RejectButton(R.string.sign_in_reject) { onAnswer(SignInResponseFfi.REJECT) }
            }
        },
        dismissButton = {
            if (!readOnly && choice == SignInAnswerChoice.NotNowOrReject && waiting) {
                NotNowButton { onAnswer(SignInResponseFfi.NOT_NOW) }
            } else {
                TextButton(onClick = onClose, modifier = Modifier.testTag(SIGN_IN_CLOSE_TAG)) {
                    Text(stringResource(R.string.close))
                }
            }
            if (!readOnly && choice == SignInAnswerChoice.NotNowOrDontRecognize && waiting) {
                NotNowButton { onAnswer(SignInResponseFfi.NOT_NOW) }
            }
            if (!readOnly && choice == SignInAnswerChoice.NotNowOrReject && !mine) {
                TextButton(onClick = onMine, modifier = Modifier.testTag(SIGN_IN_MINE_TAG)) {
                    Text(stringResource(R.string.sign_in_mine))
                }
            }
        },
    )
}

/** The Not now answer. */
@Composable
private fun NotNowButton(onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.testTag(SIGN_IN_NOT_NOW_TAG)) {
        Text(stringResource(R.string.link_request_not_now))
    }
}

/** The answer that records the sign-in as not the user's, in the error colour. */
@Composable
private fun RejectButton(
    label: Int,
    onClick: () -> Unit,
) {
    TextButton(onClick = onClick, modifier = Modifier.testTag(SIGN_IN_REJECT_TAG)) {
        Text(stringResource(label), color = MaterialTheme.colorScheme.error)
    }
}

/**
 * The dialog's body: when the sign-in appeared, what it says it is, what the question means and what rejecting
 * does.
 */
@Composable
private fun SignInReviewText(
    signIn: UnrecognizedSignInFfi,
    choice: SignInAnswerChoice,
    mine: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.Related)) {
        signInWhen(signIn)?.let { Text(it) }
        signInSummary(signIn, mine).forEach { Text(it) }
        if (choice != SignInAnswerChoice.DismissOnly) {
            val detail = if (signIn.beforeThisDevice) R.string.sign_in_detail_before else R.string.sign_in_detail_new
            Text(stringResource(detail))
        }
        if (signIn.previouslyLinkedHere) Text(stringResource(R.string.sign_in_previously_linked))
        if (choice != SignInAnswerChoice.DismissOnly) Text(stringResource(R.string.sign_in_reject_confirm))
    }
}
