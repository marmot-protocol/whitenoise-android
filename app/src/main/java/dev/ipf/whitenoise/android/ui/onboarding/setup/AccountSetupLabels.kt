package dev.ipf.whitenoise.android.ui.onboarding.setup

import dev.ipf.marmotkit.OnboardingActionFfi
import dev.ipf.marmotkit.OnboardingIssueFfi
import dev.ipf.marmotkit.OnboardingStatusFfi
import dev.ipf.marmotkit.OnboardingStepFfi
import dev.ipf.whitenoise.android.R

/** Localized labels for the native step contract. */
internal fun setupStepTitle(value: OnboardingStepFfi): Int =
    when (value) {
        OnboardingStepFfi.PROFILE -> R.string.setup_step_profile
        OnboardingStepFfi.FOLLOWS -> R.string.setup_step_follows
        OnboardingStepFfi.RELAYS -> R.string.setup_step_relays
        OnboardingStepFfi.INBOX_RELAYS -> R.string.setup_step_inbox_relays
        OnboardingStepFfi.SINGLE_DEVICE -> R.string.setup_step_single_device
        OnboardingStepFfi.KEY_PACKAGE -> R.string.setup_step_key_package
    }

/** Localized labels for the native status contract. */
internal fun setupStatusTitle(value: OnboardingStatusFfi): Int =
    when (value) {
        OnboardingStatusFfi.PENDING -> R.string.setup_status_pending
        OnboardingStatusFfi.CHECKING -> R.string.setup_status_checking
        OnboardingStatusFfi.PASSED -> R.string.setup_status_passed
        OnboardingStatusFfi.NEEDS_INPUT -> R.string.setup_status_needs_input
        OnboardingStatusFfi.RETRYABLE_FAILURE -> R.string.setup_status_retryable_failure
        OnboardingStatusFfi.WAITING_FOR_SIGNER -> R.string.setup_status_waiting_for_signer
        OnboardingStatusFfi.SKIPPED -> R.string.setup_status_skipped
    }

/** Localized labels for the native action contract. */
internal fun setupActionTitle(value: OnboardingActionFfi): Int =
    when (value) {
        OnboardingActionFfi.RETRY -> R.string.setup_action_retry
        OnboardingActionFfi.CONTINUE_WITHOUT -> R.string.setup_action_continue_without
        OnboardingActionFfi.USE_RECOMMENDED_RELAYS -> R.string.setup_action_use_recommended_relays
        OnboardingActionFfi.EDIT_RELAYS -> R.string.setup_action_edit_relays
        OnboardingActionFfi.EDIT_PROFILE -> R.string.setup_action_edit_profile
        OnboardingActionFfi.EDIT_FOLLOWS -> R.string.setup_action_edit_follows
        OnboardingActionFfi.APPROVE_REPAIR -> R.string.setup_action_approve_repair
        OnboardingActionFfi.CANCEL_REPAIR -> R.string.setup_action_cancel_repair
        OnboardingActionFfi.RECONNECT_SIGNER -> R.string.setup_action_reconnect_signer
        OnboardingActionFfi.EDIT_DISCOVERY_RELAYS -> R.string.setup_action_edit_discovery_relays
        OnboardingActionFfi.CONTINUE_ANYWAY -> R.string.setup_action_continue_anyway
        OnboardingActionFfi.CANCEL_ONBOARDING -> R.string.setup_action_cancel_onboarding
    }

/** Localized labels for the native finding contract. */
@Suppress("CyclomaticComplexMethod") // Exhaustive native finding-to-resource mapping.
internal fun setupFindingTitle(value: OnboardingIssueFfi): Int =
    when (value) {
        OnboardingIssueFfi.MISSING -> R.string.setup_finding_missing
        OnboardingIssueFfi.MALFORMED -> R.string.setup_finding_malformed
        OnboardingIssueFfi.FUTURE_DATED -> R.string.setup_finding_future_dated
        OnboardingIssueFfi.INVALID_RELAY -> R.string.setup_finding_invalid_relay
        OnboardingIssueFfi.RETIRED_RELAY -> R.string.setup_finding_retired_relay
        OnboardingIssueFfi.UNSAFE_RELAY -> R.string.setup_finding_unsafe_relay
        OnboardingIssueFfi.UNREACHABLE -> R.string.setup_finding_unreachable
        OnboardingIssueFfi.TIMED_OUT -> R.string.setup_finding_timed_out
        OnboardingIssueFfi.AUTHENTICATION_REQUIRED -> R.string.setup_finding_authentication_required
        OnboardingIssueFfi.PAYMENT_REQUIRED -> R.string.setup_finding_payment_required
        OnboardingIssueFfi.ACCESS_RESTRICTED -> R.string.setup_finding_access_restricted
        OnboardingIssueFfi.NO_USABLE_ROUTE -> R.string.setup_finding_no_usable_route
        OnboardingIssueFfi.PUBLICATION_FAILED -> R.string.setup_finding_publication_failed
        OnboardingIssueFfi.SIGNER_UNAVAILABLE -> R.string.setup_finding_signer_unavailable
        OnboardingIssueFfi.SIGNER_REJECTED -> R.string.setup_finding_signer_rejected
        OnboardingIssueFfi.RECORD_CHANGED -> R.string.setup_finding_record_changed
        OnboardingIssueFfi.INTERRUPTED -> R.string.setup_finding_interrupted
        OnboardingIssueFfi.TOO_MANY_RELAYS -> R.string.setup_finding_too_many_relays
        OnboardingIssueFfi.MULTI_DEVICE_UNSUPPORTED -> R.string.setup_finding_multi_device_unsupported
        OnboardingIssueFfi.OTHER_INSTALLATION_POSSIBLE -> R.string.setup_finding_other_installation_possible
        OnboardingIssueFfi.DISCOVERY_INCOMPLETE -> R.string.setup_finding_discovery_incomplete
    }
