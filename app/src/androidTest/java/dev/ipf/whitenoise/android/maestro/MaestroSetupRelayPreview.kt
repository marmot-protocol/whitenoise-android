package dev.ipf.whitenoise.android.maestro

import dev.ipf.marmotkit.OnboardingActionFfi
import dev.ipf.marmotkit.OnboardingRelayRepairFfi
import dev.ipf.marmotkit.OnboardingRelayRepairModeFfi
import dev.ipf.marmotkit.OnboardingRelayTagFfi
import dev.ipf.marmotkit.OnboardingRelayTagRoleFfi
import dev.ipf.marmotkit.OnboardingRepairProposalFfi
import dev.ipf.marmotkit.OnboardingSnapshotFfi
import dev.ipf.marmotkit.OnboardingStepFfi

internal const val MAESTRO_SETUP_RELAY = "wss://Custom.example.invalid/path/"

/** Supplies an opaque typed native-port declaration; production code owns editor projection and review guards. */
internal fun maestroSetupRelayPreview(current: OnboardingSnapshotFfi): OnboardingSnapshotFfi {
    val tag =
        OnboardingRelayTagFfi(
            listOf("r", MAESTRO_SETUP_RELAY),
            MAESTRO_SETUP_RELAY,
            OnboardingRelayTagRoleFfi.UNMARKED,
        )
    val repair =
        OnboardingRelayRepairFfi(
            OnboardingRelayRepairModeFfi.ADDITIVE,
            "fixture-source",
            "preserved fixture content",
            "preserved fixture content",
            listOf(tag),
            listOf(tag),
            emptyList(),
        )
    return current.copy(
        revision = 4uL,
        steps =
            current.steps.map {
                if (it.step == OnboardingStepFfi.RELAYS) {
                    it.copy(actions = listOf(OnboardingActionFfi.APPROVE_REPAIR, OnboardingActionFfi.CANCEL_REPAIR))
                } else {
                    it
                }
            },
        proposal =
            OnboardingRepairProposalFfi(
                OnboardingStepFfi.RELAYS,
                4uL,
                "fixture-source",
                emptyList(),
                emptyList(),
                null,
                null,
                repair,
            ),
    )
}
