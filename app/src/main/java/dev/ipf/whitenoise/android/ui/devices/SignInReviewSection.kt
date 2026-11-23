@file:Suppress("FunctionNaming") // Jetpack Compose functions intentionally use PascalCase.

package dev.ipf.whitenoise.android.ui.devices

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import dev.ipf.marmotkit.CompromiseWarningItemFfi
import dev.ipf.marmotkit.CompromiseWarningReasonFfi
import dev.ipf.marmotkit.SignInResponseFfi
import dev.ipf.marmotkit.UnrecognizedSignInFfi
import dev.ipf.marmotkit.UnrecognizedSignInStateFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.IdentityFormatter
import dev.ipf.whitenoise.android.state.SignInListState
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseAlertDialog
import dev.ipf.whitenoise.android.ui.settings.SettingsAction
import dev.ipf.whitenoise.android.ui.settings.SettingsCallout
import dev.ipf.whitenoise.android.ui.settings.SettingsExplainer
import dev.ipf.whitenoise.android.ui.settings.SettingsGroup
import dev.ipf.whitenoise.android.ui.settings.SettingsLeadingIcon
import dev.ipf.whitenoise.android.ui.settings.SettingsLink
import dev.ipf.whitenoise.android.ui.settings.SettingsSection
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing
import java.time.format.FormatStyle

internal const val SIGN_INS_CHECK_TAG = "sign_ins.check"
internal const val SIGN_INS_CHECK_FAILED_TAG = "sign_ins.check_failed"
internal const val SIGN_INS_START_FRESH_TAG = "sign_ins.start_fresh"
internal const val SIGN_IN_REJECT_TAG = "sign_in.reject"
internal const val SIGN_IN_NOT_NOW_TAG = "sign_in.not_now"
internal const val SIGN_IN_DISMISS_TAG = "sign_in.dismiss"
internal const val COMPROMISE_WARNING_TAG = "devices.compromise_warning"
internal const val COMPROMISE_WARNING_DISMISS_TAG = "devices.compromise_warning.dismiss"

/** What the review section needs: the sign-ins, the open warning, whether this device started fresh, and busyness. */
internal data class SignInReviewState(
    val list: SignInListState = SignInListState(),
    val warning: List<CompromiseWarningItemFfi> = emptyList(),
    val startedFresh: Boolean = false,
    val busy: Boolean = false,
)

/** What the review section can ask its owner to do. */
internal data class SignInReviewActions(
    val onCheck: () -> Unit = {},
    val onOpen: (UnrecognizedSignInFfi) -> Unit = {},
    val onStartFresh: () -> Unit = {},
    val onDismissWarning: () -> Unit = {},
)

/**
 * The sentence for one warning item. A reason this build does not know is shown with the rejected-sign-in wording,
 * the generic of the two, as MDK asks for reasons added later.
 */
internal fun compromiseWarningText(reason: CompromiseWarningReasonFfi): Int =
    when (reason) {
        CompromiseWarningReasonFfi.REJECTED_SIGN_IN -> R.string.compromise_warning_rejected
        CompromiseWarningReasonFfi.FOREIGN_OWN_SLOT_KEY_PACKAGE -> R.string.compromise_warning_foreign_key
    }

/**
 * The open compromise warning, one sentence per distinct reason, with the action that marks it seen. The wording
 * never claims the account's devices stopped anything, and marking it seen is described as hiding it here only.
 */
