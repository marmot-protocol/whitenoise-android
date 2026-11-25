@file:Suppress("FunctionNaming") // Compose UI functions follow the framework naming convention.

package dev.ipf.whitenoise.android.ui.onboarding.setup

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ipf.marmotkit.OnboardingActionFfi
import dev.ipf.marmotkit.OnboardingCheckPurposeFfi
import dev.ipf.marmotkit.OnboardingFindingContextFfi
import dev.ipf.marmotkit.OnboardingFindingFfi
import dev.ipf.marmotkit.OnboardingIssueFfi
import dev.ipf.marmotkit.OnboardingRelayRepairModeFfi
import dev.ipf.marmotkit.OnboardingRepairProposalFfi
import dev.ipf.marmotkit.OnboardingStatusFfi
import dev.ipf.marmotkit.OnboardingStepFfi
import dev.ipf.marmotkit.OnboardingStepStateFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.theme.Dimens

/** What went wrong on the current step, in plain words, and the pinned action that can move it forward. */
internal data class SetupStepGuidance(
    val problem: Int?,
    val next: Int?,
)

/** One sentence on what a relay or key step is for; other steps carry their own explanation. */
@StringRes
internal fun setupStepPurpose(step: OnboardingStepFfi): Int? =
    when (step) {
        OnboardingStepFfi.RELAYS -> R.string.setup_purpose_relays
        OnboardingStepFfi.INBOX_RELAYS -> R.string.setup_purpose_inbox_relays
        OnboardingStepFfi.KEY_PACKAGE -> R.string.setup_purpose_key_package
        else -> null
    }

/**
 * Reads the native findings of a blocked relay or key step. A proposal under review, a signer prompt, a pending
 * cancellation and a stopped screen already say what to do, so they get no guidance here.
 */
internal fun AccountSetupState.stepGuidance(): SetupStepGuidance? {
    val snapshot = snapshot ?: return null
    val step = currentStep ?: return null
    val proposal = snapshot.proposal?.takeIf { it.step == step.step }
    val explainedElsewhere =
        step.step !in guidedSteps ||
            snapshot.cancellationPending ||
            blocksSetupActions ||
            disconnected ||
            step.status == OnboardingStatusFfi.WAITING_FOR_SIGNER ||
            proposal?.relayRepair?.mode == OnboardingRelayRepairModeFfi.MANUAL_REVIEW ||
            (proposal != null && OnboardingActionFfi.APPROVE_REPAIR in step.actions)
    if (explainedElsewhere) return null
    val guidance = SetupStepGuidance(stepProblem(step, proposal), stepNextAction(step))
    return guidance.takeIf { it.problem != null || it.next != null }
}

/** The problem sentence for the step's native status and findings; null when the heading already says it. */
@StringRes
private fun AccountSetupState.stepProblem(
    step: OnboardingStepStateFfi,
    proposal: OnboardingRepairProposalFfi?,
): Int? {
    val issues = step.findings.map { it.issue }.toSet()
    val atSave = step.findings.filter { it.context == OnboardingFindingContextFfi.REPAIR_APPROVAL }
    val newerRecord =
        atSave.any {
            it.issue == OnboardingIssueFfi.RECORD_CHANGED && it.purpose == OnboardingCheckPurposeFfi.SOURCE_FRESHNESS
        }
    return when {
        proposal != null ->
            R.string.setup_problem_publication_failed.takeIf { OnboardingIssueFfi.PUBLICATION_FAILED in issues }
        newerRecord -> R.string.setup_problem_save_record_changed
        atSave.isNotEmpty() -> R.string.setup_problem_save_refused
        step.step == OnboardingStepFfi.KEY_PACKAGE ->
            R.string.setup_problem_keys_failed.takeIf { step.status == OnboardingStatusFfi.RETRYABLE_FAILURE }
        focusesRelayTimeout() -> null
        step.status == OnboardingStatusFfi.RETRYABLE_FAILURE -> relayCheckFailure(issues)
        step.status == OnboardingStatusFfi.NEEDS_INPUT -> relayInputProblem(issues)
        else -> null
    }
}

@StringRes
private fun relayCheckFailure(issues: Set<OnboardingIssueFfi>): Int =
    when {
        OnboardingIssueFfi.NO_USABLE_ROUTE in issues -> R.string.setup_problem_no_usable_route
        issues.any {
            it == OnboardingIssueFfi.DISCOVERY_INCOMPLETE ||
                it == OnboardingIssueFfi.UNREACHABLE ||
                it == OnboardingIssueFfi.TIMED_OUT
        } -> R.string.setup_problem_check_incomplete
        else -> R.string.setup_problem_check_failed
    }

