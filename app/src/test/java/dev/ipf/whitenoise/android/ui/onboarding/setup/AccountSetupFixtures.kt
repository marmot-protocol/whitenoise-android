package dev.ipf.whitenoise.android.ui.onboarding.setup

import dev.ipf.marmotkit.OnboardingActionFfi
import dev.ipf.marmotkit.OnboardingSnapshotFfi
import dev.ipf.marmotkit.OnboardingStatusFfi
import dev.ipf.marmotkit.OnboardingStepFfi
import dev.ipf.marmotkit.OnboardingStepStateFfi

internal val SETUP_TEST_ACCOUNT = "ab".repeat(32)

/** Six-stage fixture with only the selected checkpoint requiring a decision. */
internal fun setupSnapshot(
    step: OnboardingStepFfi = OnboardingStepFfi.PROFILE,
    actions: List<OnboardingActionFfi> = listOf(OnboardingActionFfi.CONTINUE_WITHOUT, OnboardingActionFfi.EDIT_PROFILE),
    revision: ULong = 3uL,
    ready: Boolean = false,
): OnboardingSnapshotFfi =
    OnboardingSnapshotFfi(
        SETUP_TEST_ACCOUNT,
        null,
        revision,
        ready,
        OnboardingStepFfi.entries.map { item ->
            OnboardingStepStateFfi(
                item,
                when {
                    ready || item.ordinal < step.ordinal -> OnboardingStatusFfi.PASSED
                    item == step -> OnboardingStatusFfi.NEEDS_INPUT
                    else -> OnboardingStatusFfi.PENDING
                },
                emptyList(),
                if (item == step && !ready) actions else emptyList(),
                null,
            )
        },
        null,
        null,
        false,
    )
