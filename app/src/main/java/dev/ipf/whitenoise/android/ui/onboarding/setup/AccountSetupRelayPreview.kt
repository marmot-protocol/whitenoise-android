package dev.ipf.whitenoise.android.ui.onboarding.setup

import dev.ipf.marmotkit.OnboardingActionFfi
import dev.ipf.marmotkit.OnboardingSnapshotFfi
import dev.ipf.marmotkit.OnboardingStepFfi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Releases an undisplayed proposal on rejected or failed installation, before runtime ownership ends. */
internal suspend fun AccountSetupClient.installRelayPreview(
    step: OnboardingStepFfi,
    install: (OnboardingSnapshotFfi) -> Boolean,
): OnboardingSnapshotFfi? {
    val preview = previewRelayRepair(step)
    val installed =
        try {
            install(preview)
        } catch (expectedFailure: Exception) {
            discardRelayPreview(preview, expectedFailure)
            throw expectedFailure
        }
    return if (installed) null else discardRelayPreview(preview)
}

/**
 * Discards only the still-current preview while its controller retains runtime ownership.
 * A newer checkpoint, replacement proposal or recovery attempt must remain untouched.
 */
internal suspend fun AccountSetupClient.discardRelayPreview(
    preview: OnboardingSnapshotFfi,
    originalFailure: Exception? = null,
): OnboardingSnapshotFfi? =
    withContext(NonCancellable) {
        try {
            discardCurrentPreview(preview)
        } catch (expectedCleanupFailure: Exception) {
            if (originalFailure == null) throw expectedCleanupFailure
            originalFailure.addSuppressed(expectedCleanupFailure)
            null
        }
    }

/** Cancels through the non-advancing decision path only after re-reading the saved native decision. */
private suspend fun AccountSetupClient.discardCurrentPreview(preview: OnboardingSnapshotFfi): OnboardingSnapshotFfi? {
    val latest = snapshot()
    return if (latest != null && preview.isCurrentRelayPreview(latest)) {
        execute(
            SetupRequest(
                latest.revision,
                checkNotNull(latest.proposal).step,
                OnboardingActionFfi.CANCEL_REPAIR,
                recoveryEpoch = latest.recoveryEpoch,
            ),
        )
    } else {
        null
    }
}

/** A changed checkpoint or non-cancellable decision cannot be discarded by a superseded editor load. */
private fun OnboardingSnapshotFfi.isCurrentRelayPreview(latest: OnboardingSnapshotFfi): Boolean {
    val sameCheckpoint =
        latest.accountIdHex == accountIdHex && latest.recoveryEpoch == recoveryEpoch && latest.revision == revision
    val sameProposal = proposal != null && latest.proposal == proposal
    val cancellable =
        !latest.cancellationPending &&
            latest.steps.any { it.step == proposal?.step && OnboardingActionFfi.CANCEL_REPAIR in it.actions }
    return sameCheckpoint && sameProposal && cancellable
}

/** Editor delivery must remain bound to the requested account, recovery attempt and latest visible revision. */
internal fun OnboardingSnapshotFfi.matchesRelayEditorLoad(
    account: String,
    request: SetupRequest,
    current: OnboardingSnapshotFfi?,
): Boolean {
    val owned = accountIdHex == account && recoveryEpoch == request.recoveryEpoch
    val fresh = current != null && current.revision <= revision && current.recoveryEpoch == recoveryEpoch
    return owned && fresh
}
