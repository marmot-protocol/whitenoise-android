package dev.ipf.whitenoise.android.ui.onboarding.setup

import dev.ipf.marmotkit.OnboardingActionFfi
import dev.ipf.marmotkit.OnboardingCheckOutcomeFfi
import dev.ipf.marmotkit.OnboardingCheckPurposeFfi
import dev.ipf.marmotkit.OnboardingFindingContextFfi
import dev.ipf.marmotkit.OnboardingFindingFfi
import dev.ipf.marmotkit.OnboardingIssueFfi
import dev.ipf.marmotkit.OnboardingRelayCapabilityFfi

/** Legacy observations stay unknown; tests must explicitly supply native evidence and per-finding grants. */
@Suppress("LongParameterList") // Explicit native evidence stays independent in test records.
internal fun setupFinding(
    issue: OnboardingIssueFfi,
    endpoint: String?,
    actions: List<OnboardingActionFfi> = emptyList(),
    context: OnboardingFindingContextFfi = OnboardingFindingContextFfi.UNKNOWN,
    purpose: OnboardingCheckPurposeFfi = OnboardingCheckPurposeFfi.UNKNOWN,
    outcome: OnboardingCheckOutcomeFfi = OnboardingCheckOutcomeFfi.UNKNOWN,
    capability: OnboardingRelayCapabilityFfi = OnboardingRelayCapabilityFfi.NONE,
    observedRevision: ULong? = null,
): OnboardingFindingFfi =
    OnboardingFindingFfi(issue, endpoint, context, purpose, outcome, capability, actions, observedRevision)
