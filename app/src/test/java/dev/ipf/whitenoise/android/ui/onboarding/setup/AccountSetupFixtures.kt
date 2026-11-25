package dev.ipf.whitenoise.android.ui.onboarding.setup

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.performScrollTo
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

/** Scrolls a setup node into view when the scrolling content owns it; a pinned action is already on screen. */
internal fun SemanticsNodeInteraction.scrollToIfScrollable(): SemanticsNodeInteraction {
    var parent = fetchSemanticsNode().parent
    while (parent != null) {
        if (SemanticsActions.ScrollBy in parent.config) return performScrollTo()
        parent = parent.parent
    }
    return this
}
