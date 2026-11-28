@file:Suppress("FunctionNaming") // Jetpack Compose functions intentionally use PascalCase.

package dev.ipf.whitenoise.android.ui.devices

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import dev.ipf.marmotkit.CompromiseWarningItemFfi
import dev.ipf.marmotkit.CompromiseWarningReasonFfi
import dev.ipf.marmotkit.UnrecognizedSignInFfi
import dev.ipf.marmotkit.UnrecognizedSignInStateFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.IdentityFormatter
import dev.ipf.whitenoise.android.state.SignInListState
import dev.ipf.whitenoise.android.ui.settings.SettingsAction
import dev.ipf.whitenoise.android.ui.settings.SettingsCallout
import dev.ipf.whitenoise.android.ui.settings.SettingsExplainer
import dev.ipf.whitenoise.android.ui.settings.SettingsGroup
import dev.ipf.whitenoise.android.ui.settings.SettingsLeadingIcon
import dev.ipf.whitenoise.android.ui.settings.SettingsLink
import dev.ipf.whitenoise.android.ui.settings.SettingsSection
import java.time.format.FormatStyle

internal const val SIGN_INS_CHECK_TAG = "sign_ins.check"
internal const val SIGN_INS_CHECK_FAILED_TAG = "sign_ins.check_failed"
internal const val SIGN_INS_START_FRESH_TAG = "sign_ins.start_fresh"
internal const val SIGN_IN_REJECT_TAG = "sign_in.reject"
internal const val SIGN_IN_NOT_NOW_TAG = "sign_in.not_now"
internal const val SIGN_IN_DISMISS_TAG = "sign_in.dismiss"
internal const val SIGN_IN_MINE_TAG = "sign_in.mine"
internal const val SIGN_IN_CLOSE_TAG = "sign_in.close"
internal const val COMPROMISE_WARNING_TAG = "devices.compromise_warning"
internal const val COMPROMISE_WARNING_DISMISS_TAG = "devices.compromise_warning.dismiss"

/**
 * What the review section needs: the sign-ins, the open warning, whether this device started fresh, busyness, and
 * the sign-ins answered Mine, don't link on this device.
 */
internal data class SignInReviewState(
    val list: SignInListState = SignInListState(),
    val warning: List<CompromiseWarningItemFfi> = emptyList(),
    val startedFresh: Boolean = false,
    val busy: Boolean = false,
    val mine: Set<String> = emptySet(),
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
 * The open compromise warning: one sentence per distinct reason, what the key can still do, and what to do about it,
 * with the action that hides it here. The wording never claims the account's devices stopped anything, and hiding is
 * described as affecting this device only.
 */
internal fun LazyListScope.compromiseWarning(
    state: SignInReviewState,
    onDismiss: () -> Unit,
) {
    if (state.warning.isEmpty()) return
    item(key = "warning") {
        SettingsCallout(
            text = compromiseWarningBody(state.warning, steps = true),
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
                    val mine = signIn.slotIdHex in state.mine
                    val lines = listOfNotNull(signInWhen(signIn)) + signInSummary(signIn, mine)
                    SettingsLink(
                        context = context,
                        title = lines.first(),
                        onClick = { actions.onOpen(signIn) },
                        modifier = Modifier.testTag("sign_ins.row.${signIn.slotIdHex}"),
                        subtitle = lines.drop(1).joinToString("\n"),
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

/** When a sign-in appeared, or was last seen for one from before this device; null when MDK gave no usable stamp. */
@Composable
internal fun signInWhen(signIn: UnrecognizedSignInFfi): String? =
    if (signIn.beforeThisDevice) {
        signInDate(signIn.lastSeenAt)?.let { stringResource(R.string.sign_in_last_seen, it) }
    } else {
        signInDate(signIn.appearedAt)?.let { stringResource(R.string.sign_in_appeared, it) }
    }

/**
 * The lines after a sign-in's time: the app it says it is, as a claim, whether it is still signed in, and its state.
 * The app name is written by whoever published the sign-in, so it is never shown as a fact or a title.
 */
@Composable
internal fun signInSummary(
    signIn: UnrecognizedSignInFfi,
    mine: Boolean = false,
): List<String> =
    buildList {
        val claim = signInClientClaim(signIn.client)
        if (claim != null) {
            add(stringResource(R.string.sign_in_claim, claim))
        } else {
            add(stringResource(R.string.sign_in_unknown_client))
        }
        if (!signIn.signedIn) add(stringResource(R.string.sign_in_signed_out))
        signInStateLabel(signIn.state, mine)?.let { add(stringResource(it)) }
    }

/** The state line for a sign-in, or null while it simply waits for an answer. [mine] marks Mine, don't link. */
internal fun signInStateLabel(
    state: UnrecognizedSignInStateFfi,
    mine: Boolean = false,
): Int? =
    when (state) {
        UnrecognizedSignInStateFfi.PROMPT -> if (mine) R.string.sign_in_mine_state else null
        UnrecognizedSignInStateFfi.NOT_NOW -> if (mine) R.string.sign_in_mine_state else R.string.sign_in_not_now_state
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

    /** It was signed in before this device: the user can put it off or say they don't recognize it. */
    NotNowOrDontRecognize,

    /** A new sign-in: the user can put it off, call it theirs without linking it, or say it was not them. */
    NotNowOrReject,
}

/** The answers offered for [signIn]. */
internal fun signInAnswerChoice(signIn: UnrecognizedSignInFfi): SignInAnswerChoice =
    when {
        signIn.state == UnrecognizedSignInStateFfi.LINKED_ELSEWHERE ||
            signIn.state == UnrecognizedSignInStateFfi.REJECTED_ELSEWHERE -> SignInAnswerChoice.DismissOnly
        signIn.beforeThisDevice -> SignInAnswerChoice.NotNowOrDontRecognize
        else -> SignInAnswerChoice.NotNowOrReject
    }