internal fun LazyListScope.compromiseWarning(
    state: SignInReviewState,
    onDismiss: () -> Unit,
) {
    if (state.warning.isEmpty()) return
    val reasons = state.warning.map { it.reason }.distinct()
    item(key = "warning") {
        val text = reasons.map { stringResource(compromiseWarningText(it)) }.joinToString("\n\n")
        SettingsCallout(
            text = text,
            title = stringResource(R.string.compromise_warning_title),
            isError = true,
            modifier = Modifier.testTag(COMPROMISE_WARNING_TAG).semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
    item(key = "warning.dismiss") {
        SettingsGroup {
            row("dismiss_warning") { context ->
                SettingsAction(
                    context = context,
                    title = stringResource(R.string.compromise_warning_dismiss),
                    subtitle = stringResource(R.string.compromise_warning_dismiss_detail),
                    onClick = onDismiss,
                    enabled = !state.busy,
                    modifier = Modifier.testTag(COMPROMISE_WARNING_DISMISS_TAG),
                    explanation = stringResource(R.string.compromise_warning_dismiss_detail),
                    leading = { SettingsLeadingIcon(R.drawable.ic_check) },
                )
            }
        }
    }
}

/**
 * The sign-ins this device does not recognize, with the check that looks for more. A check that reached no relay
 * says so above whatever was listed before; it never empties the list. A device with sign-ins from before it and
 * nothing answered is also offered a fresh start, and one that took it is told what that means.
 */
internal fun LazyListScope.signInReview(
    state: SignInReviewState,
    actions: SignInReviewActions,
) {
    item(key = "section.sign-ins") { SettingsSection(stringResource(R.string.sign_ins_section)) }
    if (state.list.checkFailed) {
        item(key = "sign-ins.check-failed") {
            SettingsCallout(
                text = stringResource(R.string.sign_ins_check_failed),
                isError = true,
                modifier = Modifier.testTag(SIGN_INS_CHECK_FAILED_TAG).semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
    if (state.startedFresh) {
        item(key = "sign-ins.started-fresh") { SettingsExplainer(stringResource(R.string.started_fresh_note)) }
    }
    item(key = "sign-ins.rows") {
        SettingsGroup {
            row("check_sign_ins") { context ->
                SettingsAction(
                    context = context,
                    title = stringResource(R.string.sign_ins_check),
                    onClick = actions.onCheck,
                    enabled = !state.busy,
                    modifier = Modifier.testTag(SIGN_INS_CHECK_TAG),
                    explanation = stringResource(R.string.sign_ins_check_detail),
                    leading = { SettingsLeadingIcon(R.drawable.ic_refresh) },
                )
            }
            state.list.signIns.forEach { signIn ->
                row(signIn.slotIdHex) { context ->
                    SettingsLink(
                        context = context,
                        title = signIn.client.ifBlank { stringResource(R.string.sign_in_unknown_client) },
                        onClick = { actions.onOpen(signIn) },
                        modifier = Modifier.testTag("sign_ins.row.${signIn.slotIdHex}"),
                        subtitle = signInSummary(signIn).joinToString("\n"),
                        enabled = !state.busy,
                        leading = { SettingsLeadingIcon(R.drawable.ic_device) },
                    )
                }
            }
            if (offersStartFresh(state)) {
                row("start_fresh") { context ->
                    SettingsAction(
                        context = context,
                        title = stringResource(R.string.start_fresh_title),
                        subtitle = stringResource(R.string.start_fresh_detail),
                        onClick = actions.onStartFresh,
                        enabled = !state.busy,
                        modifier = Modifier.testTag(SIGN_INS_START_FRESH_TAG),
                        explanation = stringResource(R.string.start_fresh_detail),
                        leading = { SettingsLeadingIcon(R.drawable.ic_device) },
                    )
                }
            }
        }
    }
}

/**
 * Whether a fresh start is offered: this device found sign-ins from before it that still wait for an answer, and
 * it has not already started fresh. MDK decides whether the call is allowed; this only avoids offering it when
 * there is nothing it would apply to.
 */
internal fun offersStartFresh(state: SignInReviewState): Boolean =
    !state.startedFresh &&
        state.list.signIns.any { it.beforeThisDevice && it.state == UnrecognizedSignInStateFfi.PROMPT }

/** The lines under a sign-in: when it appeared or was last seen, whether it is still signed in, and its state. */
@Composable
internal fun signInSummary(signIn: UnrecognizedSignInFfi): List<String> =
    buildList {
        if (signIn.beforeThisDevice) {
            signInDate(signIn.lastSeenAt)?.let { add(stringResource(R.string.sign_in_last_seen, it)) }
        } else {
            signInDate(signIn.appearedAt)?.let { add(stringResource(R.string.sign_in_appeared, it)) }
        }
        if (!signIn.signedIn) add(stringResource(R.string.sign_in_signed_out))
        signInStateLabel(signIn.state)?.let { add(stringResource(it)) }
    }

/** The state line for a sign-in, or null while it simply waits for an answer. */
internal fun signInStateLabel(state: UnrecognizedSignInStateFfi): Int? =
    when (state) {
        UnrecognizedSignInStateFfi.PROMPT -> null
        UnrecognizedSignInStateFfi.NOT_NOW -> R.string.sign_in_not_now_state
        UnrecognizedSignInStateFfi.LINKED_ELSEWHERE -> R.string.sign_in_linked_elsewhere
        UnrecognizedSignInStateFfi.REJECTED_ELSEWHERE -> R.string.sign_in_rejected_elsewhere
    }

/** [epochSeconds] as a local medium date and short time; null when MDK gave no usable stamp. */
private fun signInDate(epochSeconds: ULong): String? =
    IdentityFormatter
        .dateTime(epochSeconds, dateStyle = FormatStyle.MEDIUM, timeStyle = FormatStyle.SHORT)
        .takeIf(String::isNotEmpty)

/** Which answers a sign-in's review offers, decided by MDK's state and whether it predates this device. */
internal enum class SignInAnswerChoice {
    /** Another device answered: only ending the prompt here is offered. */
    DismissOnly,

    /** It was signed in before this device: the user can only put it off (or link from it, or start fresh). */
    NotNowOnly,

    /** A new sign-in: the user can put it off or say it was not them. */
    NotNowOrReject,
}

/** The answers offered for [signIn]. */
internal fun signInAnswerChoice(signIn: UnrecognizedSignInFfi): SignInAnswerChoice =
    when {
        signIn.state == UnrecognizedSignInStateFfi.LINKED_ELSEWHERE ||
            signIn.state == UnrecognizedSignInStateFfi.REJECTED_ELSEWHERE -> SignInAnswerChoice.DismissOnly
        signIn.beforeThisDevice -> SignInAnswerChoice.NotNowOnly
        else -> SignInAnswerChoice.NotNowOrReject
    }

/**
 * Reviews one sign-in. Not now, This wasn't me and Dismiss are separate buttons that send separate answers, and
 * closing the dialog with Back or a tap outside sends none. This wasn't me says what it records and what it does
 * not do before it is pressed.
 */
@Composable
internal fun SignInReviewDialog(
    signIn: UnrecognizedSignInFfi,
    onAnswer: (SignInResponseFfi) -> Unit,
    onClose: () -> Unit,
) {
    val choice = signInAnswerChoice(signIn)
    val question = if (signIn.beforeThisDevice) R.string.sign_in_question_before else R.string.sign_in_question_new
    WhiteNoiseAlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(question)) },
        text = { SignInReviewText(signIn, choice) },
        confirmButton = {
            when (choice) {
                SignInAnswerChoice.DismissOnly ->
                    TextButton(
                        onClick = { onAnswer(SignInResponseFfi.DISMISS) },
                        modifier = Modifier.testTag(SIGN_IN_DISMISS_TAG),
                    ) { Text(stringResource(R.string.sign_in_dismiss)) }
                SignInAnswerChoice.NotNowOnly ->
                    TextButton(
                        onClick = { onAnswer(SignInResponseFfi.NOT_NOW) },
                        modifier = Modifier.testTag(SIGN_IN_NOT_NOW_TAG),
                    ) { Text(stringResource(R.string.link_request_not_now)) }
                SignInAnswerChoice.NotNowOrReject ->
                    TextButton(
                        onClick = { onAnswer(SignInResponseFfi.REJECT) },
                        modifier = Modifier.testTag(SIGN_IN_REJECT_TAG),
                    ) { Text(stringResource(R.string.sign_in_reject), color = MaterialTheme.colorScheme.error) }
            }
        },
        dismissButton = {
            if (choice == SignInAnswerChoice.NotNowOrReject) {
                TextButton(
                    onClick = { onAnswer(SignInResponseFfi.NOT_NOW) },
                    modifier = Modifier.testTag(SIGN_IN_NOT_NOW_TAG),
                ) { Text(stringResource(R.string.link_request_not_now)) }
            } else {
                TextButton(onClick = onClose) { Text(stringResource(R.string.close)) }
            }
        },
    )
}

/** The dialog's body: the sign-in's facts, what the question means, and what This wasn't me records. */
@Composable
private fun SignInReviewText(
    signIn: UnrecognizedSignInFfi,
    choice: SignInAnswerChoice,
) {
    Column(verticalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.Related)) {
        Text(signIn.client.ifBlank { stringResource(R.string.sign_in_unknown_client) })
        signInSummary(signIn).forEach { Text(it) }
        if (choice != SignInAnswerChoice.DismissOnly) {
            val detail = if (signIn.beforeThisDevice) R.string.sign_in_detail_before else R.string.sign_in_detail_new
            Text(stringResource(detail))
        }
        if (signIn.previouslyLinkedHere) Text(stringResource(R.string.sign_in_previously_linked))
        if (choice == SignInAnswerChoice.NotNowOrReject) Text(stringResource(R.string.sign_in_reject_confirm))
    }
}
