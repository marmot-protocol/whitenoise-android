package dev.ipf.whitenoise.android.ui.onboarding.setup

import dev.ipf.marmotkit.OnboardingCheckOutcomeFfi
import dev.ipf.marmotkit.OnboardingCheckPurposeFfi
import dev.ipf.marmotkit.OnboardingFindingContextFfi
import dev.ipf.whitenoise.android.R

/** Names the native observation's source without inferring current relay health. */
internal fun setupFindingContextTitle(value: OnboardingFindingContextFfi): Int =
    when (value) {
        OnboardingFindingContextFfi.UNKNOWN -> R.string.setup_finding_context_unknown
        OnboardingFindingContextFfi.RECORD_DISCOVERY -> R.string.setup_finding_context_discovery
        OnboardingFindingContextFfi.PUBLISHED_DECLARATION -> R.string.setup_finding_context_declaration
        OnboardingFindingContextFfi.DECLARED_ROUTE -> R.string.setup_finding_context_route
        OnboardingFindingContextFfi.REPAIR_APPROVAL -> R.string.setup_finding_context_approval
        OnboardingFindingContextFfi.REPAIR_PUBLICATION -> R.string.setup_finding_context_publication
    }

/** A directory query and an event publication are separate native checks. */
internal fun setupFindingPurposeTitle(value: OnboardingCheckPurposeFfi): Int =
    when (value) {
        OnboardingCheckPurposeFfi.UNKNOWN -> R.string.setup_finding_purpose_unknown
        OnboardingCheckPurposeFfi.RECORD_LOOKUP -> R.string.setup_finding_purpose_lookup
        OnboardingCheckPurposeFfi.DECLARATION_VALIDATION -> R.string.setup_finding_purpose_validation
        OnboardingCheckPurposeFfi.DIRECTORY_QUERY -> R.string.setup_finding_purpose_directory
        OnboardingCheckPurposeFfi.SOURCE_FRESHNESS -> R.string.setup_finding_purpose_freshness
        OnboardingCheckPurposeFfi.EVENT_PUBLICATION -> R.string.setup_finding_purpose_publication
    }

/** Unknown stays inconclusive; success applies only to the displayed check. */
internal fun setupFindingOutcomeTitle(value: OnboardingCheckOutcomeFfi): Int =
    when (value) {
        OnboardingCheckOutcomeFfi.UNKNOWN -> R.string.setup_finding_outcome_unknown
        OnboardingCheckOutcomeFfi.SUCCESS -> R.string.setup_finding_outcome_success
        OnboardingCheckOutcomeFfi.FAILURE -> R.string.setup_finding_outcome_failure
    }
