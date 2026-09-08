package dev.ipf.whitenoise.android.ui.onboarding.setup

import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.OnboardingSnapshotFfi

/** Binds repair consent to its displayed recovery attempt, preserving support for checkpoints without an epoch. */
internal suspend fun MarmotInterface.approveSetupRepair(
    account: String,
    revision: ULong,
    recoveryEpoch: String?,
): OnboardingSnapshotFfi =
    if (recoveryEpoch == null) {
        approveOnboardingRepair(account, revision)
    } else {
        approveOnboardingRepairInEpoch(account, revision, recoveryEpoch)
    }

/** Sends the single-device grant with the epoch from the same snapshot as the reviewed revision. */
internal suspend fun MarmotInterface.acknowledgeSetupSingleDevice(
    account: String,
    revision: ULong,
    recoveryEpoch: String?,
): OnboardingSnapshotFfi =
    if (recoveryEpoch == null) {
        acknowledgeOnboardingSingleDevice(account, revision)
    } else {
        acknowledgeOnboardingSingleDeviceInEpoch(account, revision, recoveryEpoch)
    }