@StringRes
private fun relayInputProblem(issues: Set<OnboardingIssueFfi>): Int? =
    when {
        OnboardingIssueFfi.RETIRED_RELAY in issues -> null
        OnboardingIssueFfi.MISSING in issues -> R.string.setup_problem_missing
        issues.any { it in unusableListIssues } -> R.string.setup_problem_unusable
        else -> null
    }

/** Names the pinned way forward, so the sentence and the filled button always agree. */
@StringRes
private fun AccountSetupState.stepNextAction(step: OnboardingStepStateFfi): Int? {
    if (OnboardingActionFfi.EDIT_RELAYS in step.actions && directRemovalEndpoint() != null) {
        return R.string.setup_next_remove
    }
    val pinned = decisionButtons().map { it.first }
    return when (pinned.firstOrNull()) {
        OnboardingActionFfi.REMOVE_UNUSABLE_RELAYS -> R.string.setup_next_remove_unusable
        OnboardingActionFfi.USE_RECOMMENDED_RELAYS -> R.string.setup_next_review
        OnboardingActionFfi.EDIT_RELAYS -> R.string.setup_next_edit
        OnboardingActionFfi.RETRY ->
            when (pinned.getOrNull(1)) {
                OnboardingActionFfi.USE_RECOMMENDED_RELAYS -> R.string.setup_next_retry_then_review
                OnboardingActionFfi.EDIT_RELAYS -> R.string.setup_next_retry_then_edit
                else -> R.string.setup_next_retry
            }
        else -> null
    }
}

/** States the step's purpose, then what went wrong and what to do, directly under the heading. */
@Composable
internal fun SetupStepExplanation(state: AccountSetupState) {
    val step = state.currentStep ?: return
    setupStepPurpose(step.step)?.let {
        Text(
            stringResource(it),
            modifier = Modifier.testTag("setup-step-purpose"),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    state.stepGuidance()?.let { guidance ->
        listOfNotNull(guidance.problem, guidance.next).forEach {
            Text(
                stringResource(it),
                modifier = Modifier.testTag("setup-step-guidance"),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

/**
 * Relay problems MDK recorded on a step that still passed. They never block setup, but the user may want to fix
 * the named relays later.
 */
internal fun OnboardingStepStateFfi.passedWarnings(): List<OnboardingFindingFfi> =
    if (status != OnboardingStatusFfi.PASSED) {
        emptyList()
    } else {
        findings.filter { it.issue in relayWarningIssues && !it.endpoint.isNullOrBlank() }.distinct()
    }

/** Lists a passed step's relay problems under its checklist row, aligned with the row's title. */
@Composable
internal fun SetupPassedWarnings(
    warnings: List<OnboardingFindingFfi>,
    canRemove: Boolean,
    action: @Composable () -> Unit,
) {
    if (warnings.isEmpty() && !canRemove) return
    Column(
        Modifier.fillMaxWidth().padding(start = PASSED_WARNING_INDENT).testTag("setup-passed-warnings"),
        verticalArrangement = Arrangement.spacedBy(Dimens.spaceSm),
    ) {
        if (warnings.isNotEmpty()) {
            Text(
                stringResource(R.string.setup_passed_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            warnings.forEach { SetupFindingContent(it) }
        }
        if (canRemove) action()
    }
}

/** A checklist row's leading inset, icon and gap, so warnings start under the step title. */
private val PASSED_WARNING_INDENT = 56.dp

private val guidedSteps =
    setOf(OnboardingStepFfi.RELAYS, OnboardingStepFfi.INBOX_RELAYS, OnboardingStepFfi.KEY_PACKAGE)

/** A source that did not answer leaves the check unfinished rather than proving the list wrong. */
private val unansweredIssues =
    setOf(
        OnboardingIssueFfi.DISCOVERY_INCOMPLETE,
        OnboardingIssueFfi.UNREACHABLE,
        OnboardingIssueFfi.TIMED_OUT,
        OnboardingIssueFfi.AUTHENTICATION_REQUIRED,
        OnboardingIssueFfi.PAYMENT_REQUIRED,
        OnboardingIssueFfi.ACCESS_RESTRICTED,
    )

private val unusableListIssues =
    setOf(
        OnboardingIssueFfi.NO_USABLE_ROUTE,
        OnboardingIssueFfi.MALFORMED,
        OnboardingIssueFfi.INVALID_RELAY,
        OnboardingIssueFfi.UNSAFE_RELAY,
        OnboardingIssueFfi.TOO_MANY_RELAYS,
        OnboardingIssueFfi.FUTURE_DATED,
    )

private val relayWarningIssues =
    unansweredIssues - OnboardingIssueFfi.DISCOVERY_INCOMPLETE +
        setOf(OnboardingIssueFfi.RETIRED_RELAY, OnboardingIssueFfi.INVALID_RELAY, OnboardingIssueFfi.UNSAFE_RELAY)
